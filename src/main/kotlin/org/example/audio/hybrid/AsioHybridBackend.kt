/*
 * Omega-Plugin: a realtime guitar effects processor.
 * Copyright (C) 2026  Sam Ganji <sam.ganji.n@gmail.com>
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.example.audio.hybrid

import org.example.audio.AudioBackend
import org.example.audio.AudioBackendException
import org.example.audio.AudioCallback
import org.example.audio.AudioStream
import org.example.audio.Availability
import org.example.audio.BackendDevice
import org.example.audio.BackendId
import org.example.audio.ClockBridge
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.StreamEvents
import org.example.audio.StreamFormat
import org.example.audio.StreamRequest
import org.example.audio.asio.AsioBackend
import org.example.audio.asio.AsioStream
import org.example.audio.wasapi.WasapiHybridBackend

/**
 * ASIO hybrid (2.3.0): the input from an interface's ASIO driver, the output through Windows,
 * shared at its shortest period exactly as WASAPI hybrid's is, joined by a [ClockBridge].
 *
 * Why, from one interface: a Pocket Master under WASAPI — exclusive and shared alike — stopped
 * capturing for 20 to 50 ms about every fifteen seconds, its own capture timestamps showed it, and
 * no buffer can bring back audio that was never recorded. Under its ASIO driver, at the same
 * period, a minute ran without a dropout. So the input comes from there, and the output stays where
 * WASAPI hybrid put it: the computer's own speakers or jack, with everything else on the computer
 * still playing through them.
 *
 * Built from the two existing backends rather than beside them: [AsioBackend] opens the driver as
 * it always does, with a callback that writes the input into the bridge and sends the interface's
 * own outputs silence, and [WasapiHybridBackend] opens its output alone, reading the bridge. The
 * bridge converts the input's rate — 44.1 kHz on the Pocket Master — and follows the drift between
 * the two clocks, never splicing. Nothing in either backend changes for the modes that use them
 * alone.
 *
 * Devices are both backends' lists, ASIO drivers as inputs and Windows endpoints as outputs, under
 * prefixed ids so each is routed back to its own backend.
 */
class AsioHybridBackend : AudioBackend {

    override val id = BackendId.ASIO_HYBRID

    private val asio = AsioBackend()
    private val wasapi = WasapiHybridBackend()

    override val availability: Availability = when {
        !asio.availability.isAvailable -> asio.availability
        !wasapi.availability.isAvailable -> wasapi.availability
        else -> Availability.Available
    }

    override val convertsInput: Boolean get() = true

    /** Our device id to the device its own backend knows. Rebuilt by [devices]. */
    @Volatile
    private var originals: Map<String, BackendDevice> = emptyMap()

    override fun devices(): List<BackendDevice> {
        if (!availability.isAvailable) return emptyList()
        val map = LinkedHashMap<String, BackendDevice>()
        val listed = ArrayList<BackendDevice>()
        for (d in runCatching { asio.devices() }.getOrDefault(emptyList())) {
            if (d.inputChannels <= 0) continue
            val ours = d.copy(backend = id, id = ASIO_PREFIX + d.id, outputChannels = 0)
            map[ours.id] = d
            listed += ours
        }
        for (d in runCatching { wasapi.devices() }.getOrDefault(emptyList())) {
            if (d.outputChannels <= 0) continue
            val ours = d.copy(backend = id, id = OUT_PREFIX + d.id, inputChannels = 0)
            map[ours.id] = d
            listed += ours
        }
        originals = map
        return listed
    }

    private fun original(device: BackendDevice): BackendDevice =
        originals[device.id] ?: throw AudioBackendException("${device.name} is no longer listed; press Rescan.")

    override fun capabilities(device: BackendDevice, sampleRate: Int?): DeviceCapabilities {
        val o = originals[device.id]
            ?: return DeviceCapabilities(emptyList(), emptyList(), 0, Pairing.ANY_PAIR)
        // Two devices on two clocks, always: the pickers are separate whatever either backend says.
        return if (device.id.startsWith(ASIO_PREFIX)) {
            asio.capabilities(o, sampleRate).copy(pairing = Pairing.ANY_PAIR)
        } else {
            wasapi.capabilities(o, sampleRate).copy(pairing = Pairing.ANY_PAIR, hasControlPanel = false)
        }
    }

    override fun openControlPanel(device: BackendDevice) {
        if (device.id.startsWith(ASIO_PREFIX)) asio.openControlPanel(original(device))
    }

    override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
        val input = request.input?.takeIf { it.id.startsWith(ASIO_PREFIX) }
            ?: throw AudioBackendException("Choose an ASIO input.")
        val output = request.output?.takeIf { it.id.startsWith(OUT_PREFIX) }
            ?: throw AudioBackendException("Choose a Windows output.")
        val asioDevice = original(input)
        val outDevice = original(output)

