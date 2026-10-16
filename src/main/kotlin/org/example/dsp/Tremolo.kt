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
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Amplitude modulation — the level swings up and down under an LFO.
 *
 * The simplest thing in the app, and deliberately the first FX type built: if the slot mechanism can
 * carry a tremolo through a type change, a preset round trip and a running stream, it can carry
 * anything, and none of that is obscured by interesting DSP.
 *
 * SHAPE morphs the LFO from a sine to something close to a square, which is the difference between
 * an amp's valve tremolo and the chopped, helicopter kind. It is a blend rather than a switch
 * because the interesting settings are between the two, and because a switch would step audibly
 * mid-note where a blend does not.
 *
 * The modulation is one-sided: full depth swings from unity down to silence and never above, so the
 * effect cannot make the signal louder than it found it.
 */
class Tremolo : AudioProcessor {

    override val name = "Tremolo"

    @Volatile
    override var enabled = false

    val rate = Parameter("Rate", 0.1f, 12f, 4f, "Hz", skew = 1.5f)
    val depth = Parameter("Depth", 0f, 1f, 0.5f)
    val shape = Parameter("Shape", 0f, 1f, 0f)

    override val parameters = listOf(rate, depth, shape)

    private var sampleRate = DEFAULT_SAMPLE_RATE

    /** LFO position in cycles, 0..1. Kept as a fraction so the rate knob converts once per block. */
    private var phase = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
    }

    override fun process(buffer: FloatArray, count: Int) {
        val increment = rate.value / sampleRate
        val d = depth.value
        val s = shape.value
        val squareness = s > 0f

        var p = phase
        for (i in 0 until count) {
            val sine = sin(TWO_PI * p)
            // Blended rather than switched: shape 0 is an exact sine, shape 1 is a soft square, and
            // everything between is a real setting rather than a crossfade artefact.
            val wave = if (squareness) sine + (tanh(sine * SQUARE_DRIVE) * SQUARE_NORMALISE - sine) * s else sine
            // 0..1, so the gain runs from unity down to (1 - depth) and never above unity.
            val modulation = 0.5f + 0.5f * wave
            buffer[i] *= 1f - d + d * modulation

            p += increment
            if (p >= 1f) p -= 1f
        }
        phase = p
    }

    override fun reset() {
        // Starts at zero crossing rising, so the first note is not clipped by an LFO that happened to
        // be at the bottom of its swing when the stream opened.
        phase = 0f
    }

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()

        /** How hard the sine is driven into `tanh` to square it off. */
        const val SQUARE_DRIVE = 8f

        /** 1 / tanh(SQUARE_DRIVE), so the squared wave still peaks at exactly ±1. */
        const val SQUARE_NORMALISE = 1.0000002f
    }
}
