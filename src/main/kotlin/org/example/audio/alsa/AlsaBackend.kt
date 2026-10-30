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
package org.example.audio.alsa

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import org.example.audio.AudioBackend
import org.example.audio.AudioBackendException
import org.example.audio.AudioCallback
import org.example.audio.AudioStream
import org.example.audio.Availability
import org.example.audio.BackendDevice
import org.example.audio.BackendId
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.SampleRates
import org.example.audio.StreamEvents
import org.example.audio.StreamFormat
import org.example.audio.StreamRequest
import org.example.audio.alsa.AlsaApi.Companion.EAGAIN
import org.example.audio.alsa.AlsaApi.Companion.EBUSY
import org.example.audio.alsa.AlsaApi.Companion.EPIPE
import org.example.audio.alsa.AlsaApi.Companion.ESTRPIPE
import org.example.audio.linux.LinuxPlatform
import org.example.audio.linux.LinuxRt
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ALSA — the Linux rung that is never missing.
 *
 * JACK is better when it is there, and on a stock desktop it often is not: `pipewire-jack` is an
 * optional package on Arch, Debian and Ubuntu, and a jack2 library with no server running is worse
 * than none. ALSA is underneath every sound server on every distribution, so this backend covers
 * whatever the machine has:
 *
 * - **`default`** goes through the sound server — PipeWire or PulseAudio, through their ALSA
 *   plugins — so it works while every other program keeps its sound, at the server's latency.
 * - **`hw:` devices** go straight to the card, around the server: the lowest latency short of JACK,
 *   but only while nothing else has the card open. PipeWire releases an idle card after a few
 *   seconds, so this is usable more often than "exclusive" suggests; a busy one says so.
 *
 * **Directions are separate devices.** A card's capture and playback are distinct PCMs with
 * distinct capabilities, and [capabilities] has to know which it is answering for, so each is listed
 * on its own — `in:` and `out:` ids — and the pair is [Pairing.ANY_PAIR]. Two different cards run on
 * two crystals and will drift; that surfaces as xruns, the same caveat WASAPI carries.
 *
 * **The thread is ours**, unlike JACK's, so it has to be made realtime explicitly — see [LinuxRt].
 */
class AlsaBackend : AudioBackend {

    override val id = BackendId.ALSA

