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
 * Attack and sustain, independent of level — the pick click without the note, and the note without
 * the click.
 *
 * **This is not a compressor, and reaching for one here is the standard mistake.** A compressor
 * responds to how *loud* the signal is, so on a percussive attack it does the opposite of what is
 * wanted: set it fast and it ducks the note immediately behind the click, set it slow and it lets
 * the click through and then squashes the sustain, which leaves the click *more* prominent than
 * before. A transient shaper responds to how fast the level is *changing*, which is a different
 * question and the right one.
 *
 * Two envelope followers on the same signal, one quick and one slow. Their difference is the whole
 * mechanism: it is positive while the level is rising faster than the slow follower can track — an
 * attack — negative while it decays, and zero through anything steady. So ATTACK moves the first few
 * milliseconds of a note and SUSTAIN moves its tail, and a held note is left alone by both.
 *
 * The difference is taken *relative* to the slow envelope rather than absolutely, which is what
 * makes the effect independent of playing level: a quiet note and a loud one with the same shape get
 * the same treatment.
 *
 * On a nylon-string instrument with an undersaddle pickup this is the control that exists for the
 * problem — nail and pick contact is a click a few milliseconds long sitting on top of a note that
 * is fine, and ATTACK at a few dB down removes it without touching the instrument.
 */
class TransientShaper : AudioProcessor {

    override val name = "Transient"

    @Volatile
    override var enabled = false

    val attack = Parameter("Attack", -12f, 12f, 0f, "dB")
    val sustain = Parameter("Sustain", -12f, 12f, 0f, "dB")

    override val parameters = listOf(attack, sustain)

    private var sampleRate = DEFAULT_SAMPLE_RATE

    private var fastEnvelope = 0f
    private var slowEnvelope = 0f
    private var gain = 1f

    private var fastAttack = 0f
    private var fastRelease = 0f
    private var slowAttack = 0f
    private var slowRelease = 0f
    private var gainSmoothing = 0f

    init {
        recomputeCoefficients()
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        recomputeCoefficients()
    }

    override fun process(buffer: FloatArray, count: Int) {
        // Converted once per block. Interpolating the two gains linearly rather than in decibels is
        // deliberate: it keeps `pow` out of the per-sample loop, and with both knobs at zero the
        // result is exactly 1.0, so an untouched shaper is bit-transparent rather than nearly so.
        val attackGain = 10f.pow(attack.value / 20f)
        val sustainGain = 10f.pow(sustain.value / 20f)

        val fastUp = fastAttack
        val fastDown = fastRelease
        val slowUp = slowAttack
        val slowDown = slowRelease
        val smoothing = gainSmoothing

        var fast = fastEnvelope
        var slow = slowEnvelope
        var g = gain

        for (i in 0 until count) {
            val x = buffer[i]
            val rectified = abs(x)

            val fastCoefficient = if (rectified > fast) fastUp else fastDown
            fast = rectified + fastCoefficient * (fast - rectified)
            val slowCoefficient = if (rectified > slow) slowUp else slowDown
            slow = rectified + slowCoefficient * (slow - rectified)

            // Relative, so the same note played quietly is shaped the same as played hard. The
            // floor keeps the division finite through silence, where both envelopes are zero.
            val difference = (fast - slow) / (slow + FLOOR)
            val rising = if (difference > 0f) (if (difference > 1f) 1f else difference) else 0f
            val falling = if (difference < 0f) (if (difference < -1f) 1f else -difference) else 0f

            val target = 1f + (attackGain - 1f) * rising + (sustainGain - 1f) * falling
            g += (target - g) * smoothing

            buffer[i] = x * g
        }

        fastEnvelope = fast
        slowEnvelope = slow
        gain = g
    }

    override fun reset() {
        fastEnvelope = 0f
        slowEnvelope = 0f
        // Unity, not zero: a shaper that started at silence would fade the first note in.
        gain = 1f
    }

    private fun recomputeCoefficients() {
        fastAttack = coefficient(FAST_ATTACK_MS)
        fastRelease = coefficient(FAST_RELEASE_MS)
        slowAttack = coefficient(SLOW_ATTACK_MS)
        slowRelease = coefficient(SLOW_RELEASE_MS)
        gainSmoothing = 1f - coefficient(GAIN_SMOOTHING_MS)
    }

    private fun coefficient(milliseconds: Float): Float =
        exp(-1f / ((milliseconds / 1000f) * sampleRate).coerceAtLeast(1f))

    private companion object {
        // The fast follower has to catch a pick attack, which is a couple of milliseconds; the slow
        // one has to miss it entirely and track only where the note settles. The gap between the two
        // pairs is what the effect works in — narrow it and there is nothing to act on.
        const val FAST_ATTACK_MS = 0.5f
        const val FAST_RELEASE_MS = 40f
        const val SLOW_ATTACK_MS = 25f
        const val SLOW_RELEASE_MS = 250f

        /** Short enough to shape a transient, long enough not to modulate the waveform itself. */
        const val GAIN_SMOOTHING_MS = 1f

        /** About -80 dB. Keeps the relative difference finite through silence. */
        const val FLOOR = 1e-4f
    }
}
