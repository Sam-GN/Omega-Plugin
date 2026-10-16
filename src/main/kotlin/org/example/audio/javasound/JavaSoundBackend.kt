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
package org.example.audio.javasound

import org.example.audio.Availability
import org.example.audio.AudioBackend
import org.example.audio.AudioBackendException
import org.example.audio.AudioCallback
import org.example.audio.AudioConfig
import org.example.audio.AudioDevices
import org.example.audio.AudioStream
import org.example.audio.BackendDevice
import org.example.audio.BackendId
import org.example.audio.CushionRegulator
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.Pcm
import org.example.audio.StreamEvents
import org.example.audio.StreamFormat
import org.example.audio.StreamRequest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.max

/**
 * The bottom rung of the ladder: always present, never fast.
 *
 * This is where the old `AudioEngine.pump()` went, unchanged in substance — the blocking
 * read/process/write loop, the priming pass, the oversized line buffers and the
 * [CushionRegulator] are all still here and still load-bearing, because they are answers to
 * Java Sound's behaviour and to nothing else. Keeping them behind the seam is the point: the
 * engine above no longer knows about any of it, and a backend that does not need a cushion does
 * not pay for one.
 *
 * Its reason to exist is that it cannot fail to be available. It has no native dependency, so on a
 * machine where JNA will not load, where no ASIO driver is installed, or where exclusive mode is
 * refused, the app still starts and still makes sound — at 150-200 ms round trip, which is playable
 * for checking a signal path and not playable for performing. The UI says so rather than hiding it.
 */
class JavaSoundBackend : AudioBackend {

    override val id = BackendId.JAVA_SOUND

    override val availability: Availability = Availability.Available

    /**
     * Nothing is held between calls, which is why this backend needs no [AudioBackend.rescan] body:
     * the mixer list is read from [AudioSystem] here and now, and [capabilities] is a pure function
     * of [AudioConfig] with no device in it at all. There is no cache of ours to go stale.
     *
     * What we cannot clear is the JDK's own: Java Sound enumerates its Windows devices inside
     * `com.sun.media.sound`, and hot-plug there is a long-standing platform limitation rather than
     * something a Rescan reaches. A device plugged in after launch may still not appear on this
     * rung; it will on ASIO and WASAPI, which enumerate through the OS every time.
     */
    override fun devices(): List<BackendDevice> {
        val inputs = runCatching { AudioDevices.inputs() }.getOrDefault(emptyList())
        val outputs = runCatching { AudioDevices.outputs() }.getOrDefault(emptyList())
        // One entry per physical device carrying both directions, so the UI can filter on the
        // direction it needs. Java Sound has no stable identifier, so the mixer name is it — a
        // driver rename breaks a saved selection, which is a documented limitation rather than a bug.
        val byName = LinkedHashMap<String, BackendDevice>()
        for (d in inputs) {
            byName[d.name] = BackendDevice(id, d.name, d.name, d.channels, 0)
        }
        for (d in outputs) {
            val existing = byName[d.name]
            byName[d.name] = BackendDevice(
                id, d.name, d.name,
                existing?.inputChannels ?: 0,
                d.channels,
            )
        }
        return byName.values.toList()
    }

    /**
     * The buffer sizes offered are **capture line capacities**, not callback block sizes.
     *
     * On this backend they are not the same thing and pretending otherwise would mislead: the DSP
     * block stays at [AudioConfig.BLOCK_FRAMES] whatever is chosen here, while the line capacity is
     * what actually sets the latency, because the backend derives its polling interval from it.
     */
    /**
     * [sampleRate] is ignored, and honestly so: this backend goes through the Windows shared-mode
     * mixer (or PulseAudio), which resamples whatever it is given. It therefore reports exactly one
     * rate rather than pretending to a choice it does not make — and because it reports one, the
     * pairing logic will happily match it against anything, which is correct here and only here.
     */
    override fun capabilities(device: BackendDevice, sampleRate: Int?) = DeviceCapabilities(
        sampleRates = listOf(AudioConfig.SAMPLE_RATE.toInt()),
        bufferSizes = AudioConfig.CAPTURE_BUFFER_CHOICES.map { AudioConfig.framesForMs(it) }.sorted(),
        preferredBufferFrames = AudioConfig.framesForMs(AudioConfig.CAPTURE_BUFFER_MS),
        pairing = Pairing.ANY_PAIR,
        preferredSampleRate = AudioConfig.SAMPLE_RATE.toInt(),
    )

    override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
        val input = request.input ?: throw AudioBackendException("No input device selected.")
        val output = request.output ?: throw AudioBackendException("No output device selected.")