    override val availability: Availability = run {
        when {
            // First, and short-circuiting: libasound is not to be touched anywhere else.
            !LinuxPlatform.isLinux -> Availability.Unavailable("ALSA is a Linux backend")
            else -> try {
                AlsaLibrary.api
                Availability.Available
            } catch (t: NoClassDefFoundError) {
                // JNA itself, not libasound: name the right missing piece.
                Availability.Unavailable("JNA did not load (${t.message}). The build that produced this jar is incomplete.")
            } catch (t: Throwable) {
                Availability.Unavailable("libasound.so.2 could not be loaded: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val capabilityCache = HashMap<Pair<String, Int?>, DeviceCapabilities>()

    @Volatile
    private var live: AlsaStream? = null

    override fun devices(): List<BackendDevice> {
        if (!availability.isAvailable) return emptyList()
        return runCatching { enumerate() }.getOrDefault(emptyList())
    }

    private fun enumerate(): List<BackendDevice> {
        val found = ArrayList<BackendDevice>()
        val server = soundServer()
        val defaultName = if (server != null) "Default — through $server" else "Default"
        found += BackendDevice(id, "in:default", defaultName, 2, 0)
        found += BackendDevice(id, "out:default", defaultName, 0, 2)

        val cards = cardNames()
        for (pcm in hardwarePcms()) {
            val card = cards[pcm.card] ?: continue
            val name = "${card.second}: ${pcm.name} (direct)"
            val base = "hw:CARD=${card.first},DEV=${pcm.device}"
            if (pcm.capture) {
                found += BackendDevice(id, "in:$base", name, channelsOf(base, AlsaApi.STREAM_CAPTURE), 0)
            }
            if (pcm.playback) {
                found += BackendDevice(id, "out:$base", name, 0, channelsOf(base, AlsaApi.STREAM_PLAYBACK))
            }
        }
        return found
    }

    /** Which server `default` reaches, for the label. A guess from sockets, used only for display. */
    private fun soundServer(): String? {
        val runtime = System.getenv("XDG_RUNTIME_DIR") ?: return null
        return when {
            File(runtime, "pipewire-0").exists() -> "PipeWire"
            File(runtime, "pulse/native").exists() -> "PulseAudio"
            else -> null
        }
    }

    private fun hardwarePcms(): List<AlsaProc.Pcm> =
        File("/proc/asound/pcm").takeIf { it.isFile }?.let { AlsaProc.pcms(it.readLines()) }.orEmpty()

    private fun cardNames(): Map<Int, Pair<String, String>> =
        File("/proc/asound/cards").takeIf { it.isFile }?.let { AlsaProc.cards(it.readLines()) }.orEmpty()

    /** Channel count for display and routing. A busy device cannot be asked; assume stereo. */
    private fun channelsOf(name: String, stream: Int): Int {
        val pcm = openPcm(name, stream) ?: return 2
        val hw = PointerByReference()
        val api = AlsaLibrary.api
        try {
            if (api.snd_pcm_hw_params_malloc(hw) < 0) return 2
            try {
                api.snd_pcm_hw_params_any(pcm, hw.value)
                val max = IntByReference(2)
                api.snd_pcm_hw_params_get_channels_max(hw.value, max)
                return max.value.coerceIn(1, 32)
            } finally {
                api.snd_pcm_hw_params_free(hw.value)
            }
        } finally {
            api.snd_pcm_close(pcm)
        }
    }

    override fun capabilities(device: BackendDevice, sampleRate: Int?): DeviceCapabilities {
        val key = device.id to sampleRate
        synchronized(capabilityCache) { capabilityCache[key]?.let { return it } }
        val result = probe(device, sampleRate)
        // A busy answer is not cached: the card frees itself a few seconds after the server goes
        // idle, and the next look should see that without the user having to know to press Rescan.
        if (result.unusableReason == null) synchronized(capabilityCache) { capabilityCache[key] = result }
        return result
    }

    private fun probe(device: BackendDevice, sampleRate: Int?): DeviceCapabilities {
        val (name, stream) = split(device.id)
        val pcm = openPcmOrError(name, stream)
        val api = AlsaLibrary.api
        val unusable = { why: String ->
            DeviceCapabilities(emptyList(), emptyList(), 0, Pairing.ANY_PAIR, unusableReason = why)
        }
        if (pcm.second != null) return unusable(pcm.second!!)
        val handle = pcm.first!!
        val hw = PointerByReference()
        try {
            if (api.snd_pcm_hw_params_malloc(hw) < 0) return unusable("${device.name}: out of memory")
            val params = hw.value
            try {
                api.snd_pcm_hw_params_any(handle, params)
                if (api.snd_pcm_hw_params_set_access(handle, params, AlsaApi.ACCESS_RW_INTERLEAVED) < 0) {
                    return unusable("${device.name} does not support interleaved access")
                }
                val format = AlsaApi.FORMATS.firstOrNull { api.snd_pcm_hw_params_test_format(handle, params, it) == 0 }
                    ?: return unusable("${device.name} offers no sample format this program can use")
                api.snd_pcm_hw_params_set_format(handle, params, format)
                api.snd_pcm_hw_params_set_rate_resample(handle, params, 0)

                val rates = CANDIDATE_RATES.filter { api.snd_pcm_hw_params_test_rate(handle, params, it, 0) == 0 }
                if (rates.isEmpty()) return unusable("${device.name} accepts none of ${SampleRates.describe(CANDIDATE_RATES)}")
                val preferredRate = if (48_000 in rates) 48_000 else rates.first()
                val rate = sampleRate?.takeIf { it in rates } ?: preferredRate
                api.snd_pcm_hw_params_set_rate(handle, params, rate, 0)

                val sizes = PERIODS.filter { api.snd_pcm_hw_params_test_period_size(handle, params, it.toLong(), 0) == 0 }
                val preferred = sizes.firstOrNull { it >= PREFERRED } ?: sizes.lastOrNull() ?: 0
                return DeviceCapabilities(
                    sampleRates = rates,
                    bufferSizes = sizes,
                    preferredBufferFrames = preferred,
                    pairing = Pairing.ANY_PAIR,
                    preferredSampleRate = preferredRate,
                    unusableReason = if (sizes.isEmpty()) "${device.name} accepts none of the period sizes offered at " +
                        SampleRates.format(rate) else null,
                )
            } finally {
                api.snd_pcm_hw_params_free(params)
            }
        } finally {
            api.snd_pcm_close(handle)
        }
    }

    override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
        if (!availability.isAvailable) throw AudioBackendException(availability.reason ?: "ALSA is not available")
        if (live != null) throw AudioBackendException("An ALSA stream is already open")
        val input = request.input ?: throw AudioBackendException("No input chosen")
        val output = request.output ?: throw AudioBackendException("No output chosen")
        val stream = AlsaStream(
            captureName = split(input.id).first,
            captureLabel = input.name,
            playbackName = split(output.id).first,
            playbackLabel = output.name,
            inputChannel = request.inputChannel,
            outputChannels = request.outputChannels.copyOf(),
            rate = request.sampleRate,
            period = request.bufferFrames,
            callback = callback,
            events = events,
        )
        stream.onClosed = { live = null }
        try {
            stream.configure()
        } catch (t: Throwable) {
            runCatching { stream.close() }
            throw t
        }
        live = stream
        return stream
    }

    override fun rescan() {
        synchronized(capabilityCache) { capabilityCache.clear() }
    }

    override fun shutdown() {
        runCatching { live?.close() }
    }

    private companion object {
        val CANDIDATE_RATES = listOf(44_100, 48_000, 88_200, 96_000)
        val PERIODS = listOf(16, 32, 64, 128, 256, 512, 1024, 2048)
        const val PREFERRED = 256

        fun split(id: String): Pair<String, Int> = when {
            id.startsWith("in:") -> id.removePrefix("in:") to AlsaApi.STREAM_CAPTURE
            else -> id.removePrefix("out:") to AlsaApi.STREAM_PLAYBACK
        }
    }
}

internal fun openPcm(name: String, stream: Int): Pointer? = openPcmOrError(name, stream).first

/** The handle, or the reason it could not be had — phrased for the person looking at the picker. */
internal fun openPcmOrError(name: String, stream: Int): Pair<Pointer?, String?> {
    val ref = PointerByReference()
    val api = AlsaLibrary.api
    val err = api.snd_pcm_open(ref, name, stream, AlsaApi.NONBLOCK)
    if (err >= 0) return ref.value to null
    val direction = if (stream == AlsaApi.STREAM_CAPTURE) "input" else "output"
    return null to if (-err == EBUSY) {
        "$name ($direction) is in use by another program — usually the sound server. Close what is " +
            "playing, wait a few seconds for it to let go and press Rescan, or use Default."
    } else {
        "$name ($direction) could not be opened: ${api.snd_strerror(err)}"
    }
}

/**
 * One capture PCM and one playback PCM, and a thread of ours between them.
 *
 * The loop is read a period, process it, write a period. Playback is primed with [PREFILL] periods
 * of silence before either side starts, which is the whole output latency: after that the loop
 * replaces each period the card consumes with the one it just read, so the queue neither grows nor
 * drains. A single period of priming would leave nothing in hand at the moment of the write, and an
 * xrun every few seconds; two is the conventional answer and the measured one.
 *
 * Both PCMs are opened non-blocking and waited on with `snd_pcm_wait`, which has a timeout. A
 * blocking read on a card that has just been unplugged can stay blocked; this loop cannot.
 */
internal class AlsaStream(
    private val captureName: String,
    private val captureLabel: String,
    private val playbackName: String,
    private val playbackLabel: String,
    private val inputChannel: Int,
    private val outputChannels: IntArray,
    private val rate: Int,
    private val period: Int,
    private val callback: AudioCallback,
    private val events: StreamEvents,
) : AudioStream {

    var onClosed: (() -> Unit)? = null

    private var capture: Pointer? = null
    private var playback: Pointer? = null
    private var linked = false

    private var inFormat = 0
    private var outFormat = 0
    private var inChannels = 0
    private var outChannels = 0

    private lateinit var inMemory: Memory
    private lateinit var outMemory: Memory
    private lateinit var inBytes: ByteBuffer
    private lateinit var outBytes: ByteBuffer
    private var inAddress = 0L
    private var outAddress = 0L
    private var inFrameBytes = 0
    private var outFrameBytes = 0

    /** Which interleaved slots of the output frame carry the signal. Precomputed; no search per sample. */
    private lateinit var outMask: BooleanArray

    private val inputBlock = FloatArray(period)
    private val outputBlock = FloatArray(period)
    private val periodNanos = period * 1_000_000_000L / rate
    private val waitMillis = maxOf(20, (period * 4000L / rate).toInt())

    @Volatile
    override var xruns: Int = 0
        private set

    @Volatile
    override var deadlineMisses: Int = 0
        private set

    @Volatile
    override var worstCallbackMicros: Int = 0
        private set

    @Volatile
    private var framesProcessed = 0L

    @Volatile
    private var firstCallbackNanos = 0L
    private var settle = 0

    @Volatile
    private var running = false

    /** Set by the loop on a fatal device error; read by the monitor, which reports it. */
    @Volatile
    private var lostCode = 0

    @Volatile
    override var schedulingNote: String? = null
        private set

    private var thread: Thread? = null
    private var monitor: Thread? = null

    override lateinit var format: StreamFormat
        private set

    override val realtimeRatio: Float
        get() {
            val t0 = firstCallbackNanos
            if (t0 == 0L) return 1f
            val elapsed = System.nanoTime() - t0
            if (elapsed <= 0) return 1f
            return (framesProcessed * 1_000_000_000.0 / (elapsed.toDouble() * rate)).toFloat()
        }

    // ---- setup, on the opening thread --------------------------------------

    fun configure() {
        val (c, cErr) = openPcmOrError(captureName, AlsaApi.STREAM_CAPTURE)
        capture = c ?: throw AudioBackendException(cErr ?: "Could not open $captureLabel")
        val (p, pErr) = openPcmOrError(playbackName, AlsaApi.STREAM_PLAYBACK)
        playback = p ?: throw AudioBackendException(pErr ?: "Could not open $playbackLabel")

        val cap = setUp(c, captureLabel, needChannels = inputChannel + 1, bufferPeriods = 8)
        inFormat = cap.first; inChannels = cap.second
        val play = setUp(p, playbackLabel, needChannels = (outputChannels.maxOrNull() ?: 0) + 1, bufferPeriods = 4)
        outFormat = play.first; outChannels = play.second

        inFrameBytes = inChannels * AlsaApi.bytesPerSample(inFormat)
        outFrameBytes = outChannels * AlsaApi.bytesPerSample(outFormat)
        inMemory = Memory(period.toLong() * inFrameBytes).apply { clear() }
        outMemory = Memory(period.toLong() * outFrameBytes).apply { clear() }
        inBytes = inMemory.getByteBuffer(0, inMemory.size()).order(ByteOrder.LITTLE_ENDIAN)
        outBytes = outMemory.getByteBuffer(0, outMemory.size()).order(ByteOrder.LITTLE_ENDIAN)
        inAddress = Pointer.nativeValue(inMemory)
        outAddress = Pointer.nativeValue(outMemory)
        outMask = BooleanArray(outChannels) { ch -> outputChannels.contains(ch) }

        // One card, one clock: link them so they start on the same sample. Refused across cards,
        // which is fine — then they are started back to back and drift is the price.
        linked = AlsaLibrary.api.snd_pcm_link(c, p) == 0

        format = StreamFormat(
            sampleRate = rate,
            bufferFrames = period,
            inputLatencyFrames = period,
            outputLatencyFrames = period * PREFILL,
            adapterLatencyFrames = 0,
        )
    }

    /** hw and sw parameters for one direction. Returns (format, channels). */
    private fun setUp(pcm: Pointer, label: String, needChannels: Int, bufferPeriods: Int): Pair<Int, Int> {
        val api = AlsaLibrary.api
        val hw = PointerByReference()
        check(api.snd_pcm_hw_params_malloc(hw) >= 0) { "out of memory" }
        val params = hw.value
        try {
            fun require(result: Int, what: String) {
                if (result < 0) throw AudioBackendException("$label $what: ${api.snd_strerror(result)}")
            }
            require(api.snd_pcm_hw_params_any(pcm, params), "has no configuration")
            require(api.snd_pcm_hw_params_set_access(pcm, params, AlsaApi.ACCESS_RW_INTERLEAVED), "refused interleaved access")
            val format = AlsaApi.FORMATS.firstOrNull { api.snd_pcm_hw_params_test_format(pcm, params, it) == 0 }
                ?: throw AudioBackendException("$label offers no sample format this program can use")
            require(api.snd_pcm_hw_params_set_format(pcm, params, format), "refused ${AlsaApi.formatName(format)}")

            val min = IntByReference(1)
            val max = IntByReference(2)
            api.snd_pcm_hw_params_get_channels_min(params, min)
            api.snd_pcm_hw_params_get_channels_max(params, max)
            if (needChannels > max.value) {
                throw AudioBackendException("$label has ${max.value} channel(s); channel $needChannels was asked for")
            }
            val channels = maxOf(needChannels, min.value)
            require(api.snd_pcm_hw_params_set_channels(pcm, params, channels), "refused $channels channel(s)")

            api.snd_pcm_hw_params_set_rate_resample(pcm, params, 0)
            require(api.snd_pcm_hw_params_set_rate(pcm, params, rate, 0), "will not run at ${SampleRates.format(rate)}")
            require(api.snd_pcm_hw_params_set_period_size(pcm, params, period.toLong(), 0), "refused a $period-frame period")
            val size = LongByReference(period.toLong() * bufferPeriods)
            require(api.snd_pcm_hw_params_set_buffer_size_near(pcm, params, size), "refused a buffer of $bufferPeriods periods")
            require(api.snd_pcm_hw_params(pcm, params), "refused the configuration")

            val bufferFrames = LongByReference(0)
            api.snd_pcm_hw_params_get_buffer_size(params, bufferFrames)
            if (bufferFrames.value < period * (PREFILL + 1L)) {
                throw AudioBackendException("$label granted a ${bufferFrames.value}-frame buffer, too small for $period-frame periods")
            }

            val sw = PointerByReference()
            check(api.snd_pcm_sw_params_malloc(sw) >= 0) { "out of memory" }
            try {
                api.snd_pcm_sw_params_current(pcm, sw.value)
                // Never start on our own: the loop starts both sides explicitly, after priming.
                api.snd_pcm_sw_params_set_start_threshold(pcm, sw.value, bufferFrames.value * 2)
                api.snd_pcm_sw_params_set_avail_min(pcm, sw.value, period.toLong())
                require(api.snd_pcm_sw_params(pcm, sw.value), "refused its software parameters")
            } finally {
                api.snd_pcm_sw_params_free(sw.value)
            }
            return format to channels
        } finally {
            api.snd_pcm_hw_params_free(params)
        }
    }

    // ---- lifecycle -----------------------------------------------------------

    override fun start() {
        running = true
        thread = Thread(::loop, "omega-alsa-render").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        monitor = Thread(::monitorLoop, "omega-alsa-monitor").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
        monitor?.interrupt()
        monitor = null
    }

    override fun close() {
        stop()
        capture?.let { c ->
            if (linked) runCatching { AlsaLibrary.api.snd_pcm_unlink(c) }
            runCatching { AlsaRt.snd_pcm_drop(c) }
            runCatching { AlsaLibrary.api.snd_pcm_close(c) }
        }
        playback?.let { p ->
            runCatching { AlsaRt.snd_pcm_drop(p) }
            runCatching { AlsaLibrary.api.snd_pcm_close(p) }
        }
        capture = null
        playback = null
        onClosed?.invoke()
        onClosed = null
    }

    private fun monitorLoop() {
        while (running || lostCode != 0) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
            val code = lostCode
            if (code != 0) {
                lostCode = 0
                events.onDeviceLost("$captureLabel / $playbackLabel stopped: ${AlsaLibrary.api.snd_strerror(code)}")
                return
            }
        }
    }

    // ---- the realtime path -----------------------------------------------------

    private fun loop() {
        // Before the loop, on this thread, and allowed to allocate: the loop proper may not.
        val grant = LinuxRt.promoteCurrentThread()
        schedulingNote = if (grant.realtime) "realtime: ${grant.detail}" else "realtime ${grant.detail}"

        val c = capture!!
        val p = playback!!
        if (restart(c, p) < 0) {
            lostCode = -5
            return
        }
        while (running) {
            // Gather one period of input. Never read until everything still wanted is there: a
            // partial read leaves a remainder smaller than avail_min, and the wait that follows
            // then sleeps for a *whole* further period — a loop that falls a little further behind
            // the card on every cycle until playback runs dry. Found on an HDA card at 256 frames,
            // where it was an xrun every third period.
            var got = 0
            var failed = 0
            while (got < period && running) {
                val avail = AlsaRt.snd_pcm_avail(c)
                if (avail < 0) { failed = avail.toInt(); break }
                if (avail < period - got) {
                    val w = AlsaRt.snd_pcm_wait(c, waitMillis)
                    if (w < 0) { failed = w; break }
                    continue
                }
                val r = AlsaRt.snd_pcm_readi(c, inAddress + got.toLong() * inFrameBytes, (period - got).toLong())
                if (r >= 0) {
                    got += r.toInt()
                } else if (r != -EAGAIN.toLong()) {
                    failed = r.toInt(); break
                }
            }
            if (!running) break
            if (failed != 0) {
                if (!recover(c, p, failed)) return
                continue
            }

            val t0 = System.nanoTime()
            try {
                decode()
                callback.render(inputBlock, outputBlock, period)
                encode()
            } catch (_: Throwable) {
                java.util.Arrays.fill(outputBlock, 0f)
                encode()
                xruns++
            }
            val spent = System.nanoTime() - t0

            var put = 0
            while (put < period && running) {
                val w = AlsaRt.snd_pcm_writei(p, outAddress + put.toLong() * outFrameBytes, (period - put).toLong())
                if (w >= 0) {
                    put += w.toInt()
                } else if (w == -EAGAIN.toLong()) {
                    val r = AlsaRt.snd_pcm_wait(p, waitMillis)
                    if (r < 0) { failed = r; break }
                } else {
                    failed = w.toInt(); break
                }
            }
            if (failed != 0) {
                if (!recover(c, p, failed)) return
                continue
            }

            if (settle < SETTLE_PERIODS) settle++
            else if (firstCallbackNanos == 0L) firstCallbackNanos = System.nanoTime()
            else framesProcessed += period
            if (spent > periodNanos) deadlineMisses++
            val micros = (spent / 1000L).toInt()
            if (micros > worstCallbackMicros) worstCallbackMicros = micros
        }
    }

    /** An xrun is counted and recovered from; anything else ends the stream and is reported. */
    private fun recover(c: Pointer, p: Pointer, error: Int): Boolean {
        if (error == -EPIPE || error == -ESTRPIPE) {
            xruns++
            if (restart(c, p) >= 0) return true
        }
        lostCode = error
        running = false
        return false
    }

    /** Drop, prepare, prime with silence, start. Used for the first start and after every xrun. */
    private fun restart(c: Pointer, p: Pointer): Int {
        AlsaRt.snd_pcm_drop(c)
        if (!linked) AlsaRt.snd_pcm_drop(p)
        var e = AlsaRt.snd_pcm_prepare(c)
        if (e < 0) return e
        if (!linked) {
            e = AlsaRt.snd_pcm_prepare(p)
            if (e < 0) return e
        }
        for (i in 0 until outMemory.size().toInt()) outBytes.put(i, 0)
        repeat(PREFILL) {
            var put = 0
            while (put < period) {
                val w = AlsaRt.snd_pcm_writei(p, outAddress + put.toLong() * outFrameBytes, (period - put).toLong())
                if (w < 0) return w.toInt()
                put += w.toInt()
            }
        }
        // Linked: starting one starts both. Otherwise playback first, so it is already draining
        // the primed periods when the first input period completes.
        e = AlsaRt.snd_pcm_start(if (linked) c else p)
        if (e < 0) return e
        if (!linked) {
            e = AlsaRt.snd_pcm_start(c)
            if (e < 0) return e
        }
        return 0
    }

    private fun decode() =
        AlsaCodec.decode(inBytes, inFormat, inFrameBytes, inputChannel * AlsaApi.bytesPerSample(inFormat), inputBlock, period)

    private fun encode() = AlsaCodec.encode(outputBlock, period, outBytes, outFormat, outChannels, outMask)

    private companion object {
        const val PREFILL = 2
        const val SETTLE_PERIODS = 64
    }
}

/**
 * What the kernel says is there, from `/proc/asound`. Plain text, so no library call and nothing to
 * open — and nothing that can be refused because the sound server holds the card.
 */
internal object AlsaProc {

    data class Pcm(val card: Int, val device: Int, val name: String, val playback: Boolean, val capture: Boolean)

    private val PCM_LINE = Regex("""^(\d+)-(\d+): ([^:]*)""")
    private val CARD_LINE = Regex("""^\s*(\d+) \[(\S+)\s*]: .* - (.*)$""")

    /** `/proc/asound/pcm`: `00-00: ALC3246 Analog : ALC3246 Analog : playback 1 : capture 1`. */
    fun pcms(lines: List<String>): List<Pcm> = lines.mapNotNull { line ->
        val m = PCM_LINE.find(line) ?: return@mapNotNull null
        val rest = line.substring(m.range.last + 1)
        Pcm(
            card = m.groupValues[1].toInt(),
            device = m.groupValues[2].toInt(),
            name = m.groupValues[3].trim(),
            playback = "playback" in rest,
            capture = "capture" in rest,
        )
    }

    /**
     * `/proc/asound/cards`: index to (id, display name). The id — `PCH`, `Sonicake` — is what goes
     * into a device id, because it survives a reboot and a replug where the index does not.
     */
    fun cards(lines: List<String>): Map<Int, Pair<String, String>> = lines.mapNotNull { line ->
        val m = CARD_LINE.find(line) ?: return@mapNotNull null
        m.groupValues[1].toInt() to (m.groupValues[2] to m.groupValues[3].trim())
    }.toMap()
}

/**
 * Interleaved device bytes to and from the mono float block, little-endian throughout.
 *
 * Called from the stream loop, so: no allocation, and the format `when` sits outside the per-sample
 * loop on the way in. The way out branches per sample on a value that never changes, which the JIT
 * hoists; it is written for being read, not for the last nanosecond.
 */
internal object AlsaCodec {

    /** One channel of [n] frames out of [bytes], [stride] bytes per frame, starting [offset] in. */
    fun decode(bytes: ByteBuffer, format: Int, stride: Int, offset: Int, out: FloatArray, n: Int) {
        val b = bytes
        when (format) {
            AlsaApi.FORMAT_FLOAT_LE -> for (i in 0 until n) out[i] = b.getFloat(i * stride + offset)
            AlsaApi.FORMAT_S32_LE -> for (i in 0 until n) out[i] = b.getInt(i * stride + offset) * (1f / 2147483648f)
            AlsaApi.FORMAT_S16_LE -> for (i in 0 until n) out[i] = b.getShort(i * stride + offset) * (1f / 32768f)
            else -> for (i in 0 until n) {
                val o = i * stride + offset
                val v = (b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or (b.get(o + 2).toInt() shl 16)
                out[i] = v * (1f / 8388608f)
            }
        }
    }

    /**
     * [n] mono frames into every channel [mask] marks, silence into the rest, clamped to [-1, 1].
     * A device with more channels than the signal goes to still gets every channel written — stale
     * bytes left in an unused slot would play.
     */
    fun encode(src: FloatArray, n: Int, bytes: ByteBuffer, format: Int, channels: Int, mask: BooleanArray) {
        val b = bytes
        val bps = AlsaApi.bytesPerSample(format)
        var o = 0
        for (i in 0 until n) {
            val v = src[i]
            val s = if (v > 1f) 1f else if (v < -1f) -1f else v
            for (ch in 0 until channels) {
                val x = if (mask[ch]) s else 0f
                when (format) {
                    AlsaApi.FORMAT_FLOAT_LE -> b.putFloat(o, x)
                    AlsaApi.FORMAT_S32_LE -> b.putInt(o, (x * 2147483647f).toInt())
                    AlsaApi.FORMAT_S16_LE -> b.putShort(o, (x * 32767f).toInt().toShort())
                    else -> {
                        val q = (x * 8388607f).toInt()
                        b.put(o, q.toByte()); b.put(o + 1, (q shr 8).toByte()); b.put(o + 2, (q shr 16).toByte())
                    }
                }
                o += bps
            }
        }
    }
}
