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
package org.example.dsp.tuner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against numbers worked out from the tuning, not from the code: a string's frequency, a cents
 * offset applied to it, and what a tuner must say about each.
 */
class PitchTrackerTest {

    private val rates = listOf(44_100f, 48_000f, 96_000f)

    /** [hz] shifted by [cents]. */
    private fun shifted(hz: Float, cents: Float): Float = hz * 2.0.pow(cents / 1200.0).toFloat()

    private fun sine(hz: Float, rate: Float, count: Int, amplitude: Float = 0.3f): FloatArray =
        FloatArray(count) { (amplitude * sin(2.0 * PI * hz * it / rate)).toFloat() }

    /** A sawtooth: every harmonic at 1/n, closer to a plucked string than a sine. */
    private fun sawtooth(hz: Float, rate: Float, count: Int): FloatArray = FloatArray(count) {
        val phase = (hz * it / rate) % 1.0
        (0.3 * (2.0 * phase - 1.0)).toFloat()
    }

    /** A note whose second harmonic is louder than its fundamental, which is what tempts an octave error. */
    private fun hollow(hz: Float, rate: Float, count: Int): FloatArray = FloatArray(count) {
        val t = it / rate
        (0.1 * sin(2.0 * PI * hz * t) + 0.25 * sin(2.0 * PI * 2 * hz * t) + 0.1 * sin(2.0 * PI * 3 * hz * t)).toFloat()
    }

    private fun centsBetween(measured: Float, expected: Float): Float =
        (1200.0 * kotlin.math.ln(measured / expected) / kotlin.math.ln(2.0)).toFloat()

    @Test
    fun `every open string reads within a cent, at three rates`() {
        for (rate in rates) {
            val tracker = PitchTracker(rate)
            for (string in GuitarString.entries) {
                val hz = tracker.estimate(sine(string.hz, rate, tracker.windowSize), tracker.windowSize)
                assertTrue(hz > 0f, "${string.name} at $rate Hz: nothing heard")
                assertTrue(abs(centsBetween(hz, string.hz)) < 1f, "${string.name} at $rate Hz read $hz, ${centsBetween(hz, string.hz)} cents off")
                assertTrue(tracker.lastClarity > 0.95f, "${string.name} should be a clear note, clarity ${tracker.lastClarity}")
            }
        }
    }

    @Test
    fun `a string twenty cents flat is still that string, and reads twenty cents flat`() {
        val rate = 48_000f
        val tracker = PitchTracker(rate)
        val hz = tracker.estimate(sine(shifted(GuitarString.A2.hz, -20f), rate, tracker.windowSize), tracker.windowSize)
        assertEquals(GuitarString.A2, GuitarString.nearest(hz))
        assertEquals(-20f, GuitarString.A2.cents(hz), 1f)
    }

    @Test
    fun `sixty cents sharp of D is still D, far off, not a flat E`() {
        val rate = 44_100f
        val tracker = PitchTracker(rate)
        val hz = tracker.estimate(sine(shifted(GuitarString.D3.hz, 60f), rate, tracker.windowSize), tracker.windowSize)
        assertEquals(GuitarString.D3, GuitarString.nearest(hz))
        assertEquals(60f, GuitarString.D3.cents(hz), 1f)
    }

    @Test
    fun `a sawtooth and a note with a loud second harmonic read the fundamental, not the octave`() {
        for (rate in rates) {
            val tracker = PitchTracker(rate)
            for (string in GuitarString.entries) {
                val saw = tracker.estimate(sawtooth(string.hz, rate, tracker.windowSize), tracker.windowSize)
                assertTrue(abs(centsBetween(saw, string.hz)) < 2f, "sawtooth ${string.name} at $rate Hz read $saw")
                val hollowed = tracker.estimate(hollow(string.hz, rate, tracker.windowSize), tracker.windowSize)
                assertTrue(abs(centsBetween(hollowed, string.hz)) < 2f, "hollow ${string.name} at $rate Hz read $hollowed, which is ${centsBetween(hollowed, string.hz)} cents off")
            }
        }
    }

    @Test
    fun `silence, hum and noise read as nothing`() {
        val rate = 48_000f
        val tracker = PitchTracker(rate)
        val n = tracker.windowSize
        assertEquals(0f, tracker.estimate(FloatArray(n), n), "silence")
        assertEquals(0f, tracker.estimate(sine(GuitarString.A2.hz, rate, n, amplitude = 0.001f), n), "a note under the gate")
        val random = java.util.Random(7)
        val noise = FloatArray(n) { (random.nextGaussian() * 0.1).toFloat() }
        assertEquals(0f, tracker.estimate(noise, n), "noise has no period")
        assertEquals(0f, tracker.lastClarity)
    }

    @Test
    fun `a note read over an offset does not depend on where it is in the buffer`() {
        val rate = 48_000f
        val tracker = PitchTracker(rate)
        val n = tracker.windowSize
        // A DC offset, which a cheap interface can carry, must not read as a very low note.
        val offset = sine(GuitarString.G3.hz, rate, n).also { for (i in it.indices) it[i] += 0.2f }
        val hz = tracker.estimate(offset, n)
        assertTrue(abs(centsBetween(hz, GuitarString.G3.hz)) < 1f, "with an offset G read $hz")
    }

    @Test
    fun `the window is a fixed time whatever the rate`() {
        val at48 = PitchTracker(48_000f).windowSize / 48_000f
        val at96 = PitchTracker(96_000f).windowSize / 96_000f
        assertEquals(at48, at96, 0.001f)
        assertTrue(at48 in 0.05f..0.1f, "about 70 ms: $at48 s")
    }

    @Test
    fun `the string name holds until the pitch is clearly nearer another`() {
        val a = GuitarString.A2
        // 200 cents above A: A is nearest, by a wide margin.
        assertEquals(a, GuitarString.choose(shifted(a.hz, 200f), null))
        // 240 above A: D is only 20 cents nearer than A, so the name holds.
        assertEquals(a, GuitarString.choose(shifted(a.hz, 240f), a))
        // 280 above A: D is 60 cents nearer, so it switches.
        assertEquals(GuitarString.D3, GuitarString.choose(shifted(a.hz, 280f), a))
        // Back to 240 above A, now from D: it holds D for the same reason.
        assertEquals(GuitarString.D3, GuitarString.choose(shifted(a.hz, 240f), GuitarString.D3))
        // With nothing heard before, 240 above A is simply the nearest.
        assertEquals(a, GuitarString.choose(shifted(a.hz, 240f), null))
    }
}
