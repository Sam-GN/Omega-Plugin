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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parts of the ALSA backend that need no card: reading `/proc/asound` and the sample codec.
 * Neither touches libasound, so these run on Windows too.
 *
 * The byte patterns are worked out by hand from the format definitions, not read back from the
 * encoder — a round trip alone would pass an encoder and decoder that agreed on the wrong layout.
 */
class AlsaTest {

    @Test
    fun `pcm list is read from the kernel's own format`() {
        // Verbatim from the development machine, plus a USB interface as /proc prints one.
        val lines = listOf(
            "00-00: 92HD95 Analog : 92HD95 Analog : playback 1 : capture 1",
            "00-03: HDMI 0 : HDMI 0 : playback 1",
            "01-00: USB Audio : USB Audio : playback 1 : capture 1",
            "",
        )
        val pcms = AlsaProc.pcms(lines)
        assertEquals(3, pcms.size)
        assertEquals(AlsaProc.Pcm(0, 0, "92HD95 Analog", playback = true, capture = true), pcms[0])
        assertEquals(AlsaProc.Pcm(0, 3, "HDMI 0", playback = true, capture = false), pcms[1])
        assertEquals(1, pcms[2].card)
    }

    @Test
    fun `cards give the stable id, not the index`() {
        val lines = listOf(
            " 0 [PCH            ]: HDA-Intel - HDA Intel PCH",
            "                      HDA Intel PCH at 0x605d1c8000 irq 203",
            " 1 [Sonicake       ]: USB-Audio - Sonicake USB Audio",
            "                      Sonicake at usb-0000:00:14.0-2, full speed",
        )
        val cards = AlsaProc.cards(lines)
        assertEquals(mapOf(0 to ("PCH" to "HDA Intel PCH"), 1 to ("Sonicake" to "Sonicake USB Audio")), cards)
    }

    @Test
    fun `S24_3LE is three little-endian bytes, sign in the top one`() {
        val b = buffer(3)
        // 0.5 * 8388607 = 4194303.5, truncated to 0x3FFFFF.
        AlsaCodec.encode(floatArrayOf(0.5f), 1, b, AlsaApi.FORMAT_S24_3LE, 1, booleanArrayOf(true))
        assertEquals(listOf(0xFF, 0xFF, 0x3F), bytes(b, 3))

        // -0.5 -> -4194303 = 0xC00001 in 24-bit two's complement.
        AlsaCodec.encode(floatArrayOf(-0.5f), 1, b, AlsaApi.FORMAT_S24_3LE, 1, booleanArrayOf(true))
        assertEquals(listOf(0x01, 0x00, 0xC0), bytes(b, 3))

        // 0x800000 is the most negative 24-bit value: exactly -1.
        b.put(0, 0x00); b.put(1, 0x00); b.put(2, 0x80.toByte())
        val out = FloatArray(1)
        AlsaCodec.decode(b, AlsaApi.FORMAT_S24_3LE, 3, 0, out, 1)
        assertEquals(-1f, out[0])
    }

    @Test
    fun `S16 and S32 scale to full range`() {
        val b = buffer(4)
        AlsaCodec.encode(floatArrayOf(1f), 1, b, AlsaApi.FORMAT_S16_LE, 1, booleanArrayOf(true))
        assertEquals(32767.toShort(), b.getShort(0))
        // 2147483647f is not representable and rounds to 2^31, so full scale lands on the two ends
        // of the int range exactly — the positive one by saturation, which is the property that
        // matters: neither end wraps to the other sign.
        AlsaCodec.encode(floatArrayOf(-1f), 1, b, AlsaApi.FORMAT_S32_LE, 1, booleanArrayOf(true))
        assertEquals(Int.MIN_VALUE, b.getInt(0))
        AlsaCodec.encode(floatArrayOf(1f), 1, b, AlsaApi.FORMAT_S32_LE, 1, booleanArrayOf(true))
        assertEquals(Int.MAX_VALUE, b.getInt(0))

        b.putInt(0, Int.MIN_VALUE)
        val out = FloatArray(1)
        AlsaCodec.decode(b, AlsaApi.FORMAT_S32_LE, 4, 0, out, 1)
        assertEquals(-1f, out[0])
    }

    @Test
    fun `encode clamps rather than wrapping`() {
        val b = buffer(2)
        AlsaCodec.encode(floatArrayOf(3f), 1, b, AlsaApi.FORMAT_S16_LE, 1, booleanArrayOf(true))
        // Unclamped, 3 * 32767 wraps through Short to a negative value: a full-scale click.
        assertEquals(32767.toShort(), b.getShort(0))
    }

    @Test
    fun `the right input channel is taken out of an interleaved frame`() {
        // Four channels of S16, three frames; the guitar is on channel 2 (third slot).
        val channels = 4
        val b = buffer(3 * channels * 2)
        for (frame in 0 until 3) for (ch in 0 until channels) {
            b.putShort((frame * channels + ch) * 2, (if (ch == 2) 1000 * (frame + 1) else -7).toShort())
        }
        val out = FloatArray(3)
        AlsaCodec.decode(b, AlsaApi.FORMAT_S16_LE, channels * 2, 2 * 2, out, 3)
        assertEquals(listOf(1000f, 2000f, 3000f), out.map { it * 32768f })
    }

    @Test
    fun `unchosen output channels are written as silence, not left stale`() {
        val channels = 8
        val b = buffer(2 * channels * 4)
        // Fill with garbage first: a slot the encoder skipped would keep it.
        for (i in 0 until b.capacity()) b.put(i, 0x55)
        val mask = BooleanArray(channels) { it == 0 || it == 1 }
        AlsaCodec.encode(floatArrayOf(0.25f, -0.25f), 2, b, AlsaApi.FORMAT_FLOAT_LE, channels, mask)
        for (frame in 0 until 2) for (ch in 0 until channels) {
            val v = b.getFloat((frame * channels + ch) * 4)
            if (mask[ch]) assertEquals(if (frame == 0) 0.25f else -0.25f, v)
            else assertEquals(0f, v, "frame $frame channel $ch")
        }
    }

    @Test
    fun `formats are preferred best first`() {
        val order = AlsaApi.FORMATS.toList()
        assertEquals(AlsaApi.FORMAT_FLOAT_LE, order.first())
        assertEquals(AlsaApi.FORMAT_S16_LE, order.last())
        assertTrue(order.indexOf(AlsaApi.FORMAT_S32_LE) < order.indexOf(AlsaApi.FORMAT_S24_3LE))
        assertFalse(order.isEmpty())
    }

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun bytes(b: ByteBuffer, n: Int) = (0 until n).map { b.get(it).toInt() and 0xFF }
}
