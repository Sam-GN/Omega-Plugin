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
package org.example.audio.wasapi

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.example.audio.Availability
import org.example.audio.AudioBackend
import org.example.audio.AudioBackendException
import org.example.audio.AudioCallback
import org.example.audio.AudioStream
import org.example.audio.BackendDevice
import org.example.audio.BackendId
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.SampleRates
import org.example.audio.StreamEvents
import org.example.audio.StreamFormat
import org.example.audio.StreamRequest
import org.example.audio.win.ComObject
import org.example.audio.win.Rt
import org.example.audio.win.hrName
import org.example.util.Log

/**
 * Preference order for the sample word: the widest the driver will take in exclusive mode.
 *
 * File-level rather than private to either class, because both the capability probe and the stream's
 * "what does this device actually support" error message have to agree on the list. They disagreeing
 * is how a device gets told it is unsupported by one and supported by the other.
 */
internal val SUPPORTED_BITS = listOf(24 to false, 32 to true, 16 to false)

/** Rates worth asking about. Higher ones shorten the period for the same buffer size. */
internal val CANDIDATE_RATES = listOf(44_100, 48_000, 88_200, 96_000)

/**
 * WASAPI in **exclusive**, event-driven mode: the fallback when there is no ASIO driver.
 *
 * Exclusive mode bypasses the Windows shared-mode mixer entirely, which is where the ~47 ms of fixed
 * overhead on the Java Sound path comes from. Measured on this machine it grants a 3.0 ms period in
 * both directions and held it for thousands of callbacks without a single discontinuity.
 *
 * It is a rung below ASIO for two structural reasons, both visible in the code:
 *
 * - **Two endpoints, two clocks.** Input and output are separate `IAudioClient`s that are not
 *   sample-locked even when they are the same physical box, so a ring and a slow drift correction
 *   sit between them, and one render period of the round trip is spent crossing it. That cost is
 *   declared honestly in [StreamFormat.adapterLatencyFrames] rather than hidden.
 * - **It takes the device from the rest of the system.** Nothing else can play through it while the
 *   stream is open, and a driver left un-released can stay stuck until a reboot — which is why
 *   [close] releases in a `finally` and the app exits deliberately.
 */
open class WasapiBackend(private val shareMode: Int = WasapiIds.EXCLUSIVE) : AudioBackend {

    private val shared get() = shareMode == WasapiIds.SHARED

    override val id =
        if (shareMode == WasapiIds.SHARED) BackendId.WASAPI_SHARED else BackendId.WASAPI_EXCLUSIVE

    override val availability: Availability =
        if (Rt.isWindows) Availability.Available
        else Availability.Unavailable("WASAPI is a Windows audio API")

    override fun devices(): List<BackendDevice> {
        if (!availability.isAvailable) return emptyList()
        return runCatching { onComThread { enumerate() } }.getOrDefault(emptyList())
    }

    private fun enumerate(): List<BackendDevice> {
        val en = MMDeviceEnumerator.create()
        val byId = LinkedHashMap<String, BackendDevice>()
        try {
            for (flow in intArrayOf(WasapiIds.CAPTURE, WasapiIds.RENDER)) {
                for (d in en.list(flow)) {
                    try {
                        val devId = d.id()
                        val name = d.friendlyName()
                        val existing = byId[devId]
                        byId[devId] = BackendDevice(
                            backend = id,
                            id = devId,
                            name = name,
                            inputChannels = if (flow == WasapiIds.CAPTURE) 2 else existing?.inputChannels ?: 0,
                            outputChannels = if (flow == WasapiIds.RENDER) 2 else existing?.outputChannels ?: 0,
                        )
                    } catch (_: Throwable) {
                        // A dead endpoint must not take the enumeration down with it.
                    } finally {
                        d.release()
                    }
                }
            }
        } finally {
            en.release()
        }
        return byId.values.toList()
    }

    /**
     * Exclusive mode only takes whole multiples of a device period, so the choices are exactly that.
     *
     * The base is `GetDevicePeriod`'s **default**, not its minimum, and that is a measured decision
     * rather than caution: the interface this was developed against reports a 3.0 ms minimum and
     * then refuses it outright with `AUDCLNT_E_INVALID_DEVICE_PERIOD` when the stream is
     * event-driven, while accepting its 6.0 ms default. The minimum period is what the shared-mode
     * engine can run at; it is not a promise about exclusive mode. Offering it anyway would put a
     * value in the picker that cannot work, which reads to a user as our bug — and `open` would
     * then have to explain a failure that enumeration should have prevented.
     */
    private val capabilityCache = HashMap<String, DeviceCapabilities>()

    @Volatile
    private var streaming = false

    /** A [rescan] that arrived mid-stream, owed to the next moment we are allowed to probe. */
    @Volatile
    private var invalidateWhenIdle = false

