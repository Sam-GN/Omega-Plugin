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
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Soft-clipping overdrive.
 *
 * `tanh` gives a smooth, asymptotic knee rather than the harsh corners of hard clipping, which is
 * what makes it sound like a driven tube stage instead of digital wrap-around. The output is
 * normalised by `1/tanh(drive)` so cranking drive adds harmonics without also adding raw level, and
 * a one-pole lowpass afterwards tames the fizz the clipper generates up top.
 *
 * The lowpass coefficient is derived from the device rate: baked in at 44.1 kHz, the tone control
 * would land roughly 9 % high on a 48 kHz device and be wrong by an octave at 96 kHz.
 */
class Overdrive : AudioProcessor {

    override val name = "Overdrive"

    @Volatile
    override var enabled = false

    val drive = Parameter("Drive", 1f, 50f, 8f, "x", skew = 2f)
    val tone = Parameter("Tone", 200f, 8000f, 3000f, "Hz", skew = 2f)
    val level = Parameter("Level", 0f, 1f, 0.7f)

    override val parameters = listOf(drive, tone, level)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var lowpass = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
    }

    override fun process(buffer: FloatArray, count: Int) {
        val d = drive.value
        val normalise = 1f / tanh(d)
        val a = lowpassCoefficient(tone.value)
        val lvl = level.value

        var lp = lowpass
        for (i in 0 until count) {
            val clipped = tanh(buffer[i] * d) * normalise
            lp += a * (clipped - lp)
            buffer[i] = lp * lvl
        }
        lowpass = lp
    }

    override fun reset() {
        lowpass = 0f
    }

    private fun lowpassCoefficient(cutoffHz: Float): Float =
        (1f - exp(-2f * PI.toFloat() * cutoffHz / sampleRate)).coerceIn(0f, 1f)
}
