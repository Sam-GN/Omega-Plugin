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

/**
 * Every way an ALSA period can go wrong, counted with its cause (2.3.2).
 *
 * An xrun is only counted when ALSA returns one, and PipeWire's plugin may run dry without: a player
 * heard more dropouts at 44.1 kHz, where the xrun count read 0, than at 48, where it read 2. So the
 * loop also looks for itself: a period that took longer than it lasts, and whether the thread was
 * *running* for that time or was kept off the CPU; and an output queue found with nothing in it.
 *
 * **An empty queue is a suspect, not a verdict.** A server takes audio in lumps and may leave exactly
 * nothing for a moment without a gap. It is logged with its time and kept out of the xrun count.
 *
 * Written by the stream thread, read by the monitor. Allocates nothing.
 */
internal class DropoutCounters(private val periodNanos: Long) {

    /** Periods longer than they last, mostly spent running: the pedals cost too much. */
    @Volatile var slow = 0
        private set

    /** Periods longer than they last, mostly spent not running: something else had the CPU. */
    @Volatile var stalled = 0
        private set

    /** Times the output queue had nothing in it when the next period was ready, after a late one. */
    @Volatile var emptyAfterLate = 0
        private set

    /** The same after a period that was on time: the input, the wake-up or the server was late. */
    @Volatile var emptyOther = 0
        private set

    /** The least the output had queued when a period was ready, in frames. */
    @Volatile var lowestQueued = Long.MAX_VALUE
        private set

    /** The longest time between two periods being written. */
    @Volatile var longestGapNanos = 0L
        private set

    private var lastWritten = 0L

    /** A period took [wallNanos], of which the thread ran [cpuNanos] (negative: not known). */
    fun period(wallNanos: Long, cpuNanos: Long, queued: Long) {
        val late = wallNanos > periodNanos
        if (late) {
            if (cpuNanos < 0 || cpuNanos * 10 >= wallNanos * 8) slow++ else stalled++
        }
        if (queued < lowestQueued) lowestQueued = queued
        if (queued <= 0) {
            if (late) emptyAfterLate++ else emptyOther++
        }
    }

    fun written(nanos: Long) {
        if (lastWritten != 0L && nanos - lastWritten > longestGapNanos) longestGapNanos = nanos - lastWritten
        lastWritten = nanos
    }

    /** The stream restarted: the pause is not a gap between periods. */
    fun restart() {
        lastWritten = 0L
    }

    /** Moves whenever anything above does, so a monitor can compare one number. */
    val total: Int get() = slow + stalled + emptyAfterLate + emptyOther

    fun describe(rate: Int): String {
        val lowest = lowestQueued
        return "late periods: $slow slow, $stalled kept off the CPU; output found empty: $emptyAfterLate after a late " +
            "period, $emptyOther otherwise; least queued " +
            (if (lowest == Long.MAX_VALUE) "unknown" else "%.1f ms".format(lowest * 1000.0 / rate)) +
            ", longest gap between periods ${"%.1f".format(longestGapNanos / 1e6)} ms"
    }
}