        // Set before the driver starts, read on its thread: the bridge is made once the driver has
        // said what rate and size it runs at.
        val capture = BridgeWriter()
        val asioEvents = object : StreamEvents {
            // A reset that changes the input's rate leaves the bridge converting the wrong ratio;
            // one that changes only the size the bridge takes in its stride.
            override fun onFormatChanged(format: StreamFormat) {
                if (format.sampleRate != capture.rate) {
                    events.onDeviceLost(
                        "${asioDevice.name} changed its rate to ${format.sampleRate} Hz in its own panel. " +
                            "Press Start again.",
                    )
                }
            }
            override fun onDeviceLost(reason: String) = events.onDeviceLost(reason)
            override fun onFatal(reason: String) = events.onFatal(reason)
        }
        val asioStream = asio.open(
            StreamRequest(
                input = asioDevice,
                inputChannel = request.inputChannel,
                output = asioDevice,
                // Its outputs are opened because ASIO opens both directions, and given silence.
                outputChannels = intArrayOf(0),
                sampleRate = request.sampleRate,
                bufferFrames = request.bufferFrames,
            ),
            capture,
            asioEvents,
        )
        try {
            val inFormat = asioStream.format
            val bridge = ClockBridge(inFormat.sampleRate, request.sampleRate, inFormat.bufferFrames)
            capture.rate = inFormat.sampleRate
            capture.bridge = bridge
            val render = wasapi.openRenderOnly(
                outDevice, request, callback, events, bridge, inFormat.sampleRate, inFormat.bufferFrames,
            )
            return AsioHybridStream(asioStream, render, bridge, inFormat, request.sampleRate)
        } catch (t: Throwable) {
            runCatching { asioStream.close() }
            throw if (t is AudioBackendException) t else AudioBackendException(
                "Could not open ${output.name}: ${t.message ?: t.javaClass.simpleName}", t,
            )
        }
    }

    override fun rescan() {
        runCatching { asio.rescan() }
        runCatching { wasapi.rescan() }
    }

    override fun shutdown() {
        runCatching { asio.shutdown() }
        runCatching { wasapi.shutdown() }
    }

    /**
     * The ASIO callback: the input into the bridge, stamped with when it landed, and silence back to
     * the interface. On the driver's thread; no allocation, no lock.
     */
    private class BridgeWriter : AudioCallback {
        @Volatile var bridge: ClockBridge? = null
        @Volatile var rate = 0

        override fun render(input: FloatArray, output: FloatArray, frames: Int) {
            bridge?.write(input, frames, System.nanoTime())
            java.util.Arrays.fill(output, 0, frames, 0f)
        }
    }

    internal companion object {
        const val ASIO_PREFIX = "asio:"
        const val OUT_PREFIX = "out:"
    }
}

/**
 * The two streams as one. The chain runs on the output's thread, so its timing figures are the
 * output's; dropouts are either side's.
 */
private class AsioHybridStream(
    private val input: AudioStream,
    private val output: AudioStream,
    private val bridge: ClockBridge,
    private val inputFormat: StreamFormat,
    private val engineRate: Int,
) : AudioStream {

    /** The output's format, with the input's latency and rate where they belong. */
    override val format: StreamFormat
        get() {
            val out = output.format
            return out.copy(
                inputLatencyFrames = Math.round(inputFormat.inputLatencyFrames.toLong() * engineRate.toFloat() / inputFormat.sampleRate),
                inputRate = inputFormat.sampleRate,
            )
        }

    override val xruns: Int get() = input.xruns + output.xruns
    override val deadlineMisses: Int get() = output.deadlineMisses
    override val worstCallbackMicros: Int get() = output.worstCallbackMicros
    override val realtimeRatio: Float get() = output.realtimeRatio

    override val healthNote: String?
        get() {
            val gap = (input as? AsioStream)?.worstGapMicros ?: 0
            return (output.healthNote ?: "") +
                "; ASIO input: ${inputFormat.bufferFrames} frames at ${inputFormat.sampleRate} Hz, " +
                "longest gap between callbacks ${"%.1f".format(gap / 1000f)} ms, its own dropouts ${input.xruns}"
        }

    override fun start() {
        // The input first, so the bridge is filling by the time the output first asks.
        input.start()
        try {
            output.start()
        } catch (t: Throwable) {
            runCatching { input.stop() }
            throw t
        }
    }

    override fun stop() {
        runCatching { output.stop() }
        runCatching { input.stop() }
    }

    override fun close() {
        runCatching { output.close() }
        runCatching { input.close() }
    }
}
