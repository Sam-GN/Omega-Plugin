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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * An octave **divider** — a square wave at exactly half the input's frequency, played at the input's
 * own dynamics and blended back against it.
 *
 * This is the Boss OC-2 approach rather than the Whammy one, and the difference is worth stating
 * because it decides everything else here. A pitch *shifter* analyses the signal and resynthesises
 * it: a window, an FFT or a pair of crossfaded delay taps, latency measured in tens of milliseconds,
 * and buffers that have to be sized and swept. A *divider* counts zero crossings, so it is a
 * comparator and a flip-flop. It reads one sample and writes one sample — **zero added latency** —
 * costs a handful of arithmetic against a 1.45 ms deadline, allocates nothing, and derives every
 * coefficient from the rate handed to [prepare].
 *
 * The catch is that its output is a square rather than a copy of the instrument, which is not a
 * defect to be apologised for: that slightly synthetic, hollow sub *is* the classic octave sound. A
 * pristine shifter would sound less like the records this exists to play, not more.
 *
 * ### How the division happens
 *
 * The detector is a Schmitt trigger watching a low-passed copy of the input. TRACK sets that
 * low-pass, and its job is to leave the fundamental and remove the harmonics that would otherwise
 * put extra zero crossings inside one cycle. The trigger's hysteresis is a fraction of the filtered
 * signal's own envelope rather than a fixed voltage, so it scales with how hard the string was hit
 * instead of working at one volume.
 *
 * Every rising edge of that trigger toggles a flip-flop. The flip-flop's output is therefore a
 * square with one full cycle per *two* input cycles — an octave down, exactly, with no tuning and no
 * drift, because it is counting the waveform rather than estimating a pitch.
 *
 * That square is scaled by a peak follower on the *unfiltered* input, so the sub tracks picking
 * dynamics and decays with the note; the scaling is at `1/√2` of the peak, which is where a square
 * and a sine of the same peak carry the same RMS, so MIX behaves like a blend instead of a jump in
 * level. TONE then low-passes it — a raw square is all odd harmonics and forever — and a 15 Hz
 * high-pass takes out the DC that a frozen flip-flop under a decaying envelope would otherwise leave
 * behind. The blend is equal-power: the sub is at a different frequency from the dry signal, so the
 * two are uncorrelated and a linear crossfade would dip in the middle of the knob.
 *
 * ### Two limits, both characteristic rather than accidental
 *
 * **It is monophonic.** A chord has several fundamentals, their zero crossings interleave, and the
 * flip-flop divides whatever it last saw — the result is a warbling, unmusical mess. A real OC-2
 * does exactly the same thing for exactly the same reason. Play single notes, low on the neck, which
 * is also where an octave pedal is worth having.
 *
 * **It needs a threshold.** Below THRESH the flip-flop is frozen and the sub is faded out, because a
 * comparator with nothing to compare will chatter on room noise and on the tail of a decaying note,
 * and chatter is heard as the octave warbling a semitone about. A [Gate] in front of it — which is
 * where the board puts one — makes that floor easier to set: gate the noise, and THRESH only has to
 * decide when the *note* has finished.
 */
class Octave : AudioProcessor {

    override val name = "Octave"

    @Volatile
    override var enabled = false

    /**
     * The detector's low-pass. Lower it until a low note tracks; raise it if notes up the neck drop
     * out. It is not in the audio path — it only decides where the zero crossings are.
     */
    val track = Parameter("Track", 100f, 2000f, 600f, "Hz", skew = 2f)

    /** The sub's own low-pass. Down for a rounder bass, up for the buzzy synthetic one. */
    val tone = Parameter("Tone", 200f, 4000f, 800f, "Hz", skew = 2f)

    /** Below this the flip-flop stops counting and the sub fades out. See the note on chatter. */
    val threshold = Parameter("Thresh", -60f, -20f, -42f, "dB")

