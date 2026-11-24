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

import org.example.Allocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [DropoutCounters] against periods of 5 ms with a known cause each. */
class DropoutCountersTest {

    private val ms = 1_000_000L

    @Test
    fun `an on-time period with audio queued is nothing`() {
        val d = DropoutCounters(5 * ms)
        repeat(100) { d.period(4 * ms, 4 * ms, 256) }
        assertEquals(0, d.total)
        assertEquals(256, d.lowestQueued)
    }

    @Test
    fun `a late period is slow when it ran and stalled when it did not`() {
        val d = DropoutCounters(5 * ms)
        d.period(8 * ms, 7 * ms, 100)
        assertEquals(1, d.slow)
        d.period(8 * ms, 2 * ms, 100)
        assertEquals(1, d.stalled)
        // Not knowing how long it ran, the blame stays with our own code.
        d.period(8 * ms, -1, 100)
        assertEquals(2, d.slow)
    }

    @Test
    fun `an empty output is put down to the late period that caused it, or to something else`() {
        val d = DropoutCounters(5 * ms)
        d.period(8 * ms, 8 * ms, 0)
        assertEquals(1, d.emptyAfterLate)
        d.period(1 * ms, 1 * ms, -3)
        assertEquals(1, d.emptyOther)
        assertEquals(3, d.total)
        // An avail that failed is the write's to report.
        d.period(1 * ms, 1 * ms, Long.MAX_VALUE)
        assertEquals(3, d.total)
    }

    @Test
    fun `the gap is between two writes and never across a restart`() {
        val d = DropoutCounters(5 * ms)
        d.written(100 * ms)
        d.written(105 * ms)
        d.written(117 * ms)
        assertEquals(12 * ms, d.longestGapNanos)
        d.restart()
        d.written(900 * ms)
        assertEquals(12 * ms, d.longestGapNanos)
    }

    @Test
    fun `it allocates nothing`() {
        val d = DropoutCounters(5 * ms)
        val before = Allocation.currentThreadBytes()
        for (i in 0 until 20_000L) { d.period(i * 1000, i * 500, 256 - i % 300); d.written(i * ms) }
        assertTrue(Allocation.currentThreadBytes() - before < 64 * 1024)
    }
}