        val inDevice = AudioDevices.inputs().firstOrNull { it.name == input.id }
            ?: throw AudioBackendException("Input device \"${input.name}\" is no longer available.")
        val outDevice = AudioDevices.outputs().firstOrNull { it.name == output.id }
            ?: throw AudioBackendException("Output device \"${output.name}\" is no longer available.")

        val inFmt = AudioConfig.format(inDevice.channels)
        val outFmt = AudioConfig.format(outDevice.channels)

        val inLine = AudioSystem.getMixer(inDevice.mixerInfo)
            .getLine(DataLine.Info(TargetDataLine::class.java, inFmt)) as TargetDataLine
        val outLine = AudioSystem.getMixer(outDevice.mixerInfo)
            .getLine(DataLine.Info(SourceDataLine::class.java, outFmt)) as SourceDataLine

        val captureMs = AudioConfig.msForFrames(request.bufferFrames).toInt()
            .coerceIn(5, 2_000)

        // Capacity, not latency. Latency is set by how much audio is actually queued at the DAC,
        // not by how much could be, and a line only a few blocks long spends most of its time in
        // the backend's 15.6 ms polling wait.
        inLine.open(inFmt, bufferBytes(captureMs, inFmt))
        try {
            outLine.open(outFmt, bufferBytes(AudioConfig.PLAYBACK_BUFFER_MS, outFmt))
        } catch (e: Exception) {
            runCatching { inLine.close() }
            throw AudioBackendException("Could not open ${output.name}: ${e.message ?: e.javaClass.simpleName}", e)
        }

        return JavaSoundStream(inLine, outLine, inFmt, outFmt, callback, events)
    }

    private fun bufferBytes(ms: Int, fmt: AudioFormat): Int {
        val block = AudioConfig.BLOCK_FRAMES
        val blocks = max(2, (AudioConfig.framesForMs(ms) + block - 1) / block)
        return blocks * block * fmt.frameSize
    }
}

/**
 * Two devices, two crystals, one blocking loop, presented as a callback.
 *
 * The thread here is ours rather than the driver's, which is the same shape a future ALSA `hw:`
 * adapter would take. Everything specific to Java Sound's misbehaviour is inside this class.
 */
