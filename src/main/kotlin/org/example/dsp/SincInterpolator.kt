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
package org.example.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads a signal at any fractional position, for a resampler whose ratio moves while it runs (2.3.0).
 *
 * [Resampler] converts between two fixed rates and is exact at that, but a fixed ratio leaves the
 * drift between two real crystals to someone else — and until 2.3.0 that someone deleted or
 * repeated single samples, which a drive turns into grit. Reading at a fractional position instead
 * lets the ratio follow the drift by parts per million, and nothing is ever spliced.
 *
 * The kernel is the same Kaiser-windowed sinc as [Resampler]'s, [TAPS] long, cut so its stopband
 * starts at the lower of the two Nyquist limits, tabulated at [PHASES] fractional offsets and
 * interpolated linearly between neighbours — which costs about 110 dB, well under the window's 80.
 *
 * Works on a power-of-two ring so the caller never copies: [at] reads `buffer[(start + j) and mask]`.
 * No allocation after construction.
 */
class SincInterpolator(inputRate: Int, outputRate: Int) {

    /** Rows `0..PHASES`, each [TAPS] long; row `p` is the kernel at fractional offset `p / PHASES`. */
    private val table = FloatArray((PHASES + 1) * TAPS)

    init {
        require(inputRate > 0 && outputRate > 0) { "rates must be positive" }
        val transition = KAISER_TRANSITION * inputRate / TAPS
        val nyquist = min(inputRate, outputRate) / 2.0
        // In cycles per input sample. Never let a pathological ratio push it to nothing.
        val cutoff = ((nyquist - transition / 2.0) / inputRate).coerceAtLeast(0.25 * nyquist / inputRate)
        val bessel = besselI0(KAISER_BETA)
        for (p in 0..PHASES) {
            val frac = p.toDouble() / PHASES
            var sum = 0.0
            val row = DoubleArray(TAPS)
            for (j in 0 until TAPS) {
                // Distance from the output point to input sample `start + j`.
                val x = j - (HALF - 1) - frac
                val ideal = if (abs(x) < 1e-12) 2.0 * cutoff else sin(2.0 * PI * cutoff * x) / (PI * x)
                val r = x / HALF
                val window = if (abs(r) >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1.0 - r * r)) / bessel
                row[j] = ideal * window
                sum += row[j]
            }
            // Unity at DC for every offset, so a level never moves with the fraction.
            for (j in 0 until TAPS) table[p * TAPS + j] = (row[j] / sum).toFloat()
        }
    }

    /**
     * The signal at `start + HALF - 1 + frac`, reading [TAPS] samples from `buffer[(start) and mask]`
     * onwards. [frac] is in `[0, 1)`.
     */
    fun at(buffer: FloatArray, mask: Int, start: Int, frac: Double): Float {
        val f = frac * PHASES
        var p = f.toInt()
        if (p >= PHASES) p = PHASES - 1
        val a = (f - p).toFloat()
        val row0 = p * TAPS
        val row1 = row0 + TAPS
        val t = table
        var s0 = 0f
        var s1 = 0f
        for (j in 0 until TAPS) {
            val x = buffer[(start + j) and mask]
            s0 += t[row0 + j] * x
            s1 += t[row1 + j] * x
        }
        return s0 + (s1 - s0) * a
    }

    companion object {
        /** Kernel length in input samples. 64, as [Resampler.DEFAULT_TAPS], for the same reasons. */
        const val TAPS = 64

        /** Samples the kernel reaches either side of the point read. Its delay, in input frames. */
        const val HALF = TAPS / 2

        /** Fractional offsets tabulated. 256 with linear interpolation is far below the window's floor. */
        const val PHASES = 256

        /** Kaiser beta for roughly 80 dB of stopband, as in [Resampler]. */
        private const val KAISER_BETA = 7.86

        /** Transition width as a multiple of `rate / taps`, for that beta. */
        private const val KAISER_TRANSITION = 5.02

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 64) {
                val half = x / 2.0 / k
                term *= half * half
                sum += term
                if (term < 1e-14 * sum) break
                k++
            }
            return sum
        }
    }
}
