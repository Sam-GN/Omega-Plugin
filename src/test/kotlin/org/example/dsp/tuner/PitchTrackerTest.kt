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

    /** What a bass, a five-string bass, a seven-string and a dropped guitar put under the low E. */
    private val lowNotes = listOf(
        Triple("five-string bass B0", 30.8677f, GuitarString.B3),
        Triple("bass E1", 41.2034f, GuitarString.E2),
        Triple("bass A1", 55f, GuitarString.A2),
        Triple("seven-string B1", 61.7354f, GuitarString.B3),
        Triple("drop D2", 73.4162f, GuitarString.D3),
        Triple("bass G2", 97.9989f, GuitarString.G3),
    )

    @Test
    fun `a bass, a five-string bass and a seven-string read their strings by name, within a cent`() {
        for (rate in rates) {
            val tracker = PitchTracker(rate)
            for ((name, hz, string) in lowNotes) {
                for ((shape, signal) in listOf("sine" to sine(hz, rate, tracker.windowSize), "sawtooth" to sawtooth(hz, rate, tracker.windowSize))) {
                    val heard = tracker.estimate(signal, tracker.windowSize)
                    assertTrue(heard > 0f, "$name as $shape at $rate Hz: nothing heard")
                    assertTrue(abs(centsBetween(heard, hz)) < 1f, "$name as $shape at $rate Hz read $heard, ${centsBetween(heard, hz)} cents off")
                    assertEquals(string, GuitarString.nearest(heard), "$name is named by its pitch class")
                    assertEquals(string.label, GuitarString.nearest(heard).label)
                    assertTrue(abs(GuitarString.nearest(heard).cents(heard)) < 1f, "folded into the string's octave: ${GuitarString.nearest(heard).cents(heard)}")
                }
            }
        }
    }

    @Test
    fun `cents are folded into one octave, so a bass E thirty cents flat reads E minus thirty`() {
        val bassE = shifted(41.2034f, -30f)
        assertEquals(GuitarString.E2, GuitarString.nearest(bassE))
        assertEquals(-30f, GuitarString.E2.cents(bassE), 0.01f)
        val lowB = shifted(30.8677f, 40f)
        assertEquals(GuitarString.B3, GuitarString.nearest(lowB))
        assertEquals(40f, GuitarString.B3.cents(lowB), 0.01f)
        // Never beyond half an octave either way.
        for (hz in listOf(28f, 35f, 50f, 70f, 90f, 125f, 170f, 220f, 300f, 359f)) {
            assertTrue(abs(GuitarString.nearest(hz).cents(hz)) <= 300f, "$hz Hz is within 300 cents of some string")
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
    fun `the window is about the same time whatever the rate, and the search runs reduced`() {
        val at44 = PitchTracker(44_100f).windowSize / 44_100f
        val at48 = PitchTracker(48_000f).windowSize / 48_000f
        val at96 = PitchTracker(96_000f).windowSize / 96_000f
        assertEquals(at48, at96, 0.01f)
        assertEquals(at48, at44, 0.01f)
        assertTrue(at48 in 0.15f..0.25f, "about 180 ms: $at48 s")
        assertEquals(2, PitchTracker(44_100f).factor)
        assertEquals(2, PitchTracker(48_000f).factor)
        assertEquals(3, PitchTracker(96_000f).factor)
    }

    @Test
    fun `the string name holds until the pitch is clearly nearer another`() {
        // Named by pitch class, the strings are two or three semitones apart: A and B are 200 cents.
        val a = GuitarString.A2
        val b = GuitarString.B3
        // 90 cents above A: A is nearest.
        assertEquals(a, GuitarString.choose(shifted(a.hz, 90f), null))
        // 110 above A: B is only 20 cents nearer than A, so the name holds.
        assertEquals(a, GuitarString.choose(shifted(a.hz, 110f), a))
        // 140 above A: B is 80 cents nearer, so it switches.
        assertEquals(b, GuitarString.choose(shifted(a.hz, 140f), a))
        // Back to 110 above A, now from B: it holds B for the same reason.
        assertEquals(b, GuitarString.choose(shifted(a.hz, 110f), b))
        // With nothing heard before, 110 above A is simply the nearest, which is B.
        assertEquals(b, GuitarString.choose(shifted(a.hz, 110f), null))
        // The two Es share a pitch class; the one in the nearer octave is named.
        assertEquals(GuitarString.E2, GuitarString.nearest(82.4f))
        assertEquals(GuitarString.E4, GuitarString.nearest(329.6f))
    }
}
