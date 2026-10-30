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

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * `alsa/asoundlib.h`, the parts a duplex stream needs, declared by hand.
 *
 * Split the same way as the JACK bindings: [AlsaApi] is the interface mapping for setup, which may
 * allocate; [AlsaRt] is the direct mapping for the handful of calls the stream loop makes, which
 * must not. Buffers cross [AlsaRt] as raw addresses (`Long`) so that reading into the middle of a
 * buffer does not need a `Pointer.share` — that would be an object per partial read.
 *
 * `libasound.so.2` is on every desktop distribution there is, because every sound server sits on
 * it; that is what makes this the backend that is never missing.
 */
internal interface AlsaApi : Library {
    fun snd_pcm_open(pcm: PointerByReference, name: String, stream: Int, mode: Int): Int
    fun snd_pcm_close(pcm: Pointer): Int
    fun snd_strerror(error: Int): String

    fun snd_pcm_hw_params_malloc(params: PointerByReference): Int
    fun snd_pcm_hw_params_free(params: Pointer)
    fun snd_pcm_hw_params_any(pcm: Pointer, params: Pointer): Int
    fun snd_pcm_hw_params_set_access(pcm: Pointer, params: Pointer, access: Int): Int
    fun snd_pcm_hw_params_test_format(pcm: Pointer, params: Pointer, format: Int): Int
    fun snd_pcm_hw_params_set_format(pcm: Pointer, params: Pointer, format: Int): Int
    fun snd_pcm_hw_params_get_channels_min(params: Pointer, value: IntByReference): Int
    fun snd_pcm_hw_params_get_channels_max(params: Pointer, value: IntByReference): Int
    fun snd_pcm_hw_params_set_channels(pcm: Pointer, params: Pointer, channels: Int): Int
    fun snd_pcm_hw_params_set_rate_resample(pcm: Pointer, params: Pointer, allow: Int): Int
    fun snd_pcm_hw_params_test_rate(pcm: Pointer, params: Pointer, rate: Int, dir: Int): Int
    fun snd_pcm_hw_params_set_rate(pcm: Pointer, params: Pointer, rate: Int, dir: Int): Int
    fun snd_pcm_hw_params_test_period_size(pcm: Pointer, params: Pointer, frames: Long, dir: Int): Int
    fun snd_pcm_hw_params_set_period_size(pcm: Pointer, params: Pointer, frames: Long, dir: Int): Int
    fun snd_pcm_hw_params_set_buffer_size_near(pcm: Pointer, params: Pointer, frames: LongByReference): Int
    fun snd_pcm_hw_params_get_buffer_size(params: Pointer, frames: LongByReference): Int
    fun snd_pcm_hw_params(pcm: Pointer, params: Pointer): Int

    fun snd_pcm_sw_params_malloc(params: PointerByReference): Int
    fun snd_pcm_sw_params_free(params: Pointer)
    fun snd_pcm_sw_params_current(pcm: Pointer, params: Pointer): Int
    fun snd_pcm_sw_params_set_start_threshold(pcm: Pointer, params: Pointer, frames: Long): Int
    fun snd_pcm_sw_params_set_avail_min(pcm: Pointer, params: Pointer, frames: Long): Int
    fun snd_pcm_sw_params(pcm: Pointer, params: Pointer): Int

    fun snd_pcm_link(a: Pointer, b: Pointer): Int
    fun snd_pcm_unlink(pcm: Pointer): Int

    companion object {
        const val STREAM_PLAYBACK = 0
        const val STREAM_CAPTURE = 1
        const val NONBLOCK = 1
        const val ACCESS_RW_INTERLEAVED = 3

        const val FORMAT_S16_LE = 2
        const val FORMAT_S32_LE = 10
        const val FORMAT_FLOAT_LE = 14
        const val FORMAT_S24_3LE = 32

        /** Best first: no conversion, then the widest integer, then what every device does. */
        val FORMATS = intArrayOf(FORMAT_FLOAT_LE, FORMAT_S32_LE, FORMAT_S24_3LE, FORMAT_S16_LE)

        fun bytesPerSample(format: Int) = when (format) {
            FORMAT_S16_LE -> 2
            FORMAT_S24_3LE -> 3
            else -> 4
        }

        fun formatName(format: Int) = when (format) {
            FORMAT_S16_LE -> "S16_LE"
            FORMAT_S24_3LE -> "S24_3LE"
            FORMAT_S32_LE -> "S32_LE"
            FORMAT_FLOAT_LE -> "FLOAT_LE"
            else -> "format $format"
        }

        const val EAGAIN = 11
        const val EPIPE = 32
        const val ESTRPIPE = 86
        const val EBUSY = 16
    }
}

/** The stream loop's calls. Direct mapping: primitives and existing `Pointer`s only, no allocation. */
internal object AlsaRt {
    @JvmStatic external fun snd_pcm_readi(pcm: Pointer, buffer: Long, frames: Long): Long
    @JvmStatic external fun snd_pcm_writei(pcm: Pointer, buffer: Long, frames: Long): Long
    @JvmStatic external fun snd_pcm_avail(pcm: Pointer): Long
    @JvmStatic external fun snd_pcm_wait(pcm: Pointer, timeoutMillis: Int): Int
    @JvmStatic external fun snd_pcm_prepare(pcm: Pointer): Int
    @JvmStatic external fun snd_pcm_start(pcm: Pointer): Int
    @JvmStatic external fun snd_pcm_drop(pcm: Pointer): Int

    @Volatile
    private var registered = false

    @Synchronized
    fun register(library: NativeLibrary) {
        if (registered) return
        Native.register(AlsaRt::class.java, library)
        registered = true
    }
}

internal object AlsaLibrary {
    const val SONAME = "libasound.so.2"

    val api: AlsaApi by lazy {
        val native = NativeLibrary.getInstance(SONAME)
        AlsaRt.register(native)
        Native.load(SONAME, AlsaApi::class.java)
    }
}