    /**
     * Drops every probe, so the next [capabilities] call asks the hardware again.
     *
     * This is what makes a Windows sound-setting change visible without a restart. The endpoint's
     * supported rates come from `IsFormatSupported` and its buffer sizes from a real `Initialize`,
     * and both answer differently after the user changes the device's format in Sound settings —
     * which is exactly what they are told to do when two devices share no sample rate. Without this,
     * the app went on repeating the old refusal until the process was restarted.
     *
     * **Deferred while a stream is open**, rather than ignored or taken immediately. Probing opens
     * the device exclusively and would interrupt our own stream, so [capabilities] returns a generic
     * fallback while [streaming] — clearing now would leave the buffer picker showing sizes that
     * belong to no device on this machine until the stream stopped. The note is kept and redeemed
     * when the stream closes, which is the first moment the truthful answer can be fetched.
     */
    override fun rescan() {
        if (streaming) {
            invalidateWhenIdle = true
            Log.info("wasapi", "${id.displayName}: rescan during a stream — probes will be dropped at stop.")
            return
        }
        dropProbes("rescan")
    }

    /**
     * The line that explains this whole bug class in a stranger's log: a count and the reason.
     *
     * "What the machine did rather than what the user did" — a rescan that discards four probes and
     * then reports the same rate conflict says the device really has not changed, while one that
     * discards nothing says the invalidation never reached this backend.
     *
     * Never called from the audio thread: [rescan] runs on the event thread and the deferred path
     * runs on whichever thread closed the stream.
     */
    private fun dropProbes(why: String) {
        val dropped = synchronized(capabilityCache) {
            val n = capabilityCache.size
            capabilityCache.clear()
            n
        }
        Log.info("wasapi", "${id.displayName}: $why dropped $dropped cached device probe(s); next query re-probes.")
    }

    /**
     * Cached per **device and rate**, because on this backend they are not separable.
     *
     * Exclusive-mode buffers are whole multiples of a device period expressed in time, so the legal
     * frame counts for one endpoint differ between 44.1 and 48 kHz. Caching on the device alone
     * would hand the picker the sizes for whichever rate happened to be probed first, and the driver
     * would then reject them.
     */
    override fun capabilities(device: BackendDevice, sampleRate: Int?): DeviceCapabilities {
        val fallback = DeviceCapabilities(listOf(44_100, 48_000), listOf(480), 480, Pairing.ANY_PAIR)
        val key = "${device.id}@${sampleRate ?: 0}"
        synchronized(capabilityCache) { capabilityCache[key] }?.let { return it }
        // Probing opens the device exclusively for a moment, which would interrupt our own stream.
        if (streaming) return fallback
        val probed = runCatching { onComThread { probeCapabilities(device, sampleRate) } }.getOrNull()
            ?: fallback
        synchronized(capabilityCache) { capabilityCache[key] = probed }
        return probed
    }

    /**
     * Finds the buffer sizes this endpoint will *actually* take, by trying them.
     *
     * Arithmetic on `GetDevicePeriod` is not good enough, and the interface this was developed
     * against is why: it reports a 3.0 ms minimum, refuses it with
     * `AUDCLNT_E_INVALID_DEVICE_PERIOD` when the stream is event-driven, and then happily accepts
     * 6.0 ms — which the default-period arithmetic would also have missed, in the other direction,
     * by offering nothing below 10 ms. Four `Initialize` calls settle it in about a tenth of a
     * second, and the answer is cached.
     */
    private fun probeCapabilities(device: BackendDevice, wantRate: Int?): DeviceCapabilities {
        val flow = if (device.outputChannels > 0) WasapiIds.RENDER else WasapiIds.CAPTURE
        val en = MMDeviceEnumerator.create()
        try {
            val dev = en.list(flow).firstOrNull { runCatching { it.id() == device.id }.getOrDefault(false) }
                ?: return DeviceCapabilities(listOf(48_000), listOf(480), 480, Pairing.ANY_PAIR)
            if (shared) return try { probeShared(dev) } finally { dev.release() }
            try {
                val first = dev.audioClientOrNull()
                    ?: return DeviceCapabilities(listOf(48_000), listOf(480), 480, Pairing.ANY_PAIR)
                val period = first.devicePeriod()
                val mixRate = first.mixFormatRate()
                val rates = CANDIDATE_RATES.filter { rate ->
                    SUPPORTED_BITS.any { (bits, isFloat) ->
                        val wfx = AudioClient.waveFormat(2, rate, bits, isFloat)
                        try {
                            first.isFormatSupported(WasapiIds.EXCLUSIVE, wfx) == 0
                        } finally {
                            wfx.close()
                        }
                    }
                }
                first.release()

                // Buffer sizes are only meaningful for one rate, so pick the one the caller means:
                // what it asked for if this device can do it, else what Windows has it set to, else
                // whatever it does support.
                val preferred = mixRate.takeIf { it in rates } ?: rates.firstOrNull() ?: 48_000
                val rate = wantRate?.takeIf { it in rates } ?: preferred

                val minFrames = (period[1] * rate / 10_000_000.0).toInt().coerceAtLeast(16)
                val defaultFrames = (period[0] * rate / 10_000_000.0).toInt().coerceAtLeast(minFrames)
                val candidates = sortedSetOf(minFrames, minFrames * 2, minFrames * 4, defaultFrames, defaultFrames * 2)
                // No fallback when nothing passes. An empty list is the truthful answer for an
                // endpoint that will not run in exclusive mode on any size we can offer, and it is
                // what lets the UI say so instead of offering a size that is certain to fail.
                val sizes = candidates.filter { accepts(dev, rate, it) }
                return DeviceCapabilities(
                    sampleRates = rates.ifEmpty { listOf(preferred) },
                    bufferSizes = sizes,
                    // The smallest that survived a real Initialize in exactly the mode the stream
                    // will use. Acceptance here is a fact rather than arithmetic, so there is no
                    // reason to hold a size back — and larger ones are still on the list.
                    preferredBufferFrames = sizes.firstOrNull() ?: 0,
                    pairing = Pairing.ANY_PAIR,
                    preferredSampleRate = preferred,
                )
            } finally {
                dev.release()
            }
        } finally {
            en.release()
        }
    }