    /**
     * Sub against dry, equal power.
     *
     * Defaults to zero, so a slot that has just been switched to Octave is bit-transparent until the
     * knob is moved — the same promise every other module here makes at its defaults.
     */
    val mix = Parameter("Mix", 0f, 1f, 0f)

    override val parameters = listOf(track, tone, threshold, mix)

    private var sampleRate = DEFAULT_SAMPLE_RATE

    /** Heard only by the comparator; never reaches the output. */
    private val detectorFilter = Biquad()

    /** In the audio path, and the only thing standing between a square and a fizz. */
    private val toneFilter = Biquad()

    private var designedTrack = Float.NaN
    private var designedTone = Float.NaN
    private var designedRate = 0f

    /** Peak follower on the input. Sets the sub's level and answers the threshold. */
    private var envelope = 0f

    /** Peak follower on the filtered copy, which is what the hysteresis is a fraction of. */
    private var detectorEnvelope = 0f

    /** The sub's level, smoothed, so opening and closing the threshold is a fade and not a step. */
    private var amplitude = 0f

    /** Comparator state: true once the signal has risen through +hysteresis. */
    private var high = false

    /** The divider itself. One flip per input cycle is one sub cycle per two — an octave down. */
    private var flipped = false

    // 15 Hz one-pole high-pass on the sub. `y = x - x1 + r*y1`.
    private var dcInput = 0f
    private var dcOutput = 0f

    private var attackCoefficient = 0f
    private var releaseCoefficient = 0f
    private var levelCoefficient = 0f
    private var dcCoefficient = 0f

    init {
        recomputeCoefficients()
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        recomputeCoefficients()
        // Forces both sections to be re-derived on the next block: the same knob is a different set
        // of coefficients at a different rate, which is the bug that pins a filter to 44.1 kHz.
        designedRate = 0f
    }

    override fun process(buffer: FloatArray, count: Int) {
        val rate = sampleRate
        if (designedRate != rate || designedTrack != track.value || designedTone != tone.value) {
            designedRate = rate
            designedTrack = track.value
            designedTone = tone.value
            detectorFilter.lowPass(rate, designedTrack, BUTTERWORTH_Q)
            toneFilter.lowPass(rate, designedTone, BUTTERWORTH_Q)
        }

        val blend = mix.value * HALF_PI
        val wet = sin(blend)
        val dry = cos(blend)
        // At MIX zero the buffer is not written at all, which is what makes the default exactly
        // transparent rather than merely close — a multiply by one and an add of zero is neither on
        // a signed zero. The detector still runs, so turning the knob up mid-note picks up a
        // flip-flop that is already counting rather than one starting from cold.
        val active = wet > 0f
        val thresholdLinear = 10f.pow(threshold.value / 20f)

        // Hoisted: the per-sample loop reads locals, never fields.
        val detector = detectorFilter
        val sub = toneFilter
        val up = attackCoefficient
        val down = releaseCoefficient
        val smooth = levelCoefficient
        val r = dcCoefficient

        var env = envelope
        var det = detectorEnvelope
        var amp = amplitude
        var isHigh = high
        var flip = flipped
        var x1 = dcInput
        var y1 = dcOutput

        for (i in 0 until count) {
            val x = buffer[i]

            val filtered = detector.process(x)

            val rectified = if (x < 0f) -x else x
            env = rectified + (if (rectified > env) up else down) * (env - rectified)
            if (env < DENORMAL) env = 0f

            val detectorRectified = if (filtered < 0f) -filtered else filtered
            det = detectorRectified + (if (detectorRectified > det) up else down) * (det - detectorRectified)
            if (det < DENORMAL) det = 0f

            val playing = env > thresholdLinear
            if (playing) {
                // Hysteresis proportional to the level being tracked, with a floor so that a signal
                // small enough to be arithmetic noise cannot toggle anything.
                val hysteresis = if (det * HYSTERESIS > MIN_HYSTERESIS) det * HYSTERESIS else MIN_HYSTERESIS
                if (isHigh) {
                    if (filtered < -hysteresis) isHigh = false
                } else if (filtered > hysteresis) {
                    isHigh = true
                    flip = !flip
                }
            } else if (amp == 0f) {
                // Reset only once the sub has actually reached silence. Forcing the flip-flop at the
                // moment the threshold closes would step the square from +amp to -amp, which is a
                // click; leaving it frozen turns the fade-out into DC, which the blocker below eats.
                isHigh = false
                flip = false
                detector.reset()
                sub.reset()
                x1 = 0f
                y1 = 0f
            }

            // 1/√2 of the peak: a square and a sine of the same peak do not carry the same RMS, and
            // matching them is what stops MIX from being a level control by accident.
            val target = if (playing) env * SQUARE_LEVEL else 0f
            amp += smooth * (target - amp)
            if (amp < DENORMAL) amp = 0f

            val square = if (flip) amp else -amp
            val blocked = square - x1 + r * y1
            x1 = square
            y1 = blocked

            val octave = sub.process(blocked)
            if (active) buffer[i] = dry * x + wet * octave
        }

        envelope = env
        detectorEnvelope = det
        amplitude = amp
        high = isHigh
        flipped = flip
        dcInput = x1
        dcOutput = y1
    }

