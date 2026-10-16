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

import kotlin.math.exp
import kotlin.math.pow

/** Clean level trim. Smoothed per-sample so knob moves don't produce zipper noise. */
class Gain : AudioProcessor {

    override val name = "Gain"

    @Volatile
    override var enabled = true

    val gainDb = Parameter("Gain", -24f, 24f, 0f, "dB")

    override val parameters = listOf(gainDb)

    private var smoothing = smoothingFor(DEFAULT_SAMPLE_RATE)
    private var current = 1f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        smoothing = smoothingFor(sampleRate)
    }

    override fun process(buffer: FloatArray, count: Int) {
        val target = 10f.pow(gainDb.value / 20f)
        val s = smoothing
        var g = current
        for (i in 0 until count) {
            g += (target - g) * s
            buffer[i] *= g
        }
        current = g
    }

    override fun reset() {
        current = 10f.pow(gainDb.value / 20f)
    }

    private companion object {
        /**
         * ~11 ms time constant, held in seconds rather than as a per-sample coefficient so the ramp
         * takes the same *time* on every device instead of getting twice as fast at 96 kHz.
         */
        const val SMOOTHING_SECONDS = 500f / 44_100f

        fun smoothingFor(sampleRate: Float): Float = 1f - exp(-1f / (sampleRate * SMOOTHING_SECONDS))
    }
}