    /**
     * One throwaway `Initialize` in exactly the mode the stream will use, and then the check that
     * matters: **did the driver grant the size it was asked for?**
     *
     * A successful `Initialize` is not on its own proof that a size works. The AORUS HDMI output
     * accepts a 144-frame request, returns `S_OK`, reports a 192-frame buffer, and then goes on
     * firing its event every 144 frames — so the event-driven contract of one buffer per event
     * pushes a third more audio than the device can take, and it xruns three hundred times a second.
     * Nothing in the return code says so; only the size does.
     *
     * Requiring granted == requested is what keeps that size out of the picker in the first place,
     * which is the same rule the rest of this backend follows: never offer what cannot work. The
     * alternative — writing `bufferSize - GetCurrentPadding()` per event — was tried and is worse:
     * padding behaviour in this mode is driver-dependent, and on a well-behaved wireless endpoint it
     * starved the stream to 37 % of realtime.
     */
    /**
     * Shared mode does not negotiate, so there is nothing to search — only things to read back.
     *
     * The rate is whatever Windows has the endpoint set to; a client cannot ask for another one and
     * be given it, the engine would convert instead. So exactly one rate is offered, and the message
     * when two devices disagree is genuinely actionable here — unlike in exclusive mode, telling
     * someone to change the rate in Sound settings is the right advice, because that setting *is*
     * the rate.
     *
     * Buffer sizes come from initialising for real and reading back what the engine granted. It
     * rounds up to its own period, so asking is the only way to know, and there is no reason to
     * refuse a rounded size the way exclusive mode must — in shared mode rounding is the contract
     * rather than a driver misbehaving.
     */
    private fun probeShared(dev: MMDevice): DeviceCapabilities {
        val empty = DeviceCapabilities(emptyList(), emptyList(), 0, Pairing.ANY_PAIR)
        val ac = dev.audioClientOrNull() ?: return empty
        val mix = ac.mixFormat()
        if (mix == null) {
            ac.release()
            return empty
        }
        val info = AudioClient.describe(mix)
        val period = runCatching { ac.devicePeriod() }.getOrNull()
        ac.release()

        val base = period?.let { (it[0] * info.rate / 10_000_000.0).toInt() }?.coerceAtLeast(16) ?: 480
        val granted = sortedSetOf<Int>()

        // `IAudioClient3`'s low-latency shared mode was tried here and removed, because on this
        // machine it buys nothing — see the note on [AudioClient.sharedModeEnginePeriod]. The query
        // is kept behind `-Domega.wasapi.debug` because it is how that was established, and it is
        // the first thing to check on any machine where shared mode seems needlessly slow.
        if (System.getProperty("omega.wasapi.debug") != null) {
            val ac3 = dev.audioClient3OrNull()
            val periods = ac3?.let { runCatching { it.sharedModeEnginePeriod(mix) }.getOrNull() }
            ac3?.release()
            System.err.println(
                "[ac3] ${dev.friendlyName()}: client3=${ac3 != null} " +
                    "default/fundamental/min/max = ${periods?.joinToString() ?: "unavailable"}"
            )
        }

        for (multiple in intArrayOf(1, 2, 4)) {
            grantedSharedFrames(dev, mix, base * multiple)?.let { granted += it }
        }
        Ole32.INSTANCE.CoTaskMemFree(mix)

        return DeviceCapabilities(
            sampleRates = listOf(info.rate),
            bufferSizes = granted.toList(),
            preferredBufferFrames = granted.firstOrNull() ?: 0,
            pairing = Pairing.ANY_PAIR,
            preferredSampleRate = info.rate,
        )
    }