internal class JavaSoundStream(
    private val inLine: TargetDataLine,
    private val outLine: SourceDataLine,
    private val inFmt: AudioFormat,
    private val outFmt: AudioFormat,
    private val callback: AudioCallback,
    private val events: StreamEvents,
) : AudioStream {

    private val block = AudioConfig.BLOCK_FRAMES

    @Volatile
    private var running = false

    @Volatile
    override var xruns: Int = 0
        private set

    @Volatile
    override var deadlineMisses: Int = 0
        private set

    @Volatile
    override var worstCallbackMicros: Int = 0
        private set

    /** Live playback queue in frames. The one number this backend's latency actually depends on. */
    @Volatile
    var cushionFrames: Int = 0
        private set

    @Volatile
    var minCushionFrames: Int = Int.MAX_VALUE
        private set

    @Volatile
    var driftCorrections: Int = 0
        private set

    @Volatile
    var overruns: Int = 0
        private set

    @Volatile
    private var framesMoved = 0L

    @Volatile
    private var firstBlockNanos = 0L

    private var settle = 0

    override val realtimeRatio: Float
        get() {
            val t0 = firstBlockNanos
            if (t0 == 0L) return 1f
            val elapsed = System.nanoTime() - t0
            if (elapsed <= 0) return 1f
            return (framesMoved * 1_000_000_000.0 / (elapsed.toDouble() * inFmt.sampleRate)).toFloat()
        }

    /**
     * Recomputed off the audio thread, because the cushion moves and a new [StreamFormat] per block
     * would be an allocation in the callback path.
     */
    @Volatile
    override var format: StreamFormat = StreamFormat(
        sampleRate = inFmt.sampleRate.toInt(),
        bufferFrames = block,
        inputLatencyFrames = block,
        outputLatencyFrames = 0,
        adapterLatencyFrames = 0,
    )
        private set

    private var thread: Thread? = null
    private var monitor: Thread? = null

    override fun start() {
        running = true
        thread = Thread(::pump, "omega-audio-javasound").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        monitor = Thread(::monitorLoop, "omega-javasound-monitor").apply { isDaemon = true; start() }
    }

    override fun stop() {
        if (!running) return
        running = false
        thread?.join(2_000)
        thread = null
        monitor?.interrupt()
        monitor = null
        cushionFrames = 0
    }

    override fun close() {
        stop()
        runCatching { inLine.stop(); inLine.close() }
        runCatching { outLine.stop(); outLine.close() }
    }

    private fun monitorLoop() {
        while (running) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
            // The round trip this backend can honestly claim: one block of capture plus whatever is
            // actually queued at the DAC. Measured, not assumed — the driver keeps a lead of its own
            // that no amount of priming takes back.
            format = format.copy(adapterLatencyFrames = cushionFrames)
        }
    }

    private fun pump() {
        val inFrame = inFmt.frameSize
        val outFrame = outFmt.frameSize

        val inBytes = ByteArray(block * inFrame)
        // One spare frame: the regulator may hand back block + 1.
        val outBytes = ByteArray((block + 1) * outFrame)
        val inputBlock = FloatArray(block)
        val outputBlock = FloatArray(block + 1)

        val capacityFrames = outLine.bufferSize / outFrame
        val primeFrames = AudioConfig.framesForMs(AudioConfig.CUSHION_MS)
            .coerceAtMost(capacityFrames - block)
            .coerceAtLeast(block)
        val silence = ByteArray(primeFrames * outFrame)

        val regulator = CushionRegulator(capacityFrames, AudioConfig.DRIFT_CORRECTION)
        val periodNanos = block * 1_000_000_000L / inFmt.sampleRate.toLong()

        // Nothing below this line allocates.
        try {
            // Prime: start an empty line and it underruns on block one and, on this backend, on
            // most blocks after it.
            var primed = 0
            while (primed < silence.size) {
                val n = outLine.write(silence, primed, silence.size - primed)
                if (n <= 0) break
                primed += n
            }

            inLine.start()
            outLine.start()
            // Whatever piled up in the capture buffer while priming is stale latency.
            inLine.flush()

            while (running) {
                if (inLine.available() >= inLine.bufferSize) overruns++

                var read = 0
                var closed = false
                while (read < inBytes.size) {
                    val n = inLine.read(inBytes, read, inBytes.size - read)
                    if (n <= 0) { closed = true; break }
                    read += n
                }
                val got = read / inFrame
                if (got == 0) {
                    if (closed) break else continue
                }

                Pcm.decode(inBytes, got, inFmt.channels, inputBlock)
                // Steady state only. Priming writes a cushion and then flushes the capture line, so
                // counting from the very first block reports a starvation that was really just the
                // stream starting — and a false dropout report is worse than none.
                if (settle < SETTLE_BLOCKS) settle++
                else if (firstBlockNanos == 0L) firstBlockNanos = System.nanoTime()
                else framesMoved += got

                val renderStart = System.nanoTime()
                callback.render(inputBlock, outputBlock, got)
                val spent = System.nanoTime() - renderStart
                if (spent > periodNanos) deadlineMisses++
                val micros = (spent / 1_000L).toInt()
                if (micros > worstCallbackMicros) worstCallbackMicros = micros

                // One pass for the output clamp and the quietest sample — the regulator splices
                // there so a corrected frame lands where it cannot be heard.
                var quietest = 0
                var quietestLevel = Float.MAX_VALUE
                for (i in 0 until got) {
                    val v = outputBlock[i].coerceIn(-1f, 1f)
                    outputBlock[i] = v
                    val level = abs(v)
                    if (i < got - 1 && level < quietestLevel) {
                        quietestLevel = level
                        quietest = i
                    }
                }

                var count = got
                val fill = (outLine.bufferSize - outLine.available()) / outFrame
                cushionFrames = fill

                if (fill <= 0 && regulator.isArmed) {
                    // The queue ran dry: the DAC has already played whatever was stale in there.
                    // Re-establish the cushion rather than limping one block ahead of empty forever.
                    xruns++
                    minCushionFrames = 0
                    outLine.write(silence, 0, silence.size)
                    regulator.resettle()
                } else {
                    count = regulator.regulate(fill, outputBlock, count, quietest)
                    driftCorrections = regulator.corrections
                    if (regulator.isArmed && fill < minCushionFrames) minCushionFrames = fill
                }

                Pcm.encode(outputBlock, count, outFmt.channels, outBytes)
                outLine.write(outBytes, 0, count * outFrame)

                if (closed) break
            }
        } catch (t: Throwable) {
            if (running) events.onDeviceLost(t.message ?: t.javaClass.simpleName)
        } finally {
            running = false
            cushionFrames = 0
        }
    }

    private companion object {
        /** ~0.4 s, the same warm-up the cushion regulator waits for before it trusts a reading. */
        const val SETTLE_BLOCKS = 64
    }
}
