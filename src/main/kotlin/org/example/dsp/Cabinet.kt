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

import kotlin.math.pow

/**
 * A synthesised speaker cabinet: the broad shape of one, as a fixed arrangement of filters.
 *
 * A guitar speaker in a box is, to a first approximation, an EQ curve, and the parts of that curve
 * are well known: a steep cut below the cone's resonance, a low-mid bump that is the box, a peak in
 * the presence region where the cone breaks up, and a very steep roll-off above a few kilohertz —
 * the single biggest thing a cabinet does, and the reason an amp without one sounds like a wasp.
 * Five biquads, in that order. Cheaper than the convolution a loaded impulse costs, and it needs no
 * file, which is the point of it.
 *
 * **Mic** is the one adjustment people make on a real cabinet: from off-axis, darker with less bite,
 * to on-axis, brighter with the presence peak fully in. It tilts the roll-off and scales the peak,
 * which turns one fixed curve into a usable range without pretending to be a microphone simulator.
 *
 * What it does not have is the comb-filter detail of a microphone in front of a cone in a room,
 * which is what makes one impulse response sound like a recording and another like a different
 * one. A loaded impulse wins; this is for having a cabinet when there is none.
 */
class CabinetVoice(private val spec: Spec) : AudioProcessor {

    /** The fixed curve of one cabinet. Frequencies in hertz, gains in decibels. */
    class Spec(
        val lowCutHz: Float,
        val thumpHz: Float,
        val thumpDb: Float,
        val thumpQ: Float,
        val presenceHz: Float,
        val presenceDb: Float,
        val rollHz: Float,
    )

    override val name = "Cabinet"

    @Volatile
    override var enabled = true

    val mic = Parameter("Mic", 0f, 1f, 0.5f, ends = "OFF-AXIS" to "ON-AXIS")
    val level = Parameter("Level", -24f, 24f, 0f, "dB")

    override val parameters = listOf(mic, level)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private val lowCut = Biquad()
    private val thump = Biquad()
    private val presence = Biquad()
    private val rollA = Biquad()
    private val rollB = Biquad()

    /** The mic position the coefficients were last computed for; NaN forces the first computation. */
    private var builtFor = Float.NaN

    init {
        design(DEFAULT_SAMPLE_RATE, mic.value)
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        design(sampleRate, mic.value)
    }

    /**
     * The fixed filters, and the two the mic moves. Recomputed only when the knob has moved: a few
     * transcendentals per block when it does, nothing when it has not.
     */
    private fun design(rate: Float, micPosition: Float) {
        lowCut.highPass(rate, spec.lowCutHz, BUTTERWORTH_Q)
        thump.peaking(rate, spec.thumpHz, spec.thumpDb, spec.thumpQ)
        // Off-axis keeps a third of the peak and pulls the roll-off down by a quarter; on-axis has
        // the whole peak and a roll-off a quarter higher. Linear between.
        presence.peaking(rate, spec.presenceHz, spec.presenceDb * (0.3f + 0.7f * micPosition), PRESENCE_Q)
        val roll = spec.rollHz * (0.75f + 0.5f * micPosition)
        // Two second-order sections at the Butterworth Q pair make a maximally flat fourth order:
        // 24 dB per octave, which is roughly what a cone does above its break-up.
        rollA.lowPass(rate, roll, BUTTERWORTH_4_QA)
        rollB.lowPass(rate, roll, BUTTERWORTH_4_QB)
        builtFor = micPosition
    }

    override fun process(buffer: FloatArray, count: Int) {
        val position = mic.value
        if (position != builtFor) design(sampleRate, position)
        val gain = 10f.pow(level.value / 20f)
        val a = lowCut
        val b = thump
        val c = presence
        val d = rollA
        val e = rollB
        for (i in 0 until count) {
            buffer[i] = e.process(d.process(c.process(b.process(a.process(buffer[i]))))) * gain
        }
    }

    override fun reset() {
        lowCut.reset()
        thump.reset()
        presence.reset()
        rollA.reset()
        rollB.reset()
    }

    companion object {
        private const val BUTTERWORTH_Q = 0.7071f
        private const val BUTTERWORTH_4_QA = 0.5412f
        private const val BUTTERWORTH_4_QB = 1.3066f
        private const val PRESENCE_Q = 1.2f

        /** A combo: lighter low end, bright, roll-off near 6 kHz. */
        val OPEN_1X12 = Spec(
            lowCutHz = 90f, thumpHz = 160f, thumpDb = 3f, thumpQ = 1.0f,
            presenceHz = 3000f, presenceDb = 4f, rollHz = 6000f,
        )

        /** A bigger combo: more low-mid, the same brightness. */
        val OPEN_2X12 = Spec(
            lowCutHz = 80f, thumpHz = 140f, thumpDb = 4f, thumpQ = 1.0f,
            presenceHz = 2800f, presenceDb = 4f, rollHz = 5800f,
        )

        /** A stack: tight, thick low end, dark, roll-off near 4.5 kHz. */
        val CLOSED_4X12 = Spec(
            lowCutHz = 70f, thumpHz = 120f, thumpDb = 5f, thumpQ = 0.9f,
            presenceHz = 2500f, presenceDb = 3f, rollHz = 4500f,
        )

        /** A practice amp: thin below 150 Hz, boxy mids. */
        val SMALL_1X10 = Spec(
            lowCutHz = 150f, thumpHz = 250f, thumpDb = 4f, thumpQ = 1.5f,
            presenceHz = 3500f, presenceDb = 4f, rollHz = 5500f,
        )
    }
}
