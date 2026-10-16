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
package org.example.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CPU readout, against a fake clock and a fake counter.
 *
 * Both are injected precisely so this can be asserted against arithmetic worked out here rather than
 * against whatever the machine running the tests happens to be doing.
 */
class CpuUsageTest {

    private val second = CpuUsage.MIN_INTERVAL_NANOS

    /** A counter that advances by however much CPU it is told the process used. */
    private class FakeCpu {
        var nanos = 0L
        fun read(): Long = nanos
    }

    @Test
    fun `nothing is reported until there are two samples to subtract`() {
        val cpu = FakeCpu()
        val usage = CpuUsage(cpu::read, cores = 4)

        assertEquals(CpuUsage.UNKNOWN, usage.sample(0L), "the first reading has no predecessor")

        cpu.nanos = second
        assertTrue(usage.sample(second) >= 0f, "the second should produce a figure")
    }

    @Test
    fun `one saturated core of four reads as a quarter of the machine`() {
        val cpu = FakeCpu()
        val usage = CpuUsage(cpu::read, cores = 4)
        usage.sample(0L)

        // One second of wall time during which the process burned exactly one core-second.
        cpu.nanos = second
        assertEquals(0.25f, usage.sample(second), 1e-4f)
    }

    @Test
    fun `all four cores saturated reads as the whole machine`() {
        val cpu = FakeCpu()
        val usage = CpuUsage(cpu::read, cores = 4)
        usage.sample(0L)

        cpu.nanos = 4 * second
        assertEquals(1f, usage.sample(second), 1e-4f)
    }

    @Test
    fun `an idle process reads as zero rather than as unknown`() {
        val cpu = FakeCpu()
        val usage = CpuUsage(cpu::read, cores = 8)
        usage.sample(0L)

        // Zero is a real answer once a window has elapsed, and must not be confused with the
        // "no reading yet" case that shares a display slot with it.
        assertEquals(0f, usage.sample(second), 1e-6f)
    }

    @Test
    fun `the counter is only read once per window, however often it is asked`() {
        var reads = 0
        val cpu = FakeCpu()
        val usage = CpuUsage({ reads++; cpu.read() }, cores = 4)
        usage.sample(0L)
        cpu.nanos = second
        val settled = usage.sample(second)

        val before = reads
        // Thirty repaints inside the same second, as the meter timer would produce.
        repeat(30) { tick ->
            assertEquals(settled, usage.sample(second + tick * 10_000_000L), 1e-6f)
        }
        // The counter is still read to decide whether the window has passed; what must not happen is
        // the window advancing, which would make each repaint measure a tenth of a second of noise.
        assertTrue(reads - before == 30, "one cheap read per call, and no new window")
    }

    @Test
    fun `a jumping counter cannot produce an impossible percentage`() {
        val cpu = FakeCpu()
        val usage = CpuUsage(cpu::read, cores = 2)
        usage.sample(0L)

        // Forty core-seconds claimed to have passed in one. Clamped rather than trusted: the two
        // clocks are read separately, and 2000 % would be read as a bug in the app rather than in
        // the counter.
        cpu.nanos = 40 * second
        assertEquals(1f, usage.sample(second), 1e-6f)
    }

    @Test
    fun `a jvm without the counter reports nothing, for ever`() {
        val usage = CpuUsage({ -1L }, cores = 4)
        assertEquals(CpuUsage.UNKNOWN, usage.sample(0L))
        assertEquals(CpuUsage.UNKNOWN, usage.sample(second))
        assertEquals(CpuUsage.UNKNOWN, usage.sample(10 * second))
    }

    @Test
    fun `reset makes the next reading start a fresh window`() {
        val cpu = FakeCpu()
        val usage = CpuUsage(cpu::read, cores = 4)
        usage.sample(0L)
        cpu.nanos = second
        assertEquals(0.25f, usage.sample(second), 1e-4f)

        usage.reset()
        // The application sat stopped for an hour. Without the reset that idle time would be
        // averaged into the first reading of the next run.
        cpu.nanos += 0
        assertEquals(CpuUsage.UNKNOWN, usage.sample(3600 * second), "the window has to start again")
    }

    @Test
    fun `the real counter is either a sensible figure or absent`() {
        // Not asserting a value — the machine running this is doing other things. Asserting only
        // that the platform path does not throw and does not invent something out of range.
        val usage = CpuUsage()
        usage.sample(System.nanoTime())
        val reading = usage.sample(System.nanoTime() + 2 * second)
        assertTrue(
            reading == CpuUsage.UNKNOWN || reading in 0f..1f,
            "expected a fraction or nothing, got $reading",
        )
    }
}
