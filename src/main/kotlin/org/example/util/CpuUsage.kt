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

import java.lang.management.ManagementFactory

/**
 * What share of the machine this process is using — the number Task Manager shows.
 *
 * Deliberately **not** the same thing as `AudioEngine.loadPercent`, which the status bar reports
 * beside it. That one is the share of a *callback period* the chain consumes and is what predicts a
 * dropout. This one is the share of *every core* the whole JVM consumes, audio thread and event
 * thread and collector together. On a twelve-core machine a chain eating half its deadline is about
 * four per cent of the machine, so the two legitimately differ by an order of magnitude and the
 * labels have to carry that.
 *
 * Computed from deltas rather than from `getProcessCpuLoad`, for two reasons: the averaging window
 * is then this class's business rather than the JVM's, and a counter that only ever goes up is
 * trivial to test against a fake clock.
 *
 * **Cheap.** Reading the counter is one kernel call — `GetProcessTimes` on Windows — in the
 * single-digit microseconds, and [sample] only takes it once a second however often it is asked.
 * Between samples it returns the value it already had, so a caller repainting at 30 Hz costs nothing
 * beyond a comparison.
 */
class CpuUsage(
    private val processCpuNanos: () -> Long = ::systemProcessCpuNanos,
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
) {

    private var lastCpuNanos = -1L
    private var lastWallNanos = 0L
    private var fraction = UNKNOWN

    /**
     * The share of the machine in use, 0 to 1, or [UNKNOWN] while that cannot be said.
     *
     * Unknown covers two real cases and both must show as *nothing* rather than as zero: a JVM
     * without the counter at all, and the moment before a second sample exists to subtract from the
     * first. A CPU readout that says 0 % on a machine doing work is worse than one that says
     * nothing, because it looks like an answer.
     */
    fun sample(nowNanos: Long): Float {
        val cpu = processCpuNanos()
        if (cpu < 0L) return UNKNOWN

        if (lastCpuNanos < 0L) {
            lastCpuNanos = cpu
            lastWallNanos = nowNanos
            return UNKNOWN
        }

        val wallDelta = nowNanos - lastWallNanos
        if (wallDelta < MIN_INTERVAL_NANOS) return fraction

        val cpuDelta = cpu - lastCpuNanos
        lastCpuNanos = cpu
        lastWallNanos = nowNanos
        // Clamped rather than trusted. The two clocks are read separately and a counter can jump —
        // over a whole second that is invisible, but a readout of 4000 % would not be.
        fraction = (cpuDelta.toDouble() / (wallDelta.toDouble() * cores)).toFloat().coerceIn(0f, 1f)
        return fraction
    }

    /**
     * Forgets the last sample.
     *
     * Called when the stream stops, so that starting again does not average the new run against
     * however long the application sat idle in between.
     */
    fun reset() {
        lastCpuNanos = -1L
        lastWallNanos = 0L
        fraction = UNKNOWN
    }

    companion object {
        /** Negative, so it cannot be mistaken for a real reading. */
        const val UNKNOWN = -1f

        /**
         * How often the counter is actually read.
         *
         * A second is long enough that one syscall is free and short enough to follow a chain being
         * switched on. Shorter windows also read badly: process CPU over a tenth of a second on a
         * machine that schedules in milliseconds is mostly quantisation noise.
         */
        const val MIN_INTERVAL_NANOS = 1_000_000_000L

        /**
         * The JVM's own counter, or -1 where there is not one.
         *
         * `com.sun.management.OperatingSystemMXBean` is HotSpot's extension rather than a standard
         * interface, so the cast is guarded: this ships on Temurin and has it, and a JVM that does
         * not should cost the readout and nothing else. Resolved once — the lookup is reflective
         * underneath and this is called every second for the life of the process.
         */
        private val bean: com.sun.management.OperatingSystemMXBean? = runCatching {
            ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        }.getOrNull()

        fun systemProcessCpuNanos(): Long = bean?.processCpuTime ?: -1L
    }
}
