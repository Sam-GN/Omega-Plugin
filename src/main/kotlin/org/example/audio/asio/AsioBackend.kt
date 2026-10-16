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
package org.example.audio.asio

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.CallbackThreadInitializer
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.example.audio.Availability
import org.example.audio.AudioBackend
import org.example.audio.AudioBackendException
import org.example.audio.AudioCallback
import org.example.audio.AudioStream
import org.example.audio.BackendDevice
import org.example.audio.BackendId
import org.example.audio.DeviceCapabilities
import org.example.audio.Pairing
import org.example.audio.StreamEvents
import org.example.audio.StreamFormat
import org.example.audio.StreamRequest
import org.example.audio.win.Rt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ASIO: the lowest-latency path on Windows, and the one this program is aimed at.
 *
 * Measured on the interface this was developed against (Sonicake, generic USB ASIO driver), the
 * driver's own round-trip claim runs from 5.2 ms at 8 frames to 27.6 ms at its preferred 512, and
 * every power of two in between opens. The Java Sound path on the same interface could not get
 * below about 150 ms. That is the whole reason this class exists.
 *
 * ### Two things about ASIO that shape the code
 *
 * **One driver owns both directions.** There is no pairing to arrange, no second crystal, and
 * therefore no cushion to regulate: exactly `bufferFrames` arrive and exactly `bufferFrames` leave
 * per callback, forever. [Pairing.SINGLE_DEVICE], and [StreamFormat.adapterLatencyFrames] is zero
 * because this adapter adds nothing.
 *
 * **Drivers are thread-affine.** They are in-process COM servers written for an STA host, and many
 * will misbehave if `createBuffers` arrives on a different thread from `init`. Every driver call
 * therefore goes through [ComWorker]. The realtime callback is the exception and does not: it
 * arrives on a thread the driver created, which JNA attaches to the JVM once and leaves attached.
 */
class AsioBackend : AudioBackend {

    override val id = BackendId.ASIO

    private val worker by lazy { ComWorker("omega-asio-control") }

    /**
     * Filled by [devices]; [capabilities] reads it rather than opening the driver a second time.
     *
     * Not stale the way a cache would be, which is why this backend needs no [AudioBackend.rescan]
     * body: [probeAll] **clears the map and rebuilds it from the drivers** on every call, so a
     * `devices()` — which is what Rescan does next — is already a full re-probe. It merges nothing
     * and skips nothing. The one time entries survive is under a running stream, where [devices]
     * returns the last list deliberately: enumerating means opening every driver, and several refuse
     * a second `init` while one of them is streaming.
     */
    private val probed = LinkedHashMap<String, Probe>()

    @Volatile
    private var live: AsioStream? = null

    override val availability: Availability = run {
        when {
            !Rt.isWindows -> Availability.Unavailable("ASIO is a Windows driver model")
            else -> try {
                if (AsioDriver.registered().isEmpty()) {
                    Availability.Unavailable(
                        "no ASIO driver is installed — install the one that came with your interface"
                    )
                } else {
                    Availability.Available
                }
            } catch (t: Throwable) {
                Availability.Unavailable("${t.javaClass.simpleName}: ${t.message ?: "no detail"}")
            }
        }
    }

    private data class Probe(
        val device: BackendDevice,
        val capabilities: DeviceCapabilities,
        val clsid: String,
    )

    override fun devices(): List<BackendDevice> {
        if (!availability.isAvailable) return emptyList()
        // Enumerating means opening each driver, so never do it under a running stream: several
        // drivers refuse a second init and a few take the device down.
        live?.let { return probed.values.map { p -> p.device } }
        return runCatching { worker.submit { probeAll() } }.getOrDefault(emptyList())
    }

