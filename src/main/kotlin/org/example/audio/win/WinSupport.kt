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
package org.example.audio.win

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Just enough Windows to drive ASIO and WASAPI, and no more.
 *
 * There is no SDK here on purpose. The Steinberg ASIO SDK would mean a native build step and
 * vendored code under a licence of its own, so `IASIO` is reached by walking its COM vtable by
 * hand — a layout that has not changed since ASIO 2.0 in 2000. WASAPI is reached the same way rather
 * than through a generated binding, which keeps the only native dependency JNA (used here under
 * **LGPL 2.1**, the option JNA offers alongside Apache 2.0; both are GPLv3-compatible, and the
 * election is recorded in README.md).
 *
 * x64 only: one calling convention, and every vtable slot is eight bytes.
 */
open class ComObject(@JvmField val p: Pointer) {

    private val vtbl: Pointer = p.getPointer(0)

    protected fun slot(index: Int): Function = Function.getFunction(vtbl.getPointer(index * 8L))

    /** Invokes vtable [index] with `this` prepended, returning the HRESULT (or the ASIO error). */
    fun call(index: Int, vararg args: Any?): Int {
        val a = arrayOfNulls<Any?>(args.size + 1)
        a[0] = p
        System.arraycopy(args, 0, a, 1, args.size)
        return slot(index).invokeInt(a)
    }

    fun callVoid(index: Int, vararg args: Any?) {
        val a = arrayOfNulls<Any?>(args.size + 1)
        a[0] = p
        System.arraycopy(args, 0, a, 1, args.size)
        slot(index).invokeVoid(a)
    }

    fun release() {
        runCatching { call(2) }
    }

    companion object {
        fun check(hr: Int, what: String) {
            if (hr != 0) throw ComException(what, hr)
        }

        fun createInstance(clsid: Guid.GUID, iid: Guid.GUID): ComObject {
            val pp = PointerByReference()
            check(Ole32.INSTANCE.CoCreateInstance(clsid, null, CLSCTX_ALL, iid, pp).toInt(), "CoCreateInstance")
            return ComObject(pp.value)
        }

        const val CLSCTX_ALL = 0x17
        const val CLSCTX_INPROC_SERVER = 0x1
    }
}

class ComException(what: String, val hr: Int) :
    RuntimeException(String.format("%s failed hr=0x%08X (%s)", what, hr, hrName(hr)))

/**
 * The `AUDCLNT_E_*` codes you actually hit when probing exclusive mode.
 *
 * Worth spelling out rather than printing hex: `AUDCLNT_E_DEVICE_IN_USE` and
 * `AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED` are user-fixable and mean completely different things,
 * and "0x8889000A" tells nobody which one happened.
 */
fun hrName(hr: Int): String = when (hr) {
    0x88890001.toInt() -> "AUDCLNT_E_NOT_INITIALIZED"
    0x88890002.toInt() -> "AUDCLNT_E_ALREADY_INITIALIZED"
    0x88890003.toInt() -> "AUDCLNT_E_WRONG_ENDPOINT_TYPE"
    0x88890004.toInt() -> "AUDCLNT_E_DEVICE_INVALIDATED"
    0x88890005.toInt() -> "AUDCLNT_E_NOT_STOPPED"
    0x88890006.toInt() -> "AUDCLNT_E_BUFFER_TOO_LARGE"
    0x88890008.toInt() -> "AUDCLNT_E_UNSUPPORTED_FORMAT"
    0x8889000A.toInt() -> "AUDCLNT_E_DEVICE_IN_USE"
    0x8889000C.toInt() -> "AUDCLNT_E_THREAD_NOT_REGISTERED"
    0x8889000E.toInt() -> "AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED"
    0x8889000F.toInt() -> "AUDCLNT_E_ENDPOINT_CREATE_FAILED"
    0x88890010.toInt() -> "AUDCLNT_E_SERVICE_NOT_RUNNING"
    0x88890013.toInt() -> "AUDCLNT_E_BUFDURATION_PERIOD_NOT_EQUAL"
    0x88890014.toInt() -> "AUDCLNT_E_EVENTHANDLE_NOT_SET"
    0x88890015.toInt() -> "AUDCLNT_E_INCORRECT_BUFFER_SIZE"
    0x88890019.toInt() -> "AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED"
    0x88890020.toInt() -> "AUDCLNT_E_INVALID_DEVICE_PERIOD"
    0x80070005.toInt() -> "E_ACCESSDENIED"
    0x80004002.toInt() -> "E_NOINTERFACE"
    0x80070057.toInt() -> "E_INVALIDARG"
    0x8000FFFF.toInt() -> "E_UNEXPECTED"
    else -> "hr 0x%08X".format(hr)
}

/**
 * Realtime scheduling, which the JVM will not do for us.
 *
 * `Thread.MAX_PRIORITY` is close to meaningless on Windows. What matters is MMCSS: joining the
 * "Pro Audio" task class is what stops an ordinary background thread from preempting the audio
 * callback. It has to be called **on the callback thread itself**, which for ASIO is a thread the
 * driver created, so it happens on the first callback rather than at open.
 */
object Rt {

    private interface Avrt : Library {
        fun AvSetMmThreadCharacteristicsW(taskName: WString, taskIndex: IntByReference): WinNT.HANDLE?
        fun AvSetMmThreadPriority(handle: WinNT.HANDLE, priority: Int): Boolean
        fun AvRevertMmThreadCharacteristics(handle: WinNT.HANDLE): Boolean
    }

    private interface Winmm : Library {
        fun timeBeginPeriod(period: Int): Int
        fun timeEndPeriod(period: Int): Int
    }

    private val avrt: Avrt? by lazy {
        runCatching { Native.load("avrt", Avrt::class.java) }.getOrNull()
    }
    private val winmm: Winmm? by lazy {
        runCatching { Native.load("winmm", Winmm::class.java) }.getOrNull()
    }

    private const val AVRT_PRIORITY_CRITICAL = 2

    /**
     * Puts the *calling* thread in the "Pro Audio" MMCSS class at critical priority.
     *
     * @return the handle to revert with, or null if MMCSS refused — which is not fatal, only worse.
     */
    fun joinProAudio(): WinNT.HANDLE? {
        val a = avrt ?: return null
        val idx = IntByReference(0)
        val h = runCatching { a.AvSetMmThreadCharacteristicsW(WString("Pro Audio"), idx) }.getOrNull()
        if (h == null || Pointer.nativeValue(h.pointer) == 0L) return null
        runCatching { a.AvSetMmThreadPriority(h, AVRT_PRIORITY_CRITICAL) }
        return h
    }

    fun leaveProAudio(handle: WinNT.HANDLE?) {
        val a = avrt ?: return
        if (handle != null) runCatching { a.AvRevertMmThreadCharacteristics(handle) }
    }

    /**
     * Raises the system timer resolution to 1 ms for the life of the stream.
     *
     * Nothing in the JVM does this, so every timer-driven wait it performs is quantised to the
     * 15.6 ms scheduler tick — which is the whole reason Java Sound behaves as badly as it does here.
     * Real audio hosts call this; so do we, and we drop it again on stop so the machine's power
     * behaviour goes back to normal.
     */
    fun beginHighResolutionTimer(): Boolean = winmm?.let { it.timeBeginPeriod(1) == 0 } ?: false

    fun endHighResolutionTimer() {
        winmm?.timeEndPeriod(1)
    }

    val isWindows: Boolean get() = System.getProperty("os.name", "").startsWith("Windows")
}
