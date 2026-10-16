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

import kotlin.math.max

/**
 * Keeps the playback queue away from both ends of the line buffer when capture and playback run off
 * different clocks.
 *
 * Two devices means two crystals, and nothing couples them. Reading exactly N frames and writing
 * exactly N frames forever therefore lets the amount of audio queued at the DAC wander, over minutes
 * or hours, until it hits empty (a dropout) or full (a blocking write that stalls the audio thread).
 *
 * ### Why it is a guard rail and not a setpoint
 *
 * Where the queue sits is not ours to choose. Measured on Windows/DirectSound it settles anywhere
 * from 70 ms to 130 ms depending on the pairing and on how burstily the capture line delivers, and
 * priming less does not move it — the backend keeps a lead of its own ahead of the play cursor.
 * An earlier version of this class regulated towards a target; it could never reach one, and spent
 * the whole session correcting at its rate limit (measured: 39 frames/s, permanently). So this one
 * has no target. It watches for the queue approaching [lowWater] or [highWater] and does nothing at
 * all in between, which for a healthy pairing is always.
 *
 * The correction is one frame added or removed at the quietest point in the block — a splice of
 * 23 microseconds where the signal is closest to zero — at most once every [INTERVAL_BLOCKS] blocks.
 * That is roughly 975 ppm of authority against real-world crystal drift measured in tens of ppm.
 * Deciding on a heavily smoothed level rather than the instantaneous one keeps ordinary scheduling
 * jitter from tripping it.
 *
 * Runs on the audio thread: no allocation, no locks, nothing that can block.
 */
internal class CushionRegulator(
    capacityFrames: Int,
    private val enabled: Boolean = true,
) {

    /** Below this the next scheduling hiccup is a dropout. */
    private val lowWater = max(4 * AudioConfig.BLOCK_FRAMES, capacityFrames / 8)

    /** Above this `write` starts blocking, which stalls the audio thread — worse than a splice. */
    private val highWater = capacityFrames - max(6 * AudioConfig.BLOCK_FRAMES, capacityFrames / 8)

    private var smoothed = 0f
    private var blocks = 0L

    /** Frames added or dropped so far. Surfaced by the engine for diagnostics. */
    var corrections: Int = 0
        private set

    /** Smoothed queue level in frames — what the regulator is acting on. */
    val smoothedFrames: Float get() = smoothed

    /** False while the backend's reported level is still meaningless, just after `start()`. */
    val isArmed: Boolean get() = blocks > WARMUP_BLOCKS

    /** Called after the engine re-primes following an underrun, so the average is not poisoned. */
    fun resettle() {
        blocks = 0
        smoothed = 0f
    }

    /**
     * Notes the current playback queue [fill] and, if it is running out of room at either end,
     * splices [samples] at [quietest]. [samples] must have room for one frame beyond [count].
     *
     * @return the frames the engine should write: [count], [count] - 1 or [count] + 1.
     */
    fun regulate(fill: Int, samples: FloatArray, count: Int, quietest: Int): Int {
        blocks++

        // The backend reports a free buffer until the play cursor is live, so the first fraction of
        // a second says nothing about the steady state.
        if (blocks <= WARMUP_BLOCKS) {
            smoothed = fill.toFloat()
            return count
        }
        smoothed += (fill - smoothed) * SMOOTHING

        if (!enabled || count < 2 || blocks % INTERVAL_BLOCKS != 0L) return count
        val splice = quietest.coerceIn(0, count - 2)
        return when {
            // Queue filling towards a blocking write: lose a frame.
            smoothed > highWater -> {
                System.arraycopy(samples, splice + 1, samples, splice, count - splice - 1)
                corrections++
                count - 1
            }
            // Queue draining towards empty: hold a frame twice.
            smoothed < lowWater -> {
                System.arraycopy(samples, splice, samples, splice + 1, count - splice)
                corrections++
                count + 1
            }
            else -> count
        }
    }

    private companion object {
        /** ~5 s time constant at 44.1 kHz / 256-frame blocks. */
        const val SMOOTHING = 0.0012f

        /** One frame per four blocks caps the correction rate at ~43 frames/s (~975 ppm). */
        const val INTERVAL_BLOCKS = 4L

        /** ~0.4 s: long enough for the play cursor to start moving on every backend tried. */
        const val WARMUP_BLOCKS = 64L
    }
}
