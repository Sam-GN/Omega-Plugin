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
package org.example.audio

/** Conversion between interleaved 16-bit little-endian PCM bytes and mono float samples. */
internal object Pcm {

    /** Decodes [frames] interleaved frames, mixing all [channels] down to mono in [out]. */
    fun decode(bytes: ByteArray, frames: Int, channels: Int, out: FloatArray) {
        var p = 0
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val lo = bytes[p].toInt() and 0xFF
                val hi = bytes[p + 1].toInt()
                sum += ((hi shl 8) or lo).toShort().toInt()
                p += 2
            }
            out[i] = sum / (channels * 32_768f)
        }
    }

    /** Encodes [frames] mono samples, duplicated across [channels], into [bytes]. */
    fun encode(src: FloatArray, frames: Int, channels: Int, bytes: ByteArray) {
        var p = 0
        for (i in 0 until frames) {
            val s = (src[i] * 32_767f).toInt().coerceIn(-32_768, 32_767)
            val lo = (s and 0xFF).toByte()
            val hi = ((s shr 8) and 0xFF).toByte()
            for (c in 0 until channels) {
                bytes[p++] = lo
                bytes[p++] = hi
            }
        }
    }
}
