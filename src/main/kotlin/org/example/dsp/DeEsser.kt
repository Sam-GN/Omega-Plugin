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
 * A compressor that only hears, and only touches, one band.
 *
 * Named for what it does on voice, but on this instrument the target is finger and nail noise —
 * string squeak on a position shift, the scrape before a note. Both have the same shape as sibilance
 * and want the same answer: they live in a narrow band, they are *intermittent*, and a static EQ cut
 * that removed them would cost the whole top end of the instrument for the ninety-five per cent of
 * the time they are not happening.
 *
 * Neither of the app's existing dynamics modules can do this. [Compressor] is broadband — it hears
 * the note and ducks everything. [Gate] is frequency-blind and binary. Nothing here was both
 * frequency-selective and level-dependent until this.
 *
 * ### Two filters, doing two different jobs
 *
 * **The audio path splits with a one-pole**, and the band is the remainder: `band = input - low`.
 * A one-pole low-pass and its remainder sum back to the input *exactly* — `1/(1+s) + s/(1+s)` is
 * `1` at every frequency, in phase as well as magnitude — so at unity gain this module is bit
 * transparent, which is what it is doing for the ninety-five per cent of the time nothing is
 * squeaking.
 *
 * Steeper filters do not have that property and the failure is not subtle. A second-order high-pass
 * shifts phase hard around its corner, so `input - band` still contains most of the tone rather than
 * none of it: measured at 1.6 times the corner, the leftover was 0.9 of the input, and ducking the
 * band by 12 dB moved the total by under two. A de-esser that cannot reduce what it is pointed at is
 * worse than no de-esser, because it looks like it is working.
 *
 * **The detector is a second-order high-pass**, and here the steepness is exactly what is wanted:
 * it decides *whether* to act and never appears in the output, so it can be as selective as it
 * likes without owing anything to reconstruction. A gentle detector would trigger on midrange the
 * module has no intention of touching.
 */
class DeEsser : AudioProcessor {

    override val name = "De-Ess"

    @Volatile
    override var enabled = false

    val frequency = Parameter("Freq", 2000f, 12000f, 5000f, "Hz", skew = 2f)
    val threshold = Parameter("Thresh", -60f, 0f, -30f, "dB")
    val range = Parameter("Range", 0f, 24f, 8f, "dB")

    override val parameters = listOf(frequency, threshold, range)

    private var sampleRate = DEFAULT_SAMPLE_RATE

    /** Second order, and heard only by the envelope follower. */
    private val detector = Biquad()

    /** One pole, and the only filter in the audio path. `low + (input - low)` is the input. */
    private var low = 0f
    private var splitCoefficient = 0f

    private var envelope = 0f
    private var attackCoefficient = 0f
    private var releaseCoefficient = 0f
    private var designedFrequency = Float.NaN
    private var designedRate = 0f

    init {
        recomputeCoefficients()
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        recomputeCoefficients()
        designedRate = 0f
    }

    override fun process(buffer: FloatArray, count: Int) {
        val rate = sampleRate
        if (designedFrequency != frequency.value || designedRate != rate) {
            designedFrequency = frequency.value
            designedRate = rate
            detector.highPass(rate, designedFrequency, DETECTOR_Q)
            splitCoefficient = onePoleCoefficient(designedFrequency, rate)
        }

        val thresholdLinear = 10f.pow(threshold.value / 20f)
        // The most the band may ever be pushed down. Without it, a loud squeak against a low
        // threshold would duck the band into silence and the cure would be louder than the disease.
        val floorGain = 10f.pow(-range.value / 20f)
        val up = attackCoefficient
        val down = releaseCoefficient
        val sidechain = detector
        val split = splitCoefficient

        var env = envelope
        var lp = low

        for (i in 0 until count) {
            val x = buffer[i]

            val rectified = abs(sidechain.process(x))
            val coefficient = if (rectified > env) up else down
            env = rectified + coefficient * (env - rectified)

            // Only over the threshold is any logarithm taken, so the quiet case — which is most of
            // the signal, most of the time — costs a comparison. Same shape as Compressor's law.
            val gain = if (env > thresholdLinear) {
                val reduced = exp(-SLOPE * ln(env / thresholdLinear))
                if (reduced < floorGain) floorGain else reduced
            } else {
                1f
            }

            lp += split * (x - lp)
            // At a gain of one this is `lp + (x - lp)`, which is x, in float arithmetic as well as
            // in algebra. That exactness is the whole reason the split is a one-pole.
            buffer[i] = lp + (x - lp) * gain
        }

        envelope = env
        low = lp
    }

    override fun reset() {
        detector.reset()
        envelope = 0f
        low = 0f
    }

    /** One-pole coefficient for a -3 dB corner at [frequencyHz], clamped short of instability. */
    private fun onePoleCoefficient(frequencyHz: Float, rate: Float): Float =
        (1f - exp(-2f * Math.PI.toFloat() * frequencyHz / rate)).coerceIn(0f, 1f)

    private fun recomputeCoefficients() {
        attackCoefficient = coefficient(ATTACK_MS)
        releaseCoefficient = coefficient(RELEASE_MS)
    }

    private fun coefficient(milliseconds: Float): Float =
        exp(-1f / ((milliseconds / 1000f) * sampleRate).coerceAtLeast(1f))

    private companion object {
        /** Maximally flat: the detector should decide by level, not by where within the band. */
        const val DETECTOR_Q = 0.707f

        // Fast enough to catch the front of a squeak, slow enough that the release is not heard as
        // the band pumping back in behind the note.
        const val ATTACK_MS = 1f
        const val RELEASE_MS = 60f

        /** 1 - 1/ratio at a ratio of 4. Fixed: the useful control here is RANGE, not the ratio. */
        const val SLOPE = 0.75f
    }
}
