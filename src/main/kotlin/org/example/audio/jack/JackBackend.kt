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
package org.example.audio.jack

import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import org.example.audio.AudioBackend
import org.example.audio.AudioBackendException
import org.example.audio.AudioCallback
import org.example.audio.AudioStream
import org.example.audio.Availability
import org.example.audio.BackendDevice
import org.example.audio.BackendId
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.StreamEvents
import org.example.audio.StreamFormat
import org.example.audio.StreamRequest
import org.example.audio.linux.LinuxPlatform
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * JACK, and through it PipeWire — the Linux counterpart of ASIO.
 *
 * The shape is the one `AudioBackend` was drawn for: one clock, both directions, a callback on a
 * thread the server owns and has already made realtime. Nothing to adapt, no ring, no cushion, so
 * [StreamFormat.adapterLatencyFrames] is zero.
 *
 * **What a "device" is here.** JACK has one server and many ports. Each client that owns physical
 * ports is a device: `system` under jackd, one per sound card under PipeWire (`Built-in Audio`,
 * the USB interface by its product name). Input and output are picked separately because under
 * PipeWire they are routinely different cards — and unlike WASAPI that costs nothing, since the
 * server already runs them all on its one graph clock and resamples where it has to. Hence
 * [Pairing.ANY_PAIR] without the drift caveat.
 *
 * **The server owns the format.** The rate is whatever JACK runs at, so it is reported as
 * [DeviceCapabilities.fixedSampleRate] and the picker shows it read-only. The buffer is the
 * server's too, but a client may ask: `jack_set_buffer_size` resizes jackd for everyone, and under
 * PipeWire forces the graph quantum while this client is connected. The previous size is put back on
 * close — another program's settings are not ours to leave changed.
 *
 * **Connections are explicit.** The client connects its own ports to exactly the hardware ports the
 * user chose and to nothing else. A client that wires itself into whatever it finds is worse than
 * one that connects to nothing.
 */
class JackBackend : AudioBackend {

    override val id = BackendId.JACK

    private var library: JackLibrary.Loaded? = null

    override val availability: Availability = run {
        when {
            // First, and short-circuiting: nothing below may touch libjack on another OS.
            !LinuxPlatform.isLinux -> Availability.Unavailable("JACK is a Linux backend here")
            else -> connectAny()
        }
    }