    /** Initialises for real and reports the size the engine actually handed back, or null. */
    private fun grantedSharedFrames(dev: MMDevice, mix: Pointer, frames: Int): Int? {
        val ac = dev.audioClientOrNull() ?: return null
        return try {
            val rate = AudioClient.describe(mix).rate
            val duration = frames * 10_000_000L / rate
            // Periodicity must be zero in shared mode — the engine owns the period, and passing a
            // non-zero value here is an outright E_INVALIDARG.
            val hr = ac.initialize(
                WasapiIds.SHARED,
                WasapiIds.STREAMFLAGS_EVENTCALLBACK or WasapiIds.STREAMFLAGS_NOPERSIST,
                duration, 0L, mix,
            )
            if (hr != 0) null else runCatching { ac.bufferSizeFrames() }.getOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            ac.release()
        }
    }

    private fun accepts(dev: MMDevice, rate: Int, frames: Int): Boolean {
        val ac = dev.audioClientOrNull() ?: return false
        var wfx: com.sun.jna.Memory? = null
        return try {
            for ((bits, isFloat) in SUPPORTED_BITS) {
                val candidate = AudioClient.waveFormat(2, rate, bits, isFloat)
                if (ac.isFormatSupported(WasapiIds.EXCLUSIVE, candidate) == 0) { wfx = candidate; break }
                candidate.close()
            }
            val format = wfx ?: return false
            val duration = frames * 10_000_000L / rate
            val hr = ac.initialize(
                WasapiIds.EXCLUSIVE,
                WasapiIds.STREAMFLAGS_EVENTCALLBACK or WasapiIds.STREAMFLAGS_NOPERSIST,
                duration, duration, format,
            )
            hr == 0 && runCatching { ac.bufferSizeFrames() }.getOrDefault(-1) == frames
        } catch (_: Throwable) {
            false
        } finally {
            wfx?.close()
            ac.release()
        }
    }

    override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
        if (!availability.isAvailable) throw AudioBackendException(availability.reason ?: "unavailable")
        val input = request.input ?: throw AudioBackendException("No input device selected.")
        val output = request.output ?: throw AudioBackendException("No output device selected.")

        // Snap each direction to a size *that endpoint* accepts, separately.
        //
        // They do not have to match: these are two independent clients joined by a ring, so the
        // capture side can run at 144 frames while the render side runs at 576. Forcing one number
        // on both is what put an HDMI output on a size it merely tolerated — the driver then round
        // it up, kept its old period, and broke up three hundred times a second. Probing must happen
        // before `streaming` is set, because the probe itself opens the device.
        val rate = request.sampleRate
        val inSizes = capabilities(input, rate).bufferSizes
        val outSizes = capabilities(output, rate).bufferSizes
        if (inSizes.isEmpty() || outSizes.isEmpty()) {
            val bad = if (inSizes.isEmpty()) input else output
            throw AudioBackendException(
                if (shared) {
                    "${bad.name} would not open in shared mode at ${SampleRates.format(rate)}."
                } else {
                    "${bad.name} has no usable buffer size at ${SampleRates.format(rate)} in " +
                        "exclusive mode — its driver does not honour any size offered. Use a " +
                        "different device, or ASIO if your interface has a driver for it."
                }
            )
        }
        val captureFrames = inSizes.minByOrNull { kotlin.math.abs(it - request.bufferFrames) }!!
        val renderFrames = outSizes.minByOrNull { kotlin.math.abs(it - request.bufferFrames) }!!

        streaming = true
        return WasapiStream(
            input, output, request, callback, events, captureFrames, renderFrames, shareMode,
        ) {
            streaming = false
            // Runs on whoever closed the stream, never on an audio thread — the render and capture
            // threads have already been joined by `stop()` before `close()` gets here.
            if (invalidateWhenIdle) {
                invalidateWhenIdle = false
                dropProbes("deferred rescan")
            }
        }
    }

    override fun shutdown() {
        streaming = false
    }

    private companion object {
        /**
         * COM has to be initialised on whatever thread calls it, and the EDT is not ours to
         * initialise — `CoInitializeEx` there could conflict with Swing's own drag-and-drop STA.
         */
        fun <T> onComThread(body: () -> T): T {
            var result: Any? = null
            var error: Throwable? = null
            val t = Thread({
                Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
                try {
                    result = body()
                } catch (e: Throwable) {
                    error = e
                } finally {
                    Ole32.INSTANCE.CoUninitialize()
                }
            }, "omega-wasapi-com")
            t.isDaemon = true
            t.start()
            t.join(20_000)
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }
}

