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
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import org.example.audio.win.ComObject

/**
 * `IMMDevice` / `IAudioClient` by vtable slot, per the Windows SDK headers.
 *
 * Hand-declared for the same reason ASIO is: no SDK, no generated binding, no native build step —
 * only JNA, taken under LGPL 2.1.
 */
internal object WasapiIds {
    val CLSID_MMDeviceEnumerator: Guid.GUID = Guid.GUID.fromString("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
    val IID_IMMDeviceEnumerator: Guid.GUID = Guid.GUID.fromString("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
    val IID_IAudioClient: Guid.GUID = Guid.GUID.fromString("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")

    /** Windows 10's low-latency shared mode. Absent on older drivers, which is a fallback, not a fault. */
    val IID_IAudioClient3: Guid.GUID = Guid.GUID.fromString("{7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42}")
    val IID_IAudioRenderClient: Guid.GUID = Guid.GUID.fromString("{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}")
    val IID_IAudioCaptureClient: Guid.GUID = Guid.GUID.fromString("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")

    const val RENDER = 0
    const val CAPTURE = 1
    const val DEVICE_STATE_ACTIVE = 0x1
    const val SHARED = 0
    const val EXCLUSIVE = 1
    const val STREAMFLAGS_EVENTCALLBACK = 0x00040000
    const val STREAMFLAGS_NOPERSIST = 0x00080000
    const val E_BUFFER_SIZE_NOT_ALIGNED = 0x88890019.toInt()
}

internal class MMDeviceEnumerator(p: Pointer) : ComObject(p) {

    fun endpoints(dataFlow: Int, stateMask: Int): MMDeviceCollection {
        val pp = PointerByReference()
        check(call(3, dataFlow, stateMask, pp), "EnumAudioEndpoints")
        return MMDeviceCollection(pp.value)
    }

    fun list(dataFlow: Int): List<MMDevice> {
        val c = endpoints(dataFlow, WasapiIds.DEVICE_STATE_ACTIVE)
        return try {
            (0 until c.count()).map { c.item(it) }
        } finally {
            c.release()
        }
    }

    companion object {
        fun create() = MMDeviceEnumerator(
            createInstance(WasapiIds.CLSID_MMDeviceEnumerator, WasapiIds.IID_IMMDeviceEnumerator).p
        )
    }
}

internal class MMDeviceCollection(p: Pointer) : ComObject(p) {
    fun count(): Int = IntByReference().let { n -> check(call(3, n), "GetCount"); n.value }
    fun item(i: Int): MMDevice {
        val pp = PointerByReference()
        check(call(4, i, pp), "Item")
        return MMDevice(pp.value)
    }
}

internal class MMDevice(p: Pointer) : ComObject(p) {

    fun activateOrNull(iid: Guid.GUID): ComObject? {
        val pp = PointerByReference()
        return if (call(3, iid, CLSCTX_ALL, Pointer.NULL, pp) == 0) ComObject(pp.value) else null
    }

    fun audioClientOrNull(): AudioClient? = activateOrNull(WasapiIds.IID_IAudioClient)?.let { AudioClient(it.p) }

    /**
     * The same object through its `IAudioClient3` face, or null where the driver has no such face.
     *
     * Everything `IAudioClient` does still works on it — the extra methods simply sit further down
     * the same vtable.
     */
    fun audioClient3OrNull(): AudioClient? =
        activateOrNull(WasapiIds.IID_IAudioClient3)?.let { AudioClient(it.p) }

    /** The endpoint id string: stable across reboots and USB reshuffles, which is what we key on. */
    fun id(): String {
        val pp = PointerByReference()
        check(call(5, pp), "GetId")
        return pp.value.getWideString(0)
    }

    fun friendlyName(): String {
        val pp = PointerByReference()
        if (call(4, 0 /* STGM_READ */, pp) != 0) return "(unnamed device)"
        val store = ComObject(pp.value)
        // PROPERTYKEY { GUID fmtid; DWORD pid; } is 20 bytes with no padding. Written field by
        // field so the GUID byte order is unambiguous rather than trusting a string parse.
        val key = Memory(20)
        key.clear()
        key.setInt(0, 0xA45C254E.toInt())
        key.setShort(4, 0xDF1C.toShort())
        key.setShort(6, 0x4EFD.toShort())
        key.write(8, byteArrayOf(0x80.toByte(), 0x20, 0x67, 0xD1.toByte(), 0x46, 0xA8.toByte(), 0x50, 0xE0.toByte()), 0, 8)
        key.setInt(16, 14)   // PKEY_Device_FriendlyName
        // PROPVARIANT is 24 bytes on x64: vt, three reserved shorts, then an 8-byte-aligned union.
        val pv = Memory(24)
        pv.clear()
        val name = if (store.call(5, key, pv) != 0) {
            "(unnamed device)"
        } else {
            val vt = pv.getShort(0).toInt() and 0xFFFF
            val s = pv.getPointer(8)
            if (vt == 31 && s != null) s.getWideString(0) else "(unnamed device)"
        }
        key.close(); pv.close(); store.release()
        return name
    }
}

internal class AudioClient(p: Pointer) : ComObject(p) {

    fun initialize(shareMode: Int, flags: Int, bufferDuration100ns: Long, periodicity100ns: Long, wfx: Pointer): Int =
        call(3, shareMode, flags, bufferDuration100ns, periodicity100ns, wfx, Pointer.NULL)

    fun bufferSizeFrames(): Int = IntByReference().let { n -> check(call(4, n), "GetBufferSize"); n.value }

    /**
     * Frames still queued in the endpoint buffer.
     *
     * The difference between this and the buffer size is how much the device will actually take
     * right now. Assuming a full buffer per event is only correct when the driver's period equals
     * its buffer, and drivers do round one without the other — measured here, an HDMI endpoint
     * accepted a 144-frame request, reported a 192-frame buffer, and kept firing its event every
     * 144 frames. Writing 192 into that overruns it a third of the time.
     */
    fun currentPadding(): Int = IntByReference().let { n -> check(call(6, n), "GetCurrentPadding"); n.value }

    fun streamLatency100ns(): Long {
        val m = Memory(8)
        return try {
            if (call(5, m) == 0) m.getLong(0) else -1L
        } finally {
            m.close()
        }
    }

    /** 0 means supported; in exclusive mode there is no "closest match" to return. */
    fun isFormatSupported(shareMode: Int, wfx: Pointer): Int = call(7, shareMode, wfx, Pointer.NULL)

    /**
     * The rate this endpoint is configured for in Windows, from `GetMixFormat`.
     *
     * Worth having even though the mixer is bypassed in exclusive mode: it is what the user set in
     * Sound Settings, so it is the right default for the rate picker, and on a device that only
     * does one rate it is that rate. Returns 0 if the call fails rather than inventing 44 100.
     */
    fun mixFormatRate(): Int {
        val pp = PointerByReference()
        if (call(8, pp) != 0) return 0
        val wfx = pp.value ?: return 0
        return try {
            wfx.getInt(4)   // WAVEFORMATEX.nSamplesPerSec
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * The endpoint's shared-mode format, straight from `GetMixFormat`.
     *
     * Shared mode does not negotiate: the engine mixes everyone into one stream, so a client either
     * uses the format that stream is in or asks Windows to convert for it. Taking the mix format
     * directly is the honest option — it is exactly what the device is running.
     *
     * The pointer is COM-allocated and belongs to the caller; free it with `Ole32.CoTaskMemFree`.
     */
    fun mixFormat(): Pointer? {
        val pp = PointerByReference()
        if (call(8, pp) != 0) return null
        return pp.value
    }

    /** default period, minimum period — both in 100 ns units. */
    fun devicePeriod(): LongArray {
        val d = Memory(8)
        val m = Memory(8)
        return try {
            check(call(9, d, m), "GetDevicePeriod")
            longArrayOf(d.getLong(0), m.getLong(0))
        } finally {
            d.close(); m.close()
        }
    }

    fun start(): Int = call(10)
    fun stop(): Int = call(11)
    fun setEventHandle(h: WinNT.HANDLE): Int = call(13, h)

    // ------------------------------------------------------------ IAudioClient3
    //
    // These live at slots 18 and 20, **not** 15 and 17. `IAudioClient2` sits in between and inserts
    // IsOffloadCapable, SetClientProperties and GetBufferSizeLimits at 15-17. Getting this wrong does
    // not crash — it calls the wrong function and comes back as a nonsensical HRESULT, which is a
    // long way to walk before finding the cause. Only call these on an object obtained through
    // [MMDevice.audioClient3OrNull].

    /**
     * The engine's periods for a format, in **frames**: default, fundamental, minimum, maximum.
     * Returns null when the driver has nothing to say.
     *
     * **This was expected to be the way to make shared mode fast, and on this machine it is not.**
     * Every endpoint here answers the call — but with `minimum == default`, so there is no headroom
     * to claim:
     *
     * | Endpoint | default | fundamental | min | max |
     * | --- | ---: | ---: | ---: | ---: |
     * | Pocket Master (in and out) | 441 | 441 | 441 | 441 |
     * | SteelSeries Arctis 1 (in) | 480 | 480 | 480 | 480 |
     * | SteelSeries Arctis 1 (out) | 441 | 441 | 441 | 441 |
     * | ESS SABRE HiFi (out) | 480 | 1 | 336 | 480 |
     *
     * Only the ESS SABRE reports room below its default, and `InitializeSharedAudioStream` at 336
     * still handed back the same 1056-frame buffer the ordinary path gets. A low-latency shared mode
     * needs a driver written for it; the API being present says nothing about that. Shared mode's
     * ~66 ms therefore stands, and ASIO remains the fast path.
     *
     * Kept, along with [initializeSharedAudioStream], because this measurement is the answer to "can
     * shared mode go faster on machine X" and the call is how you take it — see the
     * `-Domega.wasapi.debug` branch in `WasapiBackend.probeShared`.
     */
    fun sharedModeEnginePeriod(wfx: Pointer): IntArray? {
        val d = IntByReference(); val f = IntByReference(); val min = IntByReference(); val max = IntByReference()
        if (call(18, wfx, d, f, min, max) != 0) return null
        return intArrayOf(d.value, f.value, min.value, max.value)
    }

    /** Initialises shared mode at an explicit period in frames. Event-driven, like the other path. */
    fun initializeSharedAudioStream(flags: Int, periodInFrames: Int, wfx: Pointer): Int =
        call(20, flags, periodInFrames, wfx, Pointer.NULL)

    fun service(iid: Guid.GUID): ComObject {
        val pp = PointerByReference()
        check(call(14, iid, pp), "GetService")
        return ComObject(pp.value)
    }

    companion object {
        const val WAVE_FORMAT_PCM = 1
        const val WAVE_FORMAT_IEEE_FLOAT = 3
        const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

        /**
         * Reads channels, rate, bits and float-ness out of a `WAVEFORMATEX`.
         *
         * A mix format is almost always `WAVE_FORMAT_EXTENSIBLE`, where the tag says nothing and the
         * real encoding is a GUID 24 bytes in. Its first four bytes are the original tag, which is
         * all that is needed to tell PCM from IEEE float.
         */
        fun describe(wfx: Pointer): FormatInfo {
            val tag = wfx.getShort(0).toInt() and 0xFFFF
            val channels = wfx.getShort(2).toInt() and 0xFFFF
            val rate = wfx.getInt(4)
            val bits = wfx.getShort(14).toInt() and 0xFFFF
            val effectiveTag = if (tag == WAVE_FORMAT_EXTENSIBLE) wfx.getInt(24) else tag
            return FormatInfo(channels, rate, bits, effectiveTag == WAVE_FORMAT_IEEE_FLOAT)
        }

        /** A plain 18-byte `WAVEFORMATEX`; every driver tried accepts this in exclusive mode. */
        fun waveFormat(channels: Int, rate: Int, bits: Int, floatFormat: Boolean): Memory {
            val m = Memory(18)
            m.clear()
            val blockAlign = channels * bits / 8
            m.setShort(0, (if (floatFormat) WAVE_FORMAT_IEEE_FLOAT else WAVE_FORMAT_PCM).toShort())
            m.setShort(2, channels.toShort())
            m.setInt(4, rate)
            m.setInt(8, rate * blockAlign)
            m.setShort(12, blockAlign.toShort())
            m.setShort(14, bits.toShort())
            m.setShort(16, 0)
            return m
        }
    }

    /** What an endpoint is actually running, as read out of a `WAVEFORMATEX`. */
    class FormatInfo(val channels: Int, val rate: Int, val bits: Int, val isFloat: Boolean) {
        override fun toString() =
            "$rate Hz, $channels ch, $bits-bit ${if (isFloat) "float" else "PCM"}"
    }
}