    override fun reset() {
        detectorFilter.reset()
        toneFilter.reset()
        envelope = 0f
        detectorEnvelope = 0f
        amplitude = 0f
        // Armed to fire on the first rising crossing, so the first note of a take starts the sub in
        // step with it rather than half a cycle late.
        high = false
        flipped = false
        dcInput = 0f
        dcOutput = 0f
    }

    private fun recomputeCoefficients() {
        attackCoefficient = coefficient(ATTACK_MS)
        releaseCoefficient = coefficient(RELEASE_MS)
        levelCoefficient = 1f - coefficient(LEVEL_MS)
        dcCoefficient = exp(-2f * PI.toFloat() * DC_BLOCK_HZ / sampleRate)
    }

    /** One-pole coefficient for a time constant in milliseconds, at the current rate. */
    private fun coefficient(milliseconds: Float): Float =
        exp(-1f / ((milliseconds / 1000f) * sampleRate).coerceAtLeast(1f))

    private companion object {
        const val HALF_PI = (PI / 2.0).toFloat()

        /** Maximally flat: both sections are shaping, not resonating. */
        const val BUTTERWORTH_Q = 0.707f

        // Fast enough to be under the pick, slow enough that the sub does not follow the ripple of
        // its own waveform. The extra LEVEL_MS smoothing is what turns the threshold into a fade.
        const val ATTACK_MS = 3f
        const val RELEASE_MS = 60f
        const val LEVEL_MS = 3f

        /** 1/√2. See the note where it is used. */
        const val SQUARE_LEVEL = 0.70710678f

        /** Comparator hysteresis as a fraction of the tracked envelope. */
        const val HYSTERESIS = 0.15f

        /** …and its absolute floor, well under anything THRESH can be set to. */
        const val MIN_HYSTERESIS = 1e-6f

        /**
         * Below a bass guitar's bottom note and below the lowest sub this can produce, so it removes
         * the offset a frozen flip-flop leaves and nothing that was worth keeping.
         */
        const val DC_BLOCK_HZ = 15f

        /**
         * Where a geometric decay is called silence.
         *
         * -300 dB, so nothing audible is ever truncated. Two reasons for snapping rather than
         * letting it run: a follower that decays for ever eventually reaches denormal range, where
         * the arithmetic can cost orders of magnitude more than the work itself, and the idle branch
         * above needs an exact `== 0f` to fire on — which is what lets the filters be cleared once,
         * at true silence, instead of never.
         */
        const val DENORMAL = 1e-15f
    }
}
