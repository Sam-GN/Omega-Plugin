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

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.example.audio.win.ComObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * `IASIO` by direct vtable walk. No Steinberg SDK, no native build, no header.
 *
 * The vtable layout has been stable since ASIO 2.0 (2000) and is the only thing this depends on:
 *
 * ```
 *   0 QueryInterface   1 AddRef              2 Release
 *   3 init(void*)      4 getDriverName(char*) 5 getDriverVersion()
 *   6 getErrorMessage(char*)                  7 start()             8 stop()
 *   9 getChannels(long*, long*)              10 getLatencies(long*, long*)
 *  11 getBufferSize(long*, long*, long*, long*)
 *  12 canSampleRate(double)                  13 getSampleRate(double*)   14 setSampleRate(double)
 *  15 getClockSources(...)                   16 setClockSource(long)
 *  17 getSamplePosition(int64*, int64*)      18 getChannelInfo(ASIOChannelInfo*)
 *  19 createBuffers(ASIOBufferInfo*, long, long, ASIOCallbacks*)
 *  20 disposeBuffers()                       21 controlPanel()      22 future(long, void*)
 *  23 outputReady()
 * ```
 *
 * ASIO's `long` is the C `long`, which is 32-bit on Windows for both x86 and x64 — so every one of
 * those out-parameters is an `int`, not a `long`. Getting that wrong reads garbage rather than
 * failing, which is why it is spelled out here.
 */
internal class AsioDriver(p: Pointer) : ComObject(p) {

    fun init(sysHandle: Pointer?): Boolean = call(3, sysHandle) != 0

    fun driverName(): String = withMemory(128) { m ->
        m.clear(); callVoid(4, m); m.getString(0)
    }

    fun driverVersion(): Int = call(5)

    fun errorMessage(): String = withMemory(256) { m ->
        m.clear(); callVoid(6, m); m.getString(0)
    }

    fun start(): Int = call(7)
    fun stop(): Int = call(8)

    /** @return inputs to outputs. */
    fun channels(): Pair<Int, Int> {
        val i = IntByReference()
        val o = IntByReference()
        val e = call(9, i, o)
        if (e != 0) throw AsioError("getChannels", e, this)
        return i.value to o.value
    }

    /**
     * Driver-reported input and output latency in frames, valid only once buffers exist.
     *
     * Measured on the Sonicake this comes out as `bufferFrames + 66` in and a floor of 154 out, so
     * the driver really is computing it from the current buffer size rather than returning a
     * constant. It still excludes converter latency, which is why it is reported as the driver's
     * claim and never as the truth.
     */
    fun latencies(): Pair<Int, Int> {
        val i = IntByReference()
        val o = IntByReference()
        val e = call(10, i, o)
        if (e != 0) throw AsioError("getLatencies", e, this)
        return i.value to o.value
    }

    /** min, max, preferred, granularity — granularity -1 meaning "powers of two". */
    fun bufferSize(): IntArray {
        val a = IntByReference(); val b = IntByReference()
        val c = IntByReference(); val d = IntByReference()
        val e = call(11, a, b, c, d)
        if (e != 0) throw AsioError("getBufferSize", e, this)
        return intArrayOf(a.value, b.value, c.value, d.value)
    }

    fun canSampleRate(rate: Double): Boolean = call(12, rate) == 0

    fun sampleRate(): Double = withMemory(8) { m ->
        if (call(13, m) != 0) 0.0 else m.getDouble(0)
    }

    fun setSampleRate(rate: Double): Int = call(14, rate)

    fun channelInfo(channel: Int, input: Boolean): ChannelInfo = withMemory(52) { m ->
        // ASIOChannelInfo { long channel; ASIOBool isInput; ASIOBool isActive; long channelGroup;
        //                   ASIOSampleType type; char name[32]; }
        m.clear()
        m.setInt(0, channel)
        m.setInt(4, if (input) 1 else 0)
        val e = call(18, m)
        if (e != 0) throw AsioError("getChannelInfo", e, this)
        ChannelInfo(m.getInt(16), m.getString(20))
    }

    fun createBuffers(bufferInfos: Pointer, numChannels: Int, bufferFrames: Int, callbacks: Pointer): Int =
        call(19, bufferInfos, numChannels, bufferFrames, callbacks)

