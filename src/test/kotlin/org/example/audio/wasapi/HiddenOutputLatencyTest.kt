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

import org.example.audio.StreamFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The round trip WASAPI exclusive shows, against the two outputs that were measured on the laptop
 * that needed it (2.2.1): a Pocket Master, whose clock stood exactly one buffer behind what was
 * written, and a Cirrus Logic SoundWire Line Out, which reported a 192-frame buffer and stood about
 * 4 900 frames behind at 48 kHz. The expected figures are that arithmetic, not the code's output.
 */
class HiddenOutputLatencyTest {

    private val pocket = StreamFormat(44_100, 264, 264, 264, 264)
    private val cirrus = StreamFormat(48_000, 192, 287, 192, 227, inputRate = 44_100)

    @Test
    fun `an output that holds what it reports is left exactly as reported`() {
        assertSame(pocket, WasapiStream.withHiddenOutput(pocket, 264))
    }

    @Test
    fun `nothing is added before the clock has been read`() {
        assertSame(cirrus, WasapiStream.withHiddenOutput(cirrus, -1))
    }

    @Test
    fun `a hundred milliseconds the driver did not report goes into the round trip and is named`() {
        // 102.7 ms written and not played, at 48 kHz.
        val queued = 4_930
        val shown = WasapiStream.withHiddenOutput(cirrus, queued)
        assertEquals(queued, shown.outputLatencyFrames)
        assertEquals(queued - 192, shown.hiddenOutputLatencyFrames)
        // Was (287 + 192 + 227) / 48 = 14.7 ms; is (287 + 4930 + 227) / 48.
        assertEquals((287 + 4_930 + 227) * 1000f / 48_000, shown.reportedRoundTripMs, 0.001f)
        assertEquals(113.4f, shown.reportedRoundTripMs, 0.1f)
    }

    @Test
    fun `under two milliseconds over is the clock's jitter and changes nothing`() {
        assertSame(pocket, WasapiStream.withHiddenOutput(pocket, 264 + 88))   // 1.996 ms at 44.1 kHz
        assertEquals(264 + 89, WasapiStream.withHiddenOutput(pocket, 264 + 89).outputLatencyFrames)
    }

    @Test
    fun `a clock reading less than the report never lowers it`() {
        // Shared-style clocks can read under the buffer; the reported figure stands.
        assertSame(cirrus, WasapiStream.withHiddenOutput(cirrus, 100))
    }
}
