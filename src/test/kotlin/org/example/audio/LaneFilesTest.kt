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

import org.example.dsp.loop.Looper
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A lane written and read back is the same lane; a long file is cut to a minute; a foreign rate is converted. */
class LaneFilesTest {

    private val folder = Files.createTempDirectory("omega-lane")

    @AfterTest
    fun cleanUp() {
        Files.walk(folder).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    @Test
    fun `save then load is a round trip, within 16-bit precision`() {
        val rate = 44_100
        val samples = FloatArray(1_000) { sin(it * 0.05).toFloat() * 0.8f }
        val file = folder.resolve("lane.wav").toFile()
        LaneFiles.save(file, samples, samples.size, rate)

        val back = LaneFiles.load(file, rate.toFloat())
        assertEquals(samples.size, back.count)
        assertFalse(back.truncated)
        // Loading goes through 16-bit PCM, the one conversion Java Sound always has: a step of
        // 1/32768, and its rounding is the converter's, so allow two.
        for (i in samples.indices) assertTrue(abs(samples[i] - back.samples[i]) < 2f / 32_768, "sample $i")
    }

    @Test
    fun `a file longer than a lane keeps its first minute and says so`() {
        val rate = 8_000
        val long = FloatArray(rate * (Looper.MAX_SECONDS + 5)) { 0.25f }
        val file = folder.resolve("long.wav").toFile()
        LaneFiles.save(file, long, long.size, rate)

        val back = LaneFiles.load(file, rate.toFloat())
        assertEquals(rate * Looper.MAX_SECONDS, back.count)
        assertTrue(back.truncated)
    }

    @Test
    fun `a file at another rate arrives at the stream's rate`() {
        val fileRate = 48_000
        val streamRate = 96_000f
        // A second of 1 kHz at 48 kHz: 48 cycles.
        val samples = FloatArray(fileRate) { sin(2 * Math.PI * 1_000 * it / fileRate).toFloat() * 0.5f }
        val file = folder.resolve("rate.wav").toFile()
        LaneFiles.save(file, samples, samples.size, fileRate)

        val back = LaneFiles.load(file, streamRate)
        assertTrue(abs(back.count - streamRate.toInt()) < 64, "about a second at 96 kHz: ${back.count}")
        // 1 kHz at 96 kHz is 96 frames a cycle: count the rising zero crossings in the middle.
        var crossings = 0
        for (i in 1_000 until back.count - 1_000) if (back.samples[i] <= 0f && back.samples[i + 1] > 0f) crossings++
        val expected = (back.count - 2_000) / 96.0
        assertTrue(abs(crossings - expected) <= 2, "$crossings crossings against $expected")
    }
}
