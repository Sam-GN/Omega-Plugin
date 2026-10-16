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
import kotlin.math.pow

/**
 * Noise gate — silence between notes, and nothing else.
 *
 * The hold is what makes it usable on guitar. Without it the gate slams shut in the dip between two
 * cycles of a decaying note and chatters; holding it open for a set time after the level last
 * crossed the threshold rides through those dips.
 *
 * Placed first in the chain by default, because gating *after* a distortion means gating the noise
 * the distortion just amplified, which is far harder.
 */
class Gate : AudioProcessor {

    override val name = "Gate"

    @Volatile
    override var enabled = false

    val threshold = Parameter("Thresh", -80f, 0f, -45f, "dB")
    val attack = Parameter("Attack", 0.1f, 50f, 2f, "ms", skew = 2f)
    val hold = Parameter("Hold", 0f, 500f, 60f, "ms", skew = 1.5f)
    val release = Parameter("Release", 5f, 1000f, 150f, "ms", skew = 2f)

    override val parameters = listOf(threshold, attack, hold, release)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var envelope = 0f
    private var gain = 0f
    private var holdRemaining = 0

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
    }

    override fun process(buffer: FloatArray, count: Int) {
        val thresholdLinear = 10f.pow(threshold.value / 20f)
        val opening = coefficient(attack.value)
        val closing = coefficient(release.value)
        val holdSamples = ((hold.value / 1000f) * sampleRate).toInt()
        // Detector follows the peak quickly and falls at the release rate, so a note's decay is
        // tracked rather than chased.
        val detectorFall = coefficient(release.value)

        var env = envelope
        var g = gain
        var remaining = holdRemaining

        for (i in 0 until count) {
            val x = buffer[i]
            val rectified = abs(x)
            env = if (rectified > env) rectified else rectified + detectorFall * (env - rectified)

            val open = if (env > thresholdLinear) {
                remaining = holdSamples
                true
            } else {
                if (remaining > 0) remaining--
                remaining > 0
            }

            val target = if (open) 1f else 0f
            val coefficient = if (target > g) opening else closing
            g = target + coefficient * (g - target)

            buffer[i] = x * g
        }

        envelope = env
        gain = g
        holdRemaining = remaining
    }

    override fun reset() {
        envelope = 0f
        // Starts closed: a gate that opens on the first block would pass exactly the noise it exists
        // to remove, at the moment the stream starts.
        gain = 0f
        holdRemaining = 0
    }

    private fun coefficient(milliseconds: Float): Float =
        exp(-1f / ((milliseconds / 1000f) * sampleRate).coerceAtLeast(1f))
}
