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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Feed-forward peak compressor.
 *
 * A peak detector rather than RMS: on guitar the thing worth catching is the pick attack, and RMS
 * averages exactly that away.
 *
 * The gain law is evaluated in the log domain — above the threshold the signal is pushed back down
 * by `(1 - 1/ratio)` of however far over it went — but only *when* it is over. Below the threshold
 * the gain is exactly one and no logarithm is taken, which keeps the common case free.
 */
class Compressor : AudioProcessor {

    override val name = "Compressor"

    @Volatile
    override var enabled = false

    val threshold = Parameter("Thresh", -48f, 0f, -18f, "dB")
    val ratio = Parameter("Ratio", 1f, 20f, 4f, skew = 1.6f)
    val attack = Parameter("Attack", 0.5f, 100f, 10f, "ms", skew = 2f)
    val release = Parameter("Release", 10f, 1000f, 120f, "ms", skew = 2f)
    val makeup = Parameter("Makeup", 0f, 24f, 0f, "dB")

    override val parameters = listOf(threshold, ratio, attack, release, makeup)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var envelope = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
    }

    override fun process(buffer: FloatArray, count: Int) {
        val thresholdLinear = 10f.pow(threshold.value / 20f)
        // How much of the excess to give back. At a ratio of 1 this is 0 and the compressor is a
        // wire, which is what a ratio of 1 means.
        val slope = 1f - 1f / ratio.value
        val attackCoefficient = coefficient(attack.value)
        val releaseCoefficient = coefficient(release.value)
        val makeupLinear = 10f.pow(makeup.value / 20f)

        var env = envelope
        for (i in 0 until count) {
            val x = buffer[i]
            val rectified = abs(x)
            // Rise fast, fall slow: the coefficient chosen depends on which way the level is going.
            val coefficient = if (rectified > env) attackCoefficient else releaseCoefficient
            env = rectified + coefficient * (env - rectified)

            val gain = if (env > thresholdLinear) {
                exp(-slope * ln(env / thresholdLinear))
            } else {
                1f
            }
            buffer[i] = x * gain * makeupLinear
        }
        envelope = env
    }

    override fun reset() {
        envelope = 0f
    }

    /** One-pole coefficient for a time constant in milliseconds. */
    private fun coefficient(milliseconds: Float): Float =
        exp(-1f / ((milliseconds / 1000f) * sampleRate).coerceAtLeast(1f))
}
