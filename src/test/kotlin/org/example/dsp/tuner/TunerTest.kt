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
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ring between the audio thread and the reader, driven block by block as the engine does. */
class TunerTest {

    private fun feedTone(tuner: Tuner, hz: Float, rate: Float, seconds: Float, block: Int) {
        var sample = 0L
        val total = (rate * seconds).toInt()
        val buffer = FloatArray(block)
        while (sample < total) {
            for (i in 0 until block) buffer[i] = (0.3 * sin(2.0 * PI * hz * (sample + i) / rate)).toFloat()
            tuner.feed(buffer, block)
            sample += block
        }
    }

    @Test
    fun `off, the tuner takes nothing and says nothing`() {
        val tuner = Tuner()
        tuner.prepare(48_000f)
        feedTone(tuner, GuitarString.A2.hz, 48_000f, 0.5f, 64)
        assertNull(tuner.read(), "not enabled, so nothing was written")
    }

    @Test
    fun `a note fed in small blocks reads as that note, and reads the same twice`() {
        val tuner = Tuner()
        tuner.prepare(48_000f)
        tuner.enabled = true
        feedTone(tuner, GuitarString.G3.hz, 48_000f, 0.3f, 64)
        val first = assertNotNull(tuner.read())
        assertEquals(GuitarString.G3, first.string)
        assertTrue(abs(first.cents) < 1f, "${first.cents} cents")
        assertTrue(first.clarity > 0.95f)
        val second = assertNotNull(tuner.read(), "nothing new arrived, so the same window is read again")
        assertEquals(first.hz, second.hz)
    }

    @Test
    fun `odd block sizes and a rate change are both fine`() {
        val tuner = Tuner()
        tuner.prepare(44_100f)
        tuner.enabled = true
        feedTone(tuner, GuitarString.E2.hz, 44_100f, 0.3f, 37)
        assertEquals(GuitarString.E2, assertNotNull(tuner.read()).string)
        tuner.prepare(96_000f)
        assertNull(tuner.read(), "a new rate starts from an empty ring")
        feedTone(tuner, GuitarString.E4.hz, 96_000f, 0.3f, 1000)
        val reading = assertNotNull(tuner.read())
        assertEquals(GuitarString.E4, reading.string)
        assertTrue(abs(reading.cents) < 1f, "${reading.cents} cents")
    }

    @Test
    fun `less than a window is not enough to say anything`() {
        val tuner = Tuner()
        tuner.prepare(48_000f)
        tuner.enabled = true
        feedTone(tuner, GuitarString.A2.hz, 48_000f, 0.02f, 64)
        assertNull(tuner.read())
    }

    @Test
    fun `silence forgets the string, so the next note is named by what it is nearest to`() {
        val tuner = Tuner()
        tuner.prepare(48_000f)
        tuner.enabled = true
        // 110 cents above A: B is the nearest, by 20 cents, but A holds while A is the string being heard.
        val between = GuitarString.A2.hz * Math.pow(2.0, 110 / 1200.0).toFloat()
        feedTone(tuner, GuitarString.A2.hz, 48_000f, 0.3f, 64)
        assertEquals(GuitarString.A2, assertNotNull(tuner.read()).string)
        feedTone(tuner, between, 48_000f, 0.3f, 64)
        assertEquals(GuitarString.A2, assertNotNull(tuner.read()).string, "held, since B is barely nearer")
        feedTone(tuner, 0f, 48_000f, 0.3f, 64)
        assertNull(tuner.read(), "silence")
        feedTone(tuner, between, 48_000f, 0.3f, 64)
        assertEquals(GuitarString.B3, assertNotNull(tuner.read()).string, "named fresh: B is the nearest")
    }

    @Test
    fun `the warm-up leaves the ring empty and the switch as it was`() {
        val tuner = Tuner()
        tuner.prepare(48_000f)
        tuner.warmUp(FloatArray(64) { 0.1f }, 64, 400)
        assertEquals(false, tuner.enabled)
        assertNull(tuner.read())
    }
}