    private fun probeAll(): List<BackendDevice> {
        probed.clear()
        for ((name, clsid) in AsioDriver.registered()) {
            var drv: AsioDriver? = null
            try {
                drv = AsioDriver.create(clsid)
                if (!drv.init(Pointer.NULL)) continue
                val (ins, outs) = drv.channels()
                if (ins == 0 && outs == 0) continue
                val rate = drv.sampleRate()
                val rates = CANDIDATE_RATES.filter { drv.canSampleRate(it.toDouble()) }
                val bs = drv.bufferSize()
                val device = BackendDevice(
                    backend = id,
                    id = clsid,
                    name = runCatching { drv.driverName() }.getOrDefault(name),
                    inputChannels = ins,
                    outputChannels = outs,
                )
                probed[clsid] = Probe(
                    device = device,
                    capabilities = DeviceCapabilities(
                        sampleRates = rates.ifEmpty { listOf(rate.toInt()) },
                        bufferSizes = legalBufferSizes(bs),
                        preferredBufferFrames = bs[2],
                        pairing = Pairing.SINGLE_DEVICE,
                        // Whatever the driver is set to right now, which is the user's own choice
                        // in its control panel. Overriding that by policy would be presumptuous.
                        preferredSampleRate = rate.toInt().takeIf { it > 0 }
                            ?: rates.firstOrNull() ?: 44_100,
                        hasControlPanel = true,
                    ),
                    clsid = clsid,
                )
            } catch (_: Throwable) {
                // One broken driver must not hide the working ones. This is common: half the ASIO
                // entries on a typical machine belong to hardware that is not plugged in.
            } finally {
                runCatching { drv?.release() }
            }
        }
        return probed.values.map { it.device }
    }

    /**
     * [sampleRate] is ignored: ASIO reports buffer sizes in frames, and the range does not move
     * with the rate. The period does, of course, but that is arithmetic the caller already has.
     */
    override fun capabilities(device: BackendDevice, sampleRate: Int?): DeviceCapabilities =
        probed[device.id]?.capabilities
            ?: DeviceCapabilities(
                sampleRates = listOf(44_100),
                bufferSizes = listOf(512),
                preferredBufferFrames = 512,
                pairing = Pairing.SINGLE_DEVICE,
                hasControlPanel = true,
            )

    override fun openControlPanel(device: BackendDevice) {
        // The driver's panel is modal and can sit there for as long as the user leaves it open, so
        // this deliberately has no timeout and must never be called from the EDT.
        live?.let { stream -> worker.submit(timeoutSeconds = 3600) { stream.driver.controlPanel() }; return }
        worker.submit(timeoutSeconds = 3600) {
            var drv: AsioDriver? = null
            try {
                drv = AsioDriver.create(device.id)
                if (drv.init(Pointer.NULL)) drv.controlPanel()
            } finally {
                runCatching { drv?.release() }
            }
        }
    }

    override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
        val device = request.output ?: request.input
            ?: throw AudioBackendException("No ASIO device selected.")
        live?.let { throw AudioBackendException("An ASIO stream is already open on ${it.deviceName}.") }

        val stream = worker.submit(timeoutSeconds = 60) {
            openOnWorker(device, request, callback, events)
        }
        stream.onClosed = { if (live === stream) live = null }
        live = stream
        return stream
    }

    private fun openOnWorker(
        device: BackendDevice,
        request: StreamRequest,
        callback: AudioCallback,
        events: StreamEvents,
    ): AsioStream {
        val drv = try {
            AsioDriver.create(device.id)
        } catch (t: Throwable) {
            throw AudioBackendException("Could not load the ASIO driver for ${device.name}: ${t.message}", t)
        }
        try {
            if (!drv.init(Pointer.NULL)) {
                throw AudioBackendException(
                    "${device.name} would not start: ${drv.errorMessage()}. " +
                        "Check the interface is connected and no other application has it open."
                )
            }
            // The driver decides. Ask for the requested rate and then read back what happened —
            // this interface, for instance, only does 44 100 and refuses 48 000 outright.
            if (drv.canSampleRate(request.sampleRate.toDouble())) drv.setSampleRate(request.sampleRate.toDouble())
            val rate = drv.sampleRate().toInt().let { if (it <= 0) request.sampleRate else it }

            val bs = drv.bufferSize()
            val frames = legalBufferSizes(bs)
                .minByOrNull { kotlin.math.abs(it - request.bufferFrames) } ?: bs[2]

            val (maxIn, maxOut) = drv.channels()
            val inChannel = request.inputChannel.coerceIn(0, (maxIn - 1).coerceAtLeast(0))
            val outChannels = request.outputChannels
                .filter { it in 0 until maxOut }
                .ifEmpty { (0 until minOf(2, maxOut)).toList() }
            if (maxIn == 0) throw AudioBackendException("${device.name} reports no input channels.")
            if (outChannels.isEmpty()) throw AudioBackendException("${device.name} reports no output channels.")

            val stream = AsioStream(drv, device.name, worker, callback, events, frames, rate, inChannel, outChannels)
            stream.createBuffers()
            return stream
        } catch (t: Throwable) {
            runCatching { drv.release() }
            throw if (t is AudioBackendException) t else AudioBackendException(
                "Could not open ${device.name}: ${t.message ?: t.javaClass.simpleName}", t
            )
        }
    }

    override fun shutdown() {
        runCatching { live?.close() }
        worker.shutdown()
    }

    private companion object {
        val CANDIDATE_RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000)
    }
}

