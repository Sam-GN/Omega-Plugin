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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 100 ns duration handed to `Initialize` must come back to the frames it was made from, whether
 * the driver floors or rounds on the way back. Truncating it did not at 44.1 kHz, and a Cirrus Logic
 * SoundWire output refused every size there (2.1.5). Worked from the arithmetic, not from the code:
 * a driver's two plausible conversions are written out here.
 */
class WasapiDurationTest {

    private fun floored(hns: Long, rate: Int): Long = hns * rate / 10_000_000L
    private fun rounded(hns: Long, rate: Int): Long = Math.round(hns * rate / 1e7)

    @Test
    fun `every size at every rate survives a driver that floors and one that rounds`() {
        for (rate in CANDIDATE_RATES) {
            for (frames in 16..4096) {
                val hns = hnsForFrames(frames, rate)
                assertEquals(frames.toLong(), floored(hns, rate), "$frames frames at $rate Hz, floored")
                assertEquals(frames.toLong(), rounded(hns, rate), "$frames frames at $rate Hz, rounded")
            }
        }
    }

    @Test
    fun `truncating, as before, lost a frame at 44_1 kHz for most sizes`() {
        // The guard that the test above is testing something: the old conversion fails it.
        val lost = (16..4096).count { frames -> floored(frames * 10_000_000L / 44_100, 44_100) != frames.toLong() }
        assertTrue(lost > 4000, "truncation lost a frame on $lost of 4081 sizes")
    }
}
