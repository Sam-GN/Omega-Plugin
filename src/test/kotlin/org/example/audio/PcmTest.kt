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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmTest {

    @Test
    fun `mono round-trip preserves sample values`() {
        val original = floatArrayOf(0f, 0.5f, -0.5f, 0.999f, -0.999f)
        val bytes = ByteArray(original.size * 2)
        Pcm.encode(original, original.size, 1, bytes)

        val decoded = FloatArray(original.size)
        Pcm.decode(bytes, original.size, 1, decoded)

        for (i in original.indices) {
            assertEquals(original[i], decoded[i], 1e-3f, "sample $i")
        }
    }

    @Test
    fun `stereo encode duplicates the mono sample across both channels`() {
        val bytes = ByteArray(4)
        Pcm.encode(floatArrayOf(0.5f), 1, 2, bytes)
        assertEquals(bytes[0], bytes[2])
        assertEquals(bytes[1], bytes[3])
    }

    @Test
    fun `stereo decode averages the channels`() {
        // Left and right are equal and opposite, so the mono mix must cancel to silence.
        val left = ByteArray(2).also { Pcm.encode(floatArrayOf(0.8f), 1, 1, it) }
        val right = ByteArray(2).also { Pcm.encode(floatArrayOf(-0.8f), 1, 1, it) }

        val interleaved = byteArrayOf(left[0], left[1], right[0], right[1])
        val decoded = FloatArray(1)
        Pcm.decode(interleaved, 1, 2, decoded)

        assertTrue(kotlin.math.abs(decoded[0]) < 1e-3f, "opposed channels cancelled: ${decoded[0]}")
    }

    @Test
    fun `encode clamps out-of-range samples instead of wrapping`() {
        val bytes = ByteArray(4)
        Pcm.encode(floatArrayOf(4f, -4f), 2, 1, bytes)

        val decoded = FloatArray(2)
        Pcm.decode(bytes, 2, 1, decoded)

        assertTrue(decoded[0] > 0.99f, "positive overflow clamped high, got ${decoded[0]}")
        assertTrue(decoded[1] < -0.99f, "negative overflow clamped low, got ${decoded[1]}")
    }
}