/**
 * One open ASIO stream.
 *
 * The realtime path is [bufferSwitch] and everything it needs is a field: the double-buffer views,
 * the mono scratch arrays, the codec choice. Nothing in it allocates, takes a lock, or does I/O —
 * at 64 frames the whole body has 1.45 ms to complete and measured p99 is 0.009 ms of that.
 */
internal class AsioStream(
    val driver: AsioDriver,
    val deviceName: String,
    private val worker: ComWorker,
    private val callback: AudioCallback,
    private val events: StreamEvents,
    private val frames: Int,
    private val rate: Int,
    private val inputChannel: Int,
    private val outputChannels: List<Int>,
) : AudioStream {

    private val inputBlock = FloatArray(frames)
    private val outputBlock = FloatArray(frames)

    /** [doubleBufferIndex] -> the driver's buffer for that side. */
    private lateinit var inViews: Array<SampleView>
    private lateinit var outViews: Array<Array<SampleView>>   // [channel][doubleBufferIndex]

    private var bufferInfos: Memory? = null
    private var callbackStruct: Memory? = null
    private var signalOutputReady: (() -> Unit)? = null

    /** Invoked when this stream releases the driver, so the backend can accept a new open(). */
    var onClosed: (() -> Unit)? = null

    // Strong references: native holds the trampoline addresses and the GC must not reclaim them.
    private var cbSwitch: BufferSwitch? = null
    private var cbRate: SampleRateDidChange? = null
    private var cbMessage: AsioMessage? = null
    private var cbSwitchTime: BufferSwitchTimeInfo? = null

    @Volatile
    override var xruns: Int = 0
        private set

    @Volatile
    override var deadlineMisses: Int = 0
        private set

    @Volatile
    override var worstCallbackMicros: Int = 0
        private set

    /** Longest gap between two consecutive callbacks. A GC stall lands here, not in the body time. */
    @Volatile
    var worstGapMicros: Int = 0
        private set

    @Volatile
    private var framesProcessed: Long = 0

    @Volatile
    private var firstCallbackNanos: Long = 0

    private var lastCallbackNanos: Long = 0
    private var settle = 0

    override val realtimeRatio: Float
        get() {
            val t0 = firstCallbackNanos
            if (t0 == 0L) return 1f
            val elapsed = System.nanoTime() - t0
            if (elapsed <= 0) return 1f
            return (framesProcessed * 1_000_000_000.0 / (elapsed.toDouble() * rate)).toFloat()
        }

    @Volatile
    private var resetRequested = false

    @Volatile
    private var running = false

    private var mmcss: com.sun.jna.platform.win32.WinNT.HANDLE? = null
    private var priorityJoined = false
    private val periodNanos = frames * 1_000_000_000L / rate

    override lateinit var format: StreamFormat
        private set

    private var monitor: Thread? = null

    // ---- setup, on the control thread -------------------------------------

    fun createBuffers() {
        val total = 1 + outputChannels.size
        val infos = Memory(24L * total)
        infos.clear()
        // ASIOBufferInfo { ASIOBool isInput; long channelNum; void* buffers[2]; } == 24 bytes on x64.
        infos.setInt(0, 1)
        infos.setInt(4, inputChannel)
        outputChannels.forEachIndexed { i, ch ->
            val o = 24L * (1 + i)
            infos.setInt(o, 0)
            infos.setInt(o + 4, ch)
        }

        cbSwitch = BufferSwitch { index, _ -> bufferSwitch(index) }
        cbRate = SampleRateDidChange { resetRequested = true }
        cbMessage = AsioMessage { selector, value, _, _ -> asioMessage(selector, value) }
        cbSwitchTime = BufferSwitchTimeInfo { params, index, _ -> bufferSwitch(index); params }

        // Attach the driver's thread to the JVM once and leave it attached. Attaching per callback
        // is a hundreds-of-microseconds allocating operation that interacts with safepoints, and
        // against a 1.45 ms period it is an instant failure.
        val initializer = CallbackThreadInitializer(true, false, "omega-asio-render")
        Native.setCallbackThreadInitializer(cbSwitch, initializer)
        Native.setCallbackThreadInitializer(cbSwitchTime, initializer)
        Native.setCallbackThreadInitializer(cbMessage, initializer)
        Native.setCallbackThreadInitializer(cbRate, initializer)

        val cbs = Memory(32)
        cbs.setPointer(0, CallbackReference.getFunctionPointer(cbSwitch))
        cbs.setPointer(8, CallbackReference.getFunctionPointer(cbRate))
        cbs.setPointer(16, CallbackReference.getFunctionPointer(cbMessage))
        cbs.setPointer(24, CallbackReference.getFunctionPointer(cbSwitchTime))

        val err = driver.createBuffers(infos, total, frames, cbs)
        if (err != 0) {
            infos.close(); cbs.close()
            throw AudioBackendException(
                "$deviceName refused a $frames-frame buffer: ${driver.errorMessage()}"
            )
        }
        bufferInfos = infos
        callbackStruct = cbs

        val inType = driver.channelInfo(inputChannel, true).sampleType
        inViews = Array(2) { half ->
            SampleView(infos.getPointer(8L + half * 8L), frames, inType)
        }
        outViews = Array(outputChannels.size) { i ->
            val base = 24L * (1 + i)
            val type = driver.channelInfo(outputChannels[i], false).sampleType
            Array(2) { half -> SampleView(infos.getPointer(base + 8L + half * 8L), frames, type) }
        }

        signalOutputReady = if (driver.outputReady() == 0) driver.outputReadyInvoker() else null
        val (latIn, latOut) = runCatching { driver.latencies() }.getOrDefault(frames to frames)
        format = StreamFormat(
            sampleRate = rate,
            bufferFrames = frames,
            inputLatencyFrames = latIn,
            outputLatencyFrames = latOut,
            adapterLatencyFrames = 0,
        )
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun start() {
        worker.submit {
            Rt.beginHighResolutionTimer()
            running = true
            val e = driver.start()
            if (e != 0) {
                running = false
                Rt.endHighResolutionTimer()
                throw AudioBackendException("$deviceName would not start: ${driver.errorMessage()}")
            }
        }
        monitor = Thread(::monitorLoop, "omega-asio-monitor").apply { isDaemon = true; start() }
    }

    override fun stop() {
        if (!running) return
        running = false
        worker.submit {
            runCatching { driver.stop() }
            Rt.endHighResolutionTimer()
        }
        monitor?.interrupt()
        monitor = null
    }

    override fun close() {
        stop()
        worker.submit {
            runCatching { driver.disposeBuffers() }
            runCatching { driver.release() }
        }
        bufferInfos?.close(); bufferInfos = null
        callbackStruct?.close(); callbackStruct = null
        cbSwitch = null; cbRate = null; cbMessage = null; cbSwitchTime = null
        signalOutputReady = null
        Rt.leaveProAudio(mmcss); mmcss = null
        onClosed?.invoke()
    }

    /**
     * Everything that is allowed to allocate, log or call back into Swing.
     *
     * The audio thread only ever sets a volatile flag; this thread turns that into a
     * [StreamEvents] call. A driver reset — which is what the user changing the buffer size in the
     * driver's own control panel produces — is not an error, so it is reported as a format change.
     */
    private fun monitorLoop() {
        while (running) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return
            }
            if (resetRequested) {
                resetRequested = false
                val fresh = runCatching {
                    worker.submit {
                        val (i, o) = driver.latencies()
                        format.copy(
                            bufferFrames = driver.bufferSize()[2],
                            inputLatencyFrames = i,
                            outputLatencyFrames = o,
                        )
                    }
                }.getOrNull()
                events.onFormatChanged(fresh ?: format)
            }
        }
    }

    // ---- the realtime path -------------------------------------------------

    private fun bufferSwitch(index: Int) {
        val t0 = System.nanoTime()
        if (!priorityJoined) {
            // Must happen on this thread, and this is the first moment we are on it.
            priorityJoined = true
            mmcss = Rt.joinProAudio()
        } else {
            val gap = ((t0 - lastCallbackNanos) / 1_000L).toInt()
            if (gap > worstGapMicros) worstGapMicros = gap
        }
        lastCallbackNanos = t0
        val n = frames
        try {
            inViews[index].read(inputBlock, n)
            callback.render(inputBlock, outputBlock, n)
            val outs = outViews
            for (c in outs.indices) outs[c][index].write(outputBlock, n)
        } catch (_: Throwable) {
            // An exception escaping into JNI is not well defined. Fill silence, count it, carry on;
            // the monitor thread is what decides whether to tear the stream down.
            val outs = outViews
            java.util.Arrays.fill(outputBlock, 0, n, 0f)
            for (c in outs.indices) outs[c][index].write(outputBlock, n)
            xruns++
        }
        signalOutputReady?.invoke()
        // Steady state only: the first callbacks include the driver's own start-up transient, and
        // counting them reports a starvation that was really the stream beginning.
        if (settle < SETTLE_CALLBACKS) settle++
        else if (firstCallbackNanos == 0L) firstCallbackNanos = System.nanoTime()
        else framesProcessed += n

        val spent = System.nanoTime() - t0
        if (spent > periodNanos) deadlineMisses++
        val micros = (spent / 1000L).toInt()
        if (micros > worstCallbackMicros) worstCallbackMicros = micros
    }

    private companion object {
        /** Roughly a tenth of a second at the smallest buffer this driver family offers. */
        const val SETTLE_CALLBACKS = 64
    }

    private fun asioMessage(selector: Int, value: Int): Int = when (selector) {
        // kAsioSelectorSupported
        1 -> if (value == 1 || value == 3 || value == 4 || value == 5) 1 else 0
        // kAsioEngineVersion — 2 means "ASIO 2 host"
        3 -> 2
        // kAsioResetRequest / kAsioBufferSizeChange: flag only, never work, never allocate.
        4, 5 -> { resetRequested = true; 1 }
        else -> 0
    }

    // ---- sample conversion --------------------------------------------------

    /**
     * One driver buffer, pre-viewed at open.
     *
     * ASIO hands out non-interleaved buffers in whatever the hardware's native word format is —
     * Int32LSB on the interface this was built against, but Int16LSB, Int24LSB and Float32LSB all
     * occur. The `when` on format is paid **once, here**, so the per-sample loop has no branch and
     * the callback has no per-call allocation. `Pointer.getInt` per sample would be a JNI crossing
     * per sample; a direct `ByteBuffer` view is plain memory access.
     */
    private class SampleView(pointer: Pointer, frames: Int, private val type: Int) {

        private val bytes: ByteBuffer = pointer
            .getByteBuffer(0, frames.toLong() * bytesPerSample(type))
            .order(if (isBigEndian(type)) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

        private val ints = if (kind(type) == INT32) bytes.asIntBuffer() else null
        private val shorts = if (kind(type) == INT16) bytes.asShortBuffer() else null
        private val floats = if (kind(type) == FLOAT32) bytes.asFloatBuffer() else null
        private val scale = 1f / (1L shl (validBits(type) - 1)).toFloat()
        private val invScale = ((1L shl (validBits(type) - 1)) - 1).toFloat()
        private val k = kind(type)

        fun read(out: FloatArray, n: Int) {
            when (k) {
                INT32 -> { val b = ints!!; val s = scale; for (i in 0 until n) out[i] = b.get(i) * s }
                INT16 -> { val b = shorts!!; for (i in 0 until n) out[i] = b.get(i) / 32768f }
                FLOAT32 -> { val b = floats!!; for (i in 0 until n) out[i] = b.get(i) }
                else -> read24(out, n)
            }
        }

        fun write(src: FloatArray, n: Int) {
            when (k) {
                INT32 -> {
                    val b = ints!!; val s = invScale
                    for (i in 0 until n) b.put(i, (clamp(src[i]) * s).toInt())
                }
                INT16 -> {
                    val b = shorts!!
                    for (i in 0 until n) b.put(i, (clamp(src[i]) * 32767f).toInt().toShort())
                }
                FLOAT32 -> { val b = floats!!; for (i in 0 until n) b.put(i, clamp(src[i])) }
                else -> write24(src, n)
            }
        }

        private fun read24(out: FloatArray, n: Int) {
            val b = bytes
            for (i in 0 until n) {
                val o = i * 3
                val v = if (b.order() == ByteOrder.LITTLE_ENDIAN) {
                    (b.get(o).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or (b.get(o + 2).toInt() shl 16)
                } else {
                    (b.get(o + 2).toInt() and 0xFF) or ((b.get(o + 1).toInt() and 0xFF) shl 8) or (b.get(o).toInt() shl 16)
                }
                out[i] = v / 8388608f
            }
        }

        private fun write24(src: FloatArray, n: Int) {
            val b = bytes
            val little = b.order() == ByteOrder.LITTLE_ENDIAN
            for (i in 0 until n) {
                val v = (clamp(src[i]) * 8388607f).toInt()
                val o = i * 3
                if (little) {
                    b.put(o, v.toByte()); b.put(o + 1, (v shr 8).toByte()); b.put(o + 2, (v shr 16).toByte())
                } else {
                    b.put(o, (v shr 16).toByte()); b.put(o + 1, (v shr 8).toByte()); b.put(o + 2, v.toByte())
                }
            }
        }

        private fun clamp(v: Float) = if (v > 1f) 1f else if (v < -1f) -1f else v

        companion object {
            const val INT16 = 0
            const val INT24 = 1
            const val INT32 = 2
            const val FLOAT32 = 3

            fun kind(t: Int) = when (t) {
                0, 16 -> INT16
                1, 17 -> INT24
                3, 19 -> FLOAT32
                else -> INT32
            }

            fun isBigEndian(t: Int) = t in 0..4 || t in 32..35

            fun bytesPerSample(t: Int) = when (kind(t)) {
                INT16 -> 2
                INT24 -> 3
                else -> 4
            }

            /**
             * `Int32LSB16`/`18`/`20`/`24` are 32-bit containers with fewer meaningful bits, right
             * aligned. Scaling them as if they were full 32-bit is 48 dB of missing level, which is
             * a silent-input bug report rather than a crash.
             */
            fun validBits(t: Int) = when (t) {
                32, 48 -> 16
                33, 49 -> 18
                34, 50 -> 20
                35, 51 -> 24
                else -> when (kind(t)) {
                    INT16 -> 16
                    INT24 -> 24
                    FLOAT32 -> 1
                    else -> 32
                }
            }
        }
    }
}

/**
 * The buffer sizes an ASIO driver will accept, from its `getBufferSize` reply.
 *
 * `granularity` is the awkward part and is why this is a named function with tests rather than four
 * lines inline: **-1** means powers of two, **0** means "only the preferred size", and anything
 * positive is a step from the minimum. Get it wrong and the picker offers a size the driver refuses,
 * which surfaces to the user as our bug rather than as a driver limitation.
 *
 * The preferred size is always included even when the granularity rule would not generate it —
 * drivers do report a preferred value outside their own sequence, and refusing to offer the one size
 * the driver actually wants would be perverse.
 *
 * @param report min, max, preferred, granularity, exactly as ASIO returned them.
 */
internal fun legalBufferSizes(report: IntArray): List<Int> {
    val min = report[0]
    val max = report[1]
    val preferred = report[2]
    val granularity = report[3]
    if (min <= 0 || max < min) return listOf(preferred.coerceAtLeast(1))
    val sizes = LinkedHashSet<Int>()
    when {
        granularity == -1 -> {
            // Round the minimum up to a power of two before stepping, or a driver reporting min=100
            // would generate 100, 200, 400 — none of which are powers of two.
            var n = Integer.highestOneBit(min).let { if (it < min) it * 2 else it }
            while (n in 1..max) { sizes += n; n *= 2 }
        }
        granularity == 0 || min == max -> sizes += preferred
        else -> {
            var n = min
            while (n <= max && sizes.size < MAX_ENUMERATED) { sizes += n; n += granularity }
        }
    }
    if (preferred in min..max) sizes += preferred
    return sizes.filter { it in min..max }.sorted()
}

/** A driver with a granularity of 1 would otherwise generate a list thousands of entries long. */
private const val MAX_ENUMERATED = 64

// ---- ASIOCallbacks function pointers ---------------------------------------
internal fun interface BufferSwitch : Callback { fun invoke(index: Int, directProcess: Int) }
internal fun interface SampleRateDidChange : Callback { fun invoke(rate: Double) }
internal fun interface AsioMessage : Callback {
    fun invoke(selector: Int, value: Int, message: Pointer?, opt: Pointer?): Int
}
internal fun interface BufferSwitchTimeInfo : Callback {
    fun invoke(params: Pointer?, index: Int, directProcess: Int): Pointer?
}
