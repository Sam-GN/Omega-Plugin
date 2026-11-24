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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reservoir between a converted ALSA input and the output's clock, driven by a simulated pair
 * of devices: a 44.1 kHz input delivering 64-frame lumps into a 48 kHz output taking 256-frame
 * periods — the Pocket Master into the Razer, at the sizes the backend would choose for them.
 *
 * The input's clock can be set fast or slow by a number of ppm, which is the whole difficulty: two
 * USB devices disagree by tens of ppm, and a reservoir that only works at zero drift fills or
 * empties in minutes.
 */
class CaptureReservoirTest {

    private val period = 256
    private val capturePeriod = 64

    /** 64 frames at 44.1 kHz become 69.66 at 48 kHz: 69 or 70, so 70 is the lump, plus the converter's slack. */
    private val chunk = 72

    /**
     * Feeds [reservoir] the way the stream does: per output period, the input has captured
     * 256 × 44100 / 48000 × (1 + ppm) frames, delivered only in whole capture periods, each turning
     * into 69 or 70 frames by an exact running count. Values are a counter, so the order can be
     * checked too.
     */
    private class Pair(val reservoir: CaptureReservoir, private val ppm: Double, private val capturePeriod: Int) {
        private var captured = 0.0
        private var delivered = 0L
        private var converted = 0L
        private var next = 1f
        private val lump = FloatArray(128)

        fun outputPeriod(period: Int) {
            captured += period * 44_100.0 / 48_000.0 * (1 + ppm / 1e6)
            while (captured - delivered >= capturePeriod) {
                delivered += capturePeriod
                val total = delivered * 48_000L / 44_100L
                val n = (total - converted).toInt()
                converted = total
                for (i in 0 until n) lump[i] = next++
                reservoir.push(lump, n)
            }
        }
    }

    @Test
    fun `the window is a period plus a lump, and half a period more than a lump wide`() {
        val r = CaptureReservoir(period, chunk)
        assertEquals(256 + 72, r.low)
        assertEquals(328 + 72 + 128, r.high)
        // Mean of the window, less the period that leaves: (328 + 528) / 2 - 256.
        assertEquals(172, r.latencyFrames)
    }

    @Test
    fun `start-up silence is not an underrun`() {
        val r = CaptureReservoir(period, chunk)
        val out = FloatArray(period) { 9f }
        r.push(FloatArray(r.low - 1) { 1f }, r.low - 1)
        assertTrue(r.take(out), "filling up to the window is start-up, not a dropout")
        assertTrue(out.all { it == 0f })
        assertEquals(r.low - 1, r.level, "nothing is consumed while priming")
    }

    @Test
    fun `at matched clocks every frame comes out once, in order, with no corrections`() {
        val r = CaptureReservoir(period, chunk)
        val pair = Pair(r, ppm = 0.0, capturePeriod)
        val out = FloatArray(period)
        var expect = 0f
        repeat(20_000) {
            pair.outputPeriod(period)
            assertTrue(r.take(out))
            if (out[0] != 0f) {
                if (expect == 0f) expect = out[0]
                for (v in out) {
                    assertEquals(expect, v)
                    expect++
                }
            }
        }
        assertTrue(expect > 1_000_000f, "the stream was running, not priming, for almost all of it")
        assertEquals(0, r.corrections)
        assertEquals(0, r.overflows)
    }

    @Test
    fun `a fast or slow input is held inside the window`() {
        // 100 ppm is several times what two real USB crystals disagree by.
        for (ppm in doubleArrayOf(100.0, -100.0)) {
            val r = CaptureReservoir(period, chunk)
            val pair = Pair(r, ppm, capturePeriod)
            val out = FloatArray(period)
            var underruns = 0
            var lowest = Int.MAX_VALUE
            var highest = 0
            // 20 minutes of 256-frame periods at 48 kHz.
            repeat(225_000) { n ->
                pair.outputPeriod(period)
                if (n > 100) {
                    lowest = minOf(lowest, r.level)
                    highest = maxOf(highest, r.level)
                }
                if (!r.take(out)) underruns++
            }
            assertEquals(0, underruns, "$ppm ppm")
            assertEquals(0, r.overflows, "$ppm ppm")
            assertTrue(lowest >= period, "$ppm ppm: fell to $lowest")
            assertTrue(highest <= r.high + chunk, "$ppm ppm: rose to $highest")
            // 20 minutes at 48 kHz is 57.6 M frames; 100 ppm of that is 5 760 frames to absorb,
            // one correction each — less whatever the level absorbed by crossing the window before
            // the first correction was needed, which is at most the window's width.
            val slack = r.high - r.low + chunk
            assertTrue(abs(r.corrections - 5_760) <= slack, "$ppm ppm: ${r.corrections} corrections")
        }
    }

    @Test
    fun `an input that stops is one underrun, then priming again`() {
        val r = CaptureReservoir(period, chunk)
        val pair = Pair(r, 0.0, capturePeriod)
        val out = FloatArray(period)
        repeat(100) { pair.outputPeriod(period); assertTrue(r.take(out)) }
        // The input goes quiet: what is left drains, then one period comes up short.
        var failures = 0
        repeat(10) { if (!r.take(out)) failures++ }
        assertEquals(1, failures, "counted once, not once a period for as long as it lasts")
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `reset empties it and starts priming again`() {
        val r = CaptureReservoir(period, chunk)
        r.push(FloatArray(1000) { 1f }, 1000)
        r.reset()
        assertEquals(0, r.level)
        val out = FloatArray(period) { 9f }
        assertTrue(r.take(out))
        assertFalse(out.any { it != 0f })
    }

    @Test
    fun `the converted input runs at about half an output period, from sizes it accepts`() {
        val sizes = listOf(16, 32, 64, 128, 256, 512, 1024, 2048)
        // 256 frames at 48 kHz is 235 at 44.1; half is 117; the largest size under that is 64.
        assertEquals(64, convertedCapturePeriod(256, 48_000, 44_100, sizes))
        // 1024 -> 941 -> 470 -> 256.
        assertEquals(256, convertedCapturePeriod(1024, 48_000, 44_100, sizes))
        // 64 -> 59 -> 29, which is under the floor of 32.
        assertEquals(32, convertedCapturePeriod(64, 48_000, 44_100, sizes))
        // Converting down: 256 at 44.1 kHz is 279 at 48; half is 139 -> 128.
        assertEquals(128, convertedCapturePeriod(256, 44_100, 48_000, sizes))
        // Nothing small enough: the smallest it has.
        assertEquals(128, convertedCapturePeriod(256, 48_000, 44_100, listOf(128, 256)))
        // A busy device reported nothing: the plain equivalent, and the open will say why.
        assertEquals(235, convertedCapturePeriod(256, 48_000, 44_100, emptyList()))
    }
}