/**
 * One duplex stream: a capture thread filling a ring, a render thread draining it through the
 * effect chain.
 *
 * The ring is single-producer/single-consumer with plain volatile indices — no lock, because a lock
 * held by the capture thread while the render thread wants it is exactly the stall this design
 * exists to avoid.
 */
internal class WasapiStream(
    private val inputDevice: BackendDevice,
    private val outputDevice: BackendDevice,
    private val request: StreamRequest,
    private val callback: AudioCallback,
    private val events: StreamEvents,
    /** Size this endpoint accepts, per direction — they need not be equal. */
    private val captureTargetFrames: Int,
    private val renderTargetFrames: Int,
    private val shareMode: Int,
    private val onClosed: () -> Unit,
) : AudioStream {

    private val shared get() = shareMode == WasapiIds.SHARED

    private val ring = FloatArray(1 shl 15)
    private val ringMask = ring.size - 1

    @Volatile private var ringWrite = 0
    @Volatile private var ringRead = 0

    @Volatile override var xruns: Int = 0; private set
    @Volatile override var deadlineMisses: Int = 0; private set
    @Volatile override var worstCallbackMicros: Int = 0; private set
    @Volatile var driftCorrections: Int = 0; private set

    @Volatile private var running = false
    @Volatile private var opened = false
    @Volatile private var primed = false
    @Volatile private var failure: String? = null

    private var captureThread: Thread? = null
    private var renderThread: Thread? = null

    @Volatile private var framesRendered = 0L
    @Volatile private var firstRenderNanos = 0L
    private var settle = 0
    private var overrunCheck = 0

    override val realtimeRatio: Float
        get() {
            val t0 = firstRenderNanos
            if (t0 == 0L) return 1f
            val elapsed = System.nanoTime() - t0
            if (elapsed <= 0) return 1f
            return (framesRendered * 1_000_000_000.0 / (elapsed.toDouble() * negotiatedRate)).toFloat()
        }

    @Volatile private var renderFrames = 0
    @Volatile private var captureFrames = 0
    @Volatile private var renderLatency100ns = 0L
    @Volatile private var captureLatency100ns = 0L
    @Volatile private var negotiatedRate = request.sampleRate

    private val ready = java.util.concurrent.CountDownLatch(2)

    override var format: StreamFormat = StreamFormat(request.sampleRate, request.bufferFrames, 0, 0, 0)
        private set

    override fun start() {
        running = true
        captureThread = Thread({ run(false) }, "omega-wasapi-capture").apply { isDaemon = true; start() }
        renderThread = Thread({ run(true) }, "omega-wasapi-render").apply { isDaemon = true; start() }
        if (!ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            stop()
            throw AudioBackendException(failure ?: "The audio device did not start within 10 seconds.")
        }
        failure?.let { stop(); throw AudioBackendException(it) }
        opened = true

        val rate = negotiatedRate
        format = StreamFormat(
            sampleRate = rate,
            bufferFrames = renderFrames,
            inputLatencyFrames = ((captureLatency100ns * rate) / 10_000_000L).toInt().coerceAtLeast(captureFrames),
            outputLatencyFrames = ((renderLatency100ns * rate) / 10_000_000L).toInt().coerceAtLeast(renderFrames),
            // The hop across the ring between two unlocked clocks. Declared, not hidden.
            adapterLatencyFrames = renderFrames,
        )
    }

    override fun stop() {
        running = false
        captureThread?.join(2_000); captureThread = null
        renderThread?.join(2_000); renderThread = null
    }

    override fun close() {
        stop()
        onClosed()
    }

    private fun run(isRender: Boolean) {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED)
        var mmcss: WinNT.HANDLE? = null
        var client: AudioClient? = null
        var timerRaised = false
        try {
            val enumerator = MMDeviceEnumerator.create()
            val wanted = if (isRender) outputDevice.id else inputDevice.id
            val dev = enumerator.list(if (isRender) WasapiIds.RENDER else WasapiIds.CAPTURE)
                .firstOrNull { runCatching { it.id() == wanted } .getOrDefault(false) }
                ?: throw AudioBackendException(
                    "${if (isRender) outputDevice.name else inputDevice.name} is no longer available."
                )

            var ac = dev.audioClientOrNull()
                ?: throw AudioBackendException("Windows would not activate ${dev.friendlyName()}.")
            client = ac

            var wfx: Memory? = null
            /** Shared mode's format is COM-allocated by `GetMixFormat` and freed differently. */
            var mixFormat: Pointer? = null
            var channels = 0
            var bits = 0
            var isFloat = false
            val rate = request.sampleRate

            if (shared) {
                val mix = ac.mixFormat()
                    ?: throw AudioBackendException("Windows would not report a format for ${dev.friendlyName()}.")
                mixFormat = mix
                val info = AudioClient.describe(mix)
                channels = info.channels
                bits = info.bits
                isFloat = info.isFloat
                if (info.rate != rate) {
                    // In shared mode this advice is actually followable, unlike in exclusive mode:
                    // the Windows setting *is* the rate the engine runs at, so changing it changes
                    // what this endpoint can do.
                    throw AudioBackendException(
                        "${dev.friendlyName()} is set to ${SampleRates.format(info.rate)} in Windows, " +
                            "and in shared mode that setting is the rate. Either choose " +
                            "${SampleRates.format(info.rate)} here, or change the device under Sound " +
                            "settings > Device properties > Advanced."
                    )
                }
            }

            // Exclusive mode takes what the hardware natively is, so try the widest word first and
            // fall back rather than assuming 16-bit.
            outer@ for (ch in if (shared) intArrayOf() else intArrayOf(2, 1)) {
                for ((b, f) in listOf(24 to false, 32 to true, 16 to false)) {
                    val candidate = AudioClient.waveFormat(ch, rate, b, f)
                    if (ac.isFormatSupported(WasapiIds.EXCLUSIVE, candidate) == 0) {
                        wfx = candidate; channels = ch; bits = b; isFloat = f
                        break@outer
                    }
                    candidate.close()
                }
            }
            if (wfx == null && !shared) {
                // Say what the device *can* do rather than what we wanted. The version of this
                // message that told the user to "set the device to 44100 Hz in Windows Sound
                // settings" was advice they could not follow: the HDMI output that triggered it is
                // physically incapable of 44.1 kHz, so there was no setting to change.
                val supported = CANDIDATE_RATES.filter { candidate ->
                    SUPPORTED_BITS.any { (bits, isFloat) ->
                        val probe = AudioClient.waveFormat(2, candidate, bits, isFloat)
                        try {
                            ac.isFormatSupported(WasapiIds.EXCLUSIVE, probe) == 0
                        } finally {
                            probe.close()
                        }
                    }
                }
                throw AudioBackendException(
                    "${dev.friendlyName()} does not do ${SampleRates.format(rate)} in exclusive " +
                        "mode; it supports ${SampleRates.describe(supported)}. Pick that rate, or a " +
                        "device pair that shares one."
                )
            }
            negotiatedRate = rate

            val flags = WasapiIds.STREAMFLAGS_EVENTCALLBACK or WasapiIds.STREAMFLAGS_NOPERSIST
            // Tracked exactly, in frames. Recomputing it back out of the 100 ns duration truncates:
            // 264 frames at 44.1 kHz is 59 863 945.578 units, which rounds to 263 frames on the way
            // back and turns a perfectly good buffer into a reported mismatch.
            var requestedFrames = if (isRender) renderTargetFrames else captureTargetFrames
            var duration = requestedFrames * 10_000_000L / rate
            val format: Pointer = mixFormat ?: wfx!!
            // Periodicity must be zero in shared mode: the engine owns the period and a non-zero
            // value is rejected outright.
            val periodicity = if (shared) 0L else duration

            var hr = ac.initialize(shareMode, flags, duration, periodicity, format)
            if (hr == WasapiIds.E_BUFFER_SIZE_NOT_ALIGNED) {
                // The driver tells us the size it actually wants by way of GetBufferSize; a second
                // client has to be created because the first is now in a half-initialised state.
                val aligned = ac.bufferSizeFrames()
                requestedFrames = aligned
                duration = (1e7 * aligned / rate + 0.5).toLong()
                ac.release()
                ac = dev.audioClientOrNull() ?: throw AudioBackendException("Device went away during setup.")
                client = ac
                hr = ac.initialize(shareMode, flags, duration, periodicity, format)
            }
            if (hr != 0) {
                throw AudioBackendException(
                    if (shared) {
                        "${dev.friendlyName()} would not open in shared mode: ${hrName(hr)}. " +
                            "Try a larger buffer."
                    } else {
                        "Exclusive mode was refused by ${dev.friendlyName()}: ${hrName(hr)}. " +
                            if (hr == 0x8889000A.toInt() || hr == 0x8889000E.toInt())
                                "Close whatever else is using it, and check \"Allow applications to " +
                                    "take exclusive control\" is enabled for this device in Windows " +
                                    "Sound settings."
                            else "Try a larger buffer."
                    }
                )
            }

            val frames = ac.bufferSizeFrames()
            // Did the driver honour the period it was asked for?
            //
            // Normally buffer == period in exclusive event-driven mode, and one whole buffer per
            // event is the contract. One endpoint here does not play along: the AORUS HDMI output
            // accepts a 144-frame request, reports a 192-frame buffer, and still fires its event
            // every 144 frames. Writing 192 into that pushes a third more audio than it can take —
            // 5 981 xruns in twenty seconds. Re-initialising at the granted size does not help,
            // because the period never moves.
            //
            // So the fallback is per-event `GetCurrentPadding`, but *only* for a driver that has
            // already shown it rounds. Using padding unconditionally was worse: on a well-behaved
            // wireless endpoint it reported a buffer that was mostly full at wake time and starved
            // the stream to 37 % of realtime. Distrust only the driver that earned it.
            // Shared mode is exempt: rounding up to the engine period is the documented contract
            // there, not a driver misbehaving, and the granted size is simply what we use.
            if (!shared && frames != requestedFrames) {
                throw AudioBackendException(
                    "${dev.friendlyName()} accepted a $requestedFrames-frame buffer and then gave a " +
                        "$frames-frame one while keeping its original period. Audio through it would " +
                        "break up continuously. This is an NVIDIA HDMI-style driver limitation, not a " +
                        "setting: use a different output, or an ASIO driver if your interface has one."
                )
            }
            val latency = ac.streamLatency100ns()
            if (isRender) {
                renderFrames = frames; renderLatency100ns = latency
            } else {
                captureFrames = frames; captureLatency100ns = latency
            }

            val event = Kernel32.INSTANCE.CreateEvent(null, false, false, null)
            ComObject.check(ac.setEventHandle(event), "SetEventHandle")
            val service = ac.service(
                if (isRender) WasapiIds.IID_IAudioRenderClient else WasapiIds.IID_IAudioCaptureClient
            )

            timerRaised = Rt.beginHighResolutionTimer()
            mmcss = Rt.joinProAudio()
            ready.countDown()

            if (isRender) renderLoop(ac, service, event, frames, channels, bits, isFloat)
            else captureLoop(ac, service, event, channels, bits, isFloat)

            // The render loop can stop itself when it detects the device consuming slower than it
            // asks. Surface that the same way any other device fault is surfaced.
            if (isRender) failure?.let { if (opened) events.onDeviceLost(it) }

            runCatching { ac.stop() }
            service.release()
            dev.release()
            enumerator.release()
        } catch (t: Throwable) {
            failure = t.message ?: t.javaClass.simpleName
            while (ready.count > 0) ready.countDown()
            if (opened) events.onDeviceLost(failure!!)
        } finally {
            Rt.leaveProAudio(mmcss)
            if (timerRaised) Rt.endHighResolutionTimer()
            runCatching { client?.release() }
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun captureLoop(ac: AudioClient, cc: ComObject, event: WinNT.HANDLE, channels: Int, bits: Int, isFloat: Boolean) {
        val pdata = PointerByReference()
        val pframes = IntByReference()
        val pflags = IntByReference()
        val devPos = Memory(8)
        val qpc = Memory(8)
        val nextPacket = IntByReference()
        ac.start()
        while (running) {
            if (Kernel32.INSTANCE.WaitForSingleObject(event, 1_000) != 0) continue
            // Shared mode delivers packets, and there can be more than one waiting per wake — the
            // engine's period and ours are not the same thing. Taking only the first leaves the rest
            // to pile up until the buffer overruns. Exclusive mode has exactly one, so the loop runs
            // once and behaves as it did.
            while (true) {
                if (shared) {
                    if (cc.call(5, nextPacket) != 0 || nextPacket.value == 0) break
                }
                if (cc.call(3, pdata, pframes, pflags, devPos, qpc) != 0) { xruns++; break }
                val n = pframes.value
            // AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY. The first packet after Start always carries
            // it — there genuinely is a discontinuity before the stream existed — so it only counts
            // once the stream is running.
                if (pflags.value and 0x1 != 0 && primed) xruns++
                val buf = pdata.value
                var w = ringWrite
                if (buf != null) {
                    val ch = request.inputChannel.coerceIn(0, channels - 1)
                    for (f in 0 until n) ring[w++ and ringMask] = readSample(buf, f * channels + ch, bits, isFloat)
                } else {
                    for (f in 0 until n) ring[w++ and ringMask] = 0f
                }
                ringWrite = w
                cc.call(4, n)
                if (!shared) break
            }
        }
        devPos.close(); qpc.close()
    }

    private fun renderLoop(
        ac: AudioClient,
        rc: ComObject,
        event: WinNT.HANDLE,
        frames: Int,
        channels: Int,
        bits: Int,
        isFloat: Boolean,
    ) {
        val pp = PointerByReference()
        val inputBlock = FloatArray(frames)
        val outputBlock = FloatArray(frames)
        val periodNanos = frames * 1_000_000_000L / negotiatedRate

        // Pre-roll one buffer of silence: an exclusive-mode render client started empty underruns
        // on its very first period.
        if (rc.call(3, frames, pp) == 0) rc.call(4, frames, 0)
        ac.start()

        while (running) {
            if (Kernel32.INSTANCE.WaitForSingleObject(event, 1_000) != 0) continue

            // Exclusive: one whole buffer per event, which is that mode's contract. Endpoints that
            // do not honour it are refused at open rather than worked around here — see
            // WasapiBackend.accepts.
            //
            // Shared: the engine wakes us every *period*, which is smaller than the buffer, so the
            // whole buffer is never free. Writing it anyway is what starved this to 73 % of realtime
            // with a thousand xruns; the free space is what may be written, and asking for it is the
            // documented contract in this mode rather than the workaround it would be in exclusive.
            val want = if (shared) frames - ac.currentPadding() else frames
            if (want <= 0) continue
            if (rc.call(3, want, pp) != 0) { xruns++; continue }
            val buf = pp.value
            val t0 = System.nanoTime()
            // Steady state only; the ring is still filling for the first few periods.
            if (settle < 64) settle++
            else if (firstRenderNanos == 0L) firstRenderNanos = t0
            else framesRendered += want

            // Some drivers wake on a period that is not their buffer size, and nothing at open-time
            // says so: the AORUS HDMI output grants whatever size it is asked for and then fires its
            // event every three quarters of it, so one buffer per event pushes a third more audio
            // than it can take, for ever. Catching it here is the only way — measure what the device
            // actually consumed and stop if we are outrunning it, rather than breaking up silently
            // three hundred times a second.
            // Shared mode is exempt: writing only the free space is self-limiting, so outrunning the
            // device is not a failure mode there.
            if (!shared && firstRenderNanos != 0L && ++overrunCheck >= 256) {
                overrunCheck = 0
                val elapsed = System.nanoTime() - firstRenderNanos
                if (elapsed > 2_000_000_000L) {
                    val ratio = framesRendered * 1_000_000_000.0 / (elapsed.toDouble() * negotiatedRate)
                    if (ratio > 1.05) {
                        failure = String.format(
                            "%s cannot run in exclusive mode: its driver asks for audio %.0f%% " +
                                "faster than it plays it, so its buffer size and its period do not " +
                                "agree. Use a different output device, or ASIO.",
                            outputDevice.name,
                            (ratio - 1.0) * 100.0,
                        )
                        running = false
                    }
                }
            }

            // Drain the ring. The two endpoints are on separate crystals, so its level wanders;
            // one frame added or dropped per period is ~340 ppm of authority against real drift of
            // tens of ppm, and is inaudible where a whole dropped block would not be.
            var r = ringRead
            val available = ringWrite - r
            var skip = 0
            if (available > want * 3) { skip = 1; driftCorrections++ }
            // The ring is empty until the capture side has produced its first period, so the first
            // few render callbacks legitimately have nothing to play. Counting those as xruns would
            // report a dropout on every single start, which trains the user to ignore the counter.
            if (available >= want) primed = true
            if (primed && available < want) xruns++
            r += skip
            for (i in 0 until want) {
                inputBlock[i] = if (r != ringWrite) ring[r++ and ringMask] else 0f
            }
            if (available in want until want + 2) { r--; driftCorrections++ }   // hold one frame back
            ringRead = r

            callback.render(inputBlock, outputBlock, want)

            for (f in 0 until want) {
                val v = outputBlock[f].coerceIn(-1f, 1f)
                for (c in 0 until channels) writeSample(buf, f * channels + c, bits, isFloat, v)
            }
            rc.call(4, want, 0)

            val spent = System.nanoTime() - t0
            if (spent > periodNanos) deadlineMisses++
            val micros = (spent / 1_000L).toInt()
            if (micros > worstCallbackMicros) worstCallbackMicros = micros
        }
    }

    private fun readSample(p: Pointer, index: Int, bits: Int, isFloat: Boolean): Float = when {
        isFloat -> p.getFloat(index * 4L)
        bits == 16 -> p.getShort(index * 2L) / 32768f
        bits == 24 -> {
            val o = index * 3L
            val b0 = p.getByte(o).toInt() and 0xFF
            val b1 = p.getByte(o + 1).toInt() and 0xFF
            val b2 = p.getByte(o + 2).toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
        }
        else -> p.getInt(index * 4L) / 2147483648f
    }

    private fun writeSample(p: Pointer, index: Int, bits: Int, isFloat: Boolean, v: Float) = when {
        isFloat -> p.setFloat(index * 4L, v)
        bits == 16 -> p.setShort(index * 2L, (v * 32767f).toInt().toShort())
        bits == 24 -> {
            val x = (v * 8388607f).toInt()
            val o = index * 3L
            p.setByte(o, x.toByte()); p.setByte(o + 1, (x shr 8).toByte()); p.setByte(o + 2, (x shr 16).toByte())
        }
        else -> p.setInt(index * 4L, (v * 2147483647f).toInt())
    }
}