    fun disposeBuffers(): Int = call(20)

    fun controlPanel(): Int = call(21)

    /** 0 means the driver supports the output-ready optimisation and wants to be told. */
    fun outputReady(): Int = call(23)

    /**
     * A pre-bound `ASIOOutputReady()` for the realtime path.
     *
     * The generic [call] builds an argument array per invocation, which is one small allocation per
     * callback — harmless anywhere else and against the rules here. Binding the function and its
     * argument array once removes it.
     */
    fun outputReadyInvoker(): () -> Unit {
        val fn = slot(23)
        val args = arrayOf<Any?>(p)
        return { fn.invokeInt(args) }
    }

    data class ChannelInfo(val sampleType: Int, val name: String)

    companion object {

        /** Every driver registered under `HKLM\SOFTWARE\ASIO`, deduped by CLSID. */
        fun registered(): List<Pair<String, String>> {
            val out = LinkedHashMap<String, String>()   // clsid -> name
            for (root in arrayOf("SOFTWARE\\ASIO", "SOFTWARE\\WOW6432Node\\ASIO")) {
                if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, root)) continue
                for (name in Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, root)) {
                    val sub = "$root\\$name"
                    if (!Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, sub, "CLSID")) continue
                    val clsid = runCatching {
                        Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, sub, "CLSID")
                    }.getOrNull() ?: continue
                    out.putIfAbsent(clsid.uppercase(), name)
                }
            }
            return out.map { (clsid, name) -> name to clsid }
        }

        /** ASIO's convention is that the interface id equals the class id. */
        fun create(clsid: String): AsioDriver {
            val g = Guid.GUID.fromString(clsid)
            val pp = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(g, null, CLSCTX_INPROC_SERVER, g, pp).toInt()
            if (hr != 0) throw org.example.audio.win.ComException("CoCreateInstance($clsid)", hr)
            return AsioDriver(pp.value)
        }

        fun sampleTypeName(t: Int): String = when (t) {
            0 -> "Int16MSB"; 1 -> "Int24MSB"; 2 -> "Int32MSB"; 3 -> "Float32MSB"; 4 -> "Float64MSB"
            16 -> "Int16LSB"; 17 -> "Int24LSB"; 18 -> "Int32LSB"; 19 -> "Float32LSB"; 20 -> "Float64LSB"
            48 -> "Int32LSB16"; 49 -> "Int32LSB18"; 50 -> "Int32LSB20"; 51 -> "Int32LSB24"
            else -> "type $t"
        }
    }
}

internal class AsioError(what: String, val code: Int, driver: AsioDriver?) : RuntimeException(
    "$what failed (ASIO error $code${driver?.let { ": " + runCatching { it.errorMessage() }.getOrDefault("?") } ?: ""})"
)

/** Scratch native memory for one call, freed deterministically rather than left to a cleaner. */
private inline fun <T> withMemory(bytes: Long, block: (Memory) -> T): T {
    val m = Memory(bytes)
    return try {
        block(m)
    } finally {
        m.close()
    }
}

/**
 * A single thread that owns every ASIO call, with COM initialised apartment-threaded on it.
 *
 * ASIO drivers are in-process COM servers written for an STA host — the SDK's own host calls
 * `CoInitialize(0)` — and many of them are thread-affine to the point of crashing if `createBuffers`
 * arrives on a different thread from `init`. Rather than hope, everything that touches a driver is
 * funnelled through here. It costs one thread and removes an entire class of "works on my machine".
 *
 * The realtime callback does **not** come back on this thread; it arrives on a thread the driver
 * creates, which is the whole point of ASIO.
 */
internal class ComWorker(name: String) {

    private val queue = ArrayBlockingQueue<FutureTask<*>>(32)

    @Volatile
    private var alive = true

    private val thread = Thread({
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        try {
            while (alive) {
                val task = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                task.run()
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    fun <T> submit(timeoutSeconds: Long = 30, body: () -> T): T {
        if (Thread.currentThread() === thread) return body()
        val task = FutureTask(Callable { body() })
        check(queue.offer(task, 5, TimeUnit.SECONDS)) { "ASIO control thread is not accepting work" }
        return task.get(timeoutSeconds, TimeUnit.SECONDS)
    }

    fun shutdown() {
        alive = false
    }
}