    /** Tries every candidate library until one reaches a running server. */
    private fun connectAny(): Availability {
        val failures = ArrayList<String>()
        for (candidate in JackLibrary.candidates()) {
            try {
                val loaded = JackLibrary.load(candidate)
                runCatching {
                    loaded.api.jack_set_error_function(JackLibrary.silence)
                    loaded.api.jack_set_info_function(JackLibrary.silence)
                }
                val client = openClient(loaded.api, "omega-probe")
                if (client != null) {
                    loaded.api.jack_client_close(client)
                    JackRt.register(loaded.native)
                    library = loaded
                    return Availability.Available
                }
                failures += "$candidate: no server"
            } catch (t: Throwable) {
                failures += "$candidate: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        val noLibrary = failures.all { "no server" !in it }
        return Availability.Unavailable(
            if (noLibrary) {
                "libjack is not installed. Install pipewire-jack (Fedora: " +
                    "pipewire-jack-audio-connection-kit) for the lowest latency through PipeWire"
            } else {
                "no JACK server is running. Install pipewire-jack so PipeWire answers JACK clients, " +
                    "or start jackd"
            }
        )
    }

    private fun openClient(api: JackApi, name: String): Pointer? {
        val status = IntByReference(0)
        return api.jack_client_open(name, JackApi.JackNoStartServer, status)
    }

    /** Port lists from the last [devices] call, by device id. */
    private val inputPorts = LinkedHashMap<String, List<String>>()
    private val outputPorts = LinkedHashMap<String, List<String>>()

    @Volatile
    private var serverRate = 0

    @Volatile
    private var serverBuffer = 0

    @Volatile
    private var live: JackStream? = null

    override fun devices(): List<BackendDevice> {
        val lib = library ?: return emptyList()
        // Enumerating opens a short-lived client. Harmless under a running stream, but the answer
        // cannot have changed in a way the stream could use, so keep it stable.
        if (live != null) return snapshot()
        return runCatching { enumerate(lib.api) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun enumerate(api: JackApi): List<BackendDevice> {
        val client = openClient(api, "omega-probe") ?: return emptyList()
        try {
            serverRate = api.jack_get_sample_rate(client)
            serverBuffer = api.jack_get_buffer_size(client)
            // A hardware port that *outputs* audio is a capture port; one that takes it in plays it.
            val captures = ports(api, client, JackApi.JackPortIsOutput or JackApi.JackPortIsPhysical)
            val playbacks = ports(api, client, JackApi.JackPortIsInput or JackApi.JackPortIsPhysical)
            inputPorts.clear()
            outputPorts.clear()
            // PipeWire exposes a sink's monitor as physical capture ports. Recording what is being
            // played is not a guitar input.
            captures.filterNot { shortName(it).startsWith("monitor_") }
                .groupBy(::owner).forEach { (k, v) -> inputPorts[k] = v }
            playbacks.groupBy(::owner).forEach { (k, v) -> outputPorts[k] = v }
        } finally {
            api.jack_client_close(client)
        }
        return snapshot()
    }

    private fun snapshot(): List<BackendDevice> {
        val owners = LinkedHashSet<String>().apply { addAll(inputPorts.keys); addAll(outputPorts.keys) }
        return owners.map { owner ->
            BackendDevice(
                backend = id,
                id = owner,
                name = owner,
                inputChannels = inputPorts[owner]?.size ?: 0,
                outputChannels = outputPorts[owner]?.size ?: 0,
            )
        }
    }

    private fun ports(api: JackApi, client: Pointer, flags: Long): List<String> {
        val array = api.jack_get_ports(client, null, JackApi.AUDIO_TYPE, flags) ?: return emptyList()
        return try {
            array.getStringArray(0).toList()
        } finally {
            api.jack_free(array)
        }
    }

    override fun capabilities(device: BackendDevice, sampleRate: Int?): DeviceCapabilities {
        if (serverRate == 0) devices()
        val current = serverBuffer.takeIf { it > 0 } ?: 256
        // Powers of two: jackd accepts nothing else, and PipeWire's quantum limits sit inside this
        // range by default. A size the server will not grant comes back as the size it chose, and
        // the UI mirrors that — the same path an ASIO control-panel change takes.
        val sizes = (SIZES + current).distinct().sorted()
        return DeviceCapabilities(
            sampleRates = listOf(serverRate),
            bufferSizes = sizes,
            // The server's own size when it is already guitar-sized. PipeWire's default quantum is
            // 1024 — 21 ms each way — which is a desktop default, not a choice anyone made for this.
            preferredBufferFrames = if (current <= PREFERRED) current else PREFERRED,
            pairing = Pairing.ANY_PAIR,
            preferredSampleRate = serverRate,
            fixedSampleRate = serverRate,
        )
    }

    override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
        val lib = library ?: throw AudioBackendException(availability.reason ?: "JACK is not available")
        if (live != null) throw AudioBackendException("A JACK stream is already open")
        if (inputPorts.isEmpty() && outputPorts.isEmpty()) devices()
        val input = request.input?.let { inputPorts[it.id] }
            ?: throw AudioBackendException("${request.input?.name ?: "The input"} is no longer on the JACK server")
        val output = request.output?.let { outputPorts[it.id] }
            ?: throw AudioBackendException("${request.output?.name ?: "The output"} is no longer on the JACK server")
        val source = input.getOrNull(request.inputChannel)
            ?: throw AudioBackendException("${request.input.name} has no input ${request.inputChannel + 1}")
        val sinks = request.outputChannels.map { ch -> output.getOrNull(ch) ?: output.last() }.distinct()

        val stream = JackStream(lib.api, callback, events, source, sinks, request.bufferFrames)
        stream.onClosed = { live = null }
        live = stream
        try {
            stream.connect()
        } catch (t: Throwable) {
            live = null
            runCatching { stream.close() }
            throw t
        }
        return stream
    }

    override fun rescan() {
        if (live == null) {
            inputPorts.clear()
            outputPorts.clear()
        }
    }

    override fun shutdown() {
        runCatching { live?.close() }
    }

    private fun owner(port: String) = port.substringBefore(':')
    private fun shortName(port: String) = port.substringAfter(':')

    private companion object {
        val SIZES = listOf(32, 64, 128, 256, 512, 1024, 2048)
        const val PREFERRED = 256
    }
}

/**
 * One JACK client: an input port, one or two output ports, and the process callback.
 *
 * The realtime path is [process]. Port buffers are found through the direct-mapped
 * `jack_port_get_buffer`, and read through `FloatBuffer` views cached by address — a server may
 * hand out a small rotating set of buffers, so the cache holds a few, and only a never-seen address
 * costs a view. JACK's float format is already ours, so there is no codec at all.
 */
internal class JackStream(
    private val api: JackApi,
    private val callback: AudioCallback,
    private val events: StreamEvents,
    private val sourcePort: String,
    private val sinkPorts: List<String>,
    private val requestedFrames: Int,
) : AudioStream {

    var onClosed: (() -> Unit)? = null

    private var client: Pointer? = null
    private var inPort: Pointer? = null
    private var outPorts: Array<Pointer> = emptyArray()
    private var originalBuffer = 0

    private val inputBlock = FloatArray(MAX_FRAMES)
    private val outputBlock = FloatArray(MAX_FRAMES)
    private val inViews = ViewCache()
    private lateinit var outViews: Array<ViewCache>

    /** The block size `prepare` was given. JACK may call with more if the server is resized. */
    private var blockFrames = 0
    private var rate = 0
    private var periodNanos = 0L

    // Strong references: the server holds the trampoline addresses and the GC must not take them.
    private var cbProcess: JackApi.ProcessCallback? = null
    private var cbBuffer: JackApi.BufferSizeCallback? = null
    private var cbXrun: JackApi.XrunCallback? = null
    private var cbShutdown: JackApi.ShutdownCallback? = null

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

    @Volatile
    private var bufferChanged = 0

    @Volatile
    private var serverGone = false

    private var monitor: Thread? = null

    override lateinit var format: StreamFormat
        private set

    override val realtimeRatio: Float
        get() {
            val t0 = firstCallbackNanos
            if (t0 == 0L || rate == 0) return 1f
            val elapsed = System.nanoTime() - t0
            if (elapsed <= 0) return 1f
            return (framesProcessed * 1_000_000_000.0 / (elapsed.toDouble() * rate)).toFloat()
        }

    /** Open, register, size; everything up to but not including activation. */
    fun connect() {
        val c = api.jack_client_open("Omega", JackApi.JackNoStartServer, IntByReference(0))
            ?: throw AudioBackendException("The JACK server went away before the stream could open")
        client = c

        originalBuffer = api.jack_get_buffer_size(c)
        if (originalBuffer != requestedFrames) {
            // Refusal is not an error: the server's own size stands and is what gets reported.
            api.jack_set_buffer_size(c, requestedFrames)
        }
        blockFrames = api.jack_get_buffer_size(c)
        rate = api.jack_get_sample_rate(c)
        if (blockFrames > MAX_FRAMES) {
            throw AudioBackendException("The JACK server runs at $blockFrames frames, above the $MAX_FRAMES this supports")
        }
        periodNanos = blockFrames * 1_000_000_000L / rate

        inPort = api.jack_port_register(c, "in", JackApi.AUDIO_TYPE, JackApi.JackPortIsInput, 0)
            ?: throw AudioBackendException("JACK refused to register an input port")
        outPorts = Array(sinkPorts.size.coerceAtLeast(1)) { i ->
            api.jack_port_register(c, "out_${i + 1}", JackApi.AUDIO_TYPE, JackApi.JackPortIsOutput, 0)
                ?: throw AudioBackendException("JACK refused to register an output port")
        }
        outViews = Array(outPorts.size) { ViewCache() }

        cbProcess = JackApi.ProcessCallback { frames, _ -> process(frames) }
        cbBuffer = JackApi.BufferSizeCallback { frames, _ -> bufferChanged = frames; 0 }
        cbXrun = JackApi.XrunCallback { xruns++; 0 }
        cbShutdown = JackApi.ShutdownCallback { serverGone = true; running = false }

        // Attached once, left attached: per-callback attach is an allocating, safepoint-interacting
        // operation, and against a 1.45 ms period it is an instant failure. The same rule as ASIO.
        val initializer = CallbackThreadInitializer(true, false, "omega-jack-render")
        Native.setCallbackThreadInitializer(cbProcess, initializer)
        Native.setCallbackThreadInitializer(cbBuffer, initializer)
        Native.setCallbackThreadInitializer(cbXrun, CallbackThreadInitializer(true, false, "omega-jack-notify"))
        Native.setCallbackThreadInitializer(cbShutdown, CallbackThreadInitializer(true, false, "omega-jack-notify"))

        check(api.jack_set_process_callback(c, cbProcess!!, null) == 0) { "jack_set_process_callback failed" }
        api.jack_set_buffer_size_callback(c, cbBuffer!!, null)
        api.jack_set_xrun_callback(c, cbXrun!!, null)
        api.jack_on_shutdown(c, cbShutdown!!, null)

        format = StreamFormat(
            sampleRate = rate,
            bufferFrames = blockFrames,
            inputLatencyFrames = blockFrames,
            outputLatencyFrames = blockFrames,
            adapterLatencyFrames = 0,
        )
    }

    override fun start() {
        val c = client ?: throw AudioBackendException("The JACK client is closed")
        running = true
        if (api.jack_activate(c) != 0) {
            running = false
            throw AudioBackendException("The JACK server would not activate the client")
        }
        // Ports can only be connected once the client is active.
        val ownIn = api.jack_port_name(inPort!!)
        if (api.jack_connect(c, sourcePort, ownIn) != 0) {
            throw AudioBackendException("Could not connect $sourcePort to Omega's input")
        }
        for (i in outPorts.indices) {
            val ownOut = api.jack_port_name(outPorts[i])
            if (api.jack_connect(c, ownOut, sinkPorts.getOrElse(i) { sinkPorts.last() }) != 0) {
                throw AudioBackendException("Could not connect Omega's output to ${sinkPorts[i]}")
            }
        }
        format = format.copy(
            inputLatencyFrames = latency(sourcePort, JackApi.JackCaptureLatency),
            outputLatencyFrames = latency(sinkPorts.first(), JackApi.JackPlaybackLatency),
        )
        schedulingNote = if (api.jack_is_realtime(c) != 0) {
            "realtime: the JACK server's own thread"
        } else {
            "NOT realtime: the JACK server is running without realtime scheduling"
        }
        monitor = Thread(::monitorLoop, "omega-jack-monitor").apply { isDaemon = true; start() }
    }

    /** The hardware port's own latency claim, which includes the server's periods. */
    private fun latency(portName: String, mode: Int): Int {
        val c = client ?: return blockFrames
        val port = api.jack_port_by_name(c, portName) ?: return blockFrames
        val range = IntArray(2)
        runCatching { api.jack_port_get_latency_range(port, mode, range) }
        return range[1].takeIf { it > 0 } ?: blockFrames
    }

    /** Whether the server's callback thread is realtime. Asked of the server, not guessed. */
    @Volatile
    override var schedulingNote: String? = null
        private set

    override fun stop() {
        if (!running && client == null) return
        running = false
        client?.let { runCatching { api.jack_deactivate(it) } }
        monitor?.interrupt()
        monitor = null
    }

    override fun close() {
        stop()
        client?.let { c ->
            if (!serverGone) {
                if (originalBuffer > 0 && api.jack_get_buffer_size(c) != originalBuffer) {
                    runCatching { api.jack_set_buffer_size(c, originalBuffer) }
                }
                runCatching { api.jack_client_close(c) }
            }
        }
        client = null
        cbProcess = null; cbBuffer = null; cbXrun = null; cbShutdown = null
        onClosed?.invoke()
        onClosed = null
    }

    /** Allowed to allocate and log: turns the realtime side's flags into [StreamEvents] calls. */
    private fun monitorLoop() {
        while (!serverGone) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
            val frames = bufferChanged
            if (frames != 0) {
                bufferChanged = 0
                if (frames != format.bufferFrames) {
                    format = format.copy(bufferFrames = frames)
                    events.onFormatChanged(format)
                }
            }
        }
        events.onDeviceLost("The JACK server shut down")
    }

    // ---- the realtime path -------------------------------------------------

    private fun process(frames: Int): Int {
        val t0 = System.nanoTime()
        try {
            val inAddress = JackRt.jack_port_get_buffer(inPort!!, frames)
            val inView = inViews.view(inAddress)
            val outs = outPorts
            for (c in outs.indices) outViews[c].current = outViews[c].view(JackRt.jack_port_get_buffer(outs[c], frames))

            if (!running) {
                for (c in outs.indices) silence(outViews[c].current!!, frames)
                return 0
            }

            // A server resized above the prepared block gets processed in prepared-sized pieces, so
            // no effect is ever handed more frames than it sized itself for.
            val step = if (blockFrames > 0) blockFrames else frames
            var offset = 0
            while (offset < frames) {
                val n = minOf(step, frames - offset)
                inView.get(offset, inputBlock, 0, n)
                callback.render(inputBlock, outputBlock, n)
                for (c in outs.indices) outViews[c].current!!.put(offset, outputBlock, 0, n)
                offset += n
            }
        } catch (_: Throwable) {
            // Nothing may escape into native code. Silence, count, carry on; the monitor decides.
            val outs = outPorts
            for (c in outs.indices) outViews[c].current?.let { silence(it, frames) }
            xruns++
        }
        if (settle < SETTLE_CALLBACKS) settle++
        else if (firstCallbackNanos == 0L) firstCallbackNanos = System.nanoTime()
        else framesProcessed += frames

        val spent = System.nanoTime() - t0
        if (spent > periodNanos) deadlineMisses++
        val micros = (spent / 1000L).toInt()
        if (micros > worstCallbackMicros) worstCallbackMicros = micros
        return 0
    }

    private fun silence(view: FloatBuffer, frames: Int) {
        for (i in 0 until frames) view.put(i, 0f)
    }

    /**
     * `FloatBuffer` views onto port buffers, by address. Allocates only on an address it has not
     * seen — once per buffer for the life of the stream on every server measured.
     */
    private class ViewCache {
        private val addresses = LongArray(SLOTS)
        private val views = arrayOfNulls<FloatBuffer>(SLOTS)
        private var next = 0

        @JvmField
        var current: FloatBuffer? = null

        fun view(address: Long): FloatBuffer {
            for (i in 0 until SLOTS) if (addresses[i] == address) return views[i]!!
            val v = Pointer(address).getByteBuffer(0, MAX_FRAMES * 4L)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            addresses[next] = address
            views[next] = v
            next = (next + 1) % SLOTS
            return v
        }
    }

    private companion object {
        const val MAX_FRAMES = 8192
        const val SLOTS = 4
        const val SETTLE_CALLBACKS = 64
    }
}
