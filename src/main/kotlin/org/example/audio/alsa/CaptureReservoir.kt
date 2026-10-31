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
 * The converted input, waiting for the output's clock — what lets [AlsaStream] convert at all.
 *
 * The lockstep loop reads one period and writes one period, and a converter cannot feed that: 128
 * frames at 44.1 kHz are 139 or 140 at 48 kHz, never the same twice running, and the two devices
 * are on two crystals besides. So when the input is converted the output drives the loop, and this
 * sits between: whatever the input has produced since the last period goes in, exactly one period
 * comes out.
 *
 * **The level is kept in a window, not at a point.** Arrivals come in lumps of up to [chunk] frames
 * — one capture period, converted — so the level before a take wanders by that much with no drift at
 * all. Inside the window nothing happens. Below it, one frame is held back (the period's last sample
 * repeats); above it, one is skipped. One frame a period is thousands of ppm of authority against a
 * real drift of tens, and inaudible where a dropped period would not be — the same correction the
 * WASAPI render loop makes on its ring.
 *
 * Single-threaded: the stream's own thread both fills and drains it, so the indices are plain.
 * Nothing allocates after construction.
 */
internal class CaptureReservoir(
    private val period: Int,
    /** The most frames one capture period can become after conversion. */
    chunk: Int,
) {

    /**
     * The take that would leave less than [chunk] behind for the next period's shortfall. Below
     * it, hold back.
     */
    val low: Int = period + chunk

    /** Wide enough that a capture period's lump alone never trips a correction. */
    val high: Int = low + chunk + period / 2

    private val ring = FloatArray(Integer.highestOneBit((high + period) * 4 - 1) shl 1)
    private val mask = ring.size - 1
    private var write = 0
    private var read = 0
    private var primed = false

    /** Frames held back or skipped to keep the level in the window. */
    var corrections = 0
        private set

    /** Converted frames dropped because the ring was full. Should stay zero. */
    var overflows = 0
        private set

    /** Frames waiting. Plain subtraction survives the indices wrapping. */
    val level: Int get() = write - read

    /**
     * The delay this adds on average, in frames at the output's rate: the frames still waiting when
     * a period leaves, at the middle of the window. Declared in `adapterLatencyFrames`.
     */
    val latencyFrames: Int get() = (low + high) / 2 - period

    fun push(source: FloatArray, count: Int) {
        for (i in 0 until count) {
            if (write - read == ring.size) {
                // Drop the newest rather than overwrite unread frames.
                overflows++
                continue
            }
            ring[write and mask] = source[i]
            write++
        }
    }

    /**
     * Fills [target] with exactly one period.
     *
     * Returns false on an underrun — a period that had to be silence after the stream was running.
     * The silence before the reservoir first reaches [low] is start-up, not an underrun, and is not
     * counted: reporting a dropout on every start would teach the user to ignore the counter.
     */
    fun take(target: FloatArray): Boolean {
        val available = write - read
        if (!primed) {
            if (available < low) {
                java.util.Arrays.fill(target, 0, period, 0f)
                return true
            }
            primed = true
        }
        if (available < period) {
            java.util.Arrays.fill(target, 0, period, 0f)
            // Fill back up to the window before playing again, rather than limping a period at a time.
            primed = false
            return false
        }
        if (available > high) {
            read++
            corrections++
        }
        if (available < low) {
            for (i in 0 until period - 1) target[i] = ring[read++ and mask]
            target[period - 1] = target[period - 2]
            corrections++
        } else {
            for (i in 0 until period) target[i] = ring[read++ and mask]
        }
        return true
    }

    fun reset() {
        write = 0
        read = 0
        primed = false
    }
}
