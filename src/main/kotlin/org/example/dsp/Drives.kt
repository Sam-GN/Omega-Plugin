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
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/*
 * The drives behind the Overdrive pedal's dropdown.
 *
 * Each is a small, allocation-free waveshaper with the filters that give its family of pedal its
 * character. What separates them is three things: the clipping curve (soft or hard, symmetric or
 * not), what is taken out of the signal before it reaches the clipper, and what is done to it
 * afterwards. Every coefficient derives from the rate handed to `prepare`, never from 44 100.
 *
 * None of them is a model of a particular circuit. They are the shapes those circuits have, which
 * is the part a player reaches for: a Screamer-style tightening ahead of an amp capture is a
 * different sound from the plain soft clip, whichever Screamer it is in the style of.
 */

/** A one-pole filter's coefficient for [cutoffHz] at [sampleRate]. */
private fun onePole(sampleRate: Float, cutoffHz: Float): Float =
    (1f - exp(-2f * PI.toFloat() * cutoffHz / sampleRate)).coerceIn(0f, 1f)

/**
 * Soft-clipping overdrive: the pedal's original and default sound.
 *
 * `tanh` gives a smooth, asymptotic knee rather than the harsh corners of hard clipping, which is
 * what makes it sound like a driven tube stage instead of digital wrap-around. The output is
 * normalised by `1/tanh(drive)` so cranking drive adds harmonics without also adding raw level, and
 * a one-pole lowpass afterwards tames the fizz the clipper generates up top.
 *
 * The lowpass coefficient is derived from the device rate: baked in at 44.1 kHz, the tone control
 * would land roughly 9 % high on a 48 kHz device and be wrong by an octave at 96 kHz.
 */
class SmoothDrive : AudioProcessor {

    override val name = "Smooth"

    @Volatile
    override var enabled = true

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
        val a = onePole(sampleRate, tone.value)
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
}

/**
 * In the style of a Tube Screamer.
 *
 * The whole character of that pedal is what it does *before* clipping: a high-pass around 720 Hz
 * takes the bottom out, so the clipper never sees the bass and the result is tight rather than
 * woolly, and what is left is a hump in the mids. Here that is a one-pole high-pass into a mildly
 * asymmetric soft clip — the negative half clips a little later, as a pair of mismatched diodes
 * would — then a fixed +4 dB peak at 800 Hz, pulled back by the same amount overall so the output
 * stays inside full scale, and the tone low-pass. Same three knobs as Smooth, because that is what
 * the pedal has.
 */
class ScreamerDrive : AudioProcessor {

    override val name = "Screamer"

    @Volatile
    override var enabled = true

    val drive = Parameter("Drive", 1f, 50f, 8f, "x", skew = 2f)
    val tone = Parameter("Tone", 200f, 8000f, 3000f, "Hz", skew = 2f)
    val level = Parameter("Level", 0f, 1f, 0.7f)

    override val parameters = listOf(drive, tone, level)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var highpassCoefficient = onePole(DEFAULT_SAMPLE_RATE, HIGHPASS_HZ)
    private val hump = Biquad().apply { peaking(DEFAULT_SAMPLE_RATE, HUMP_HZ, HUMP_DB, HUMP_Q) }

    private var highpassState = 0f
    private var lowpass = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        highpassCoefficient = onePole(sampleRate, HIGHPASS_HZ)
        hump.peaking(sampleRate, HUMP_HZ, HUMP_DB, HUMP_Q)
    }

    override fun process(buffer: FloatArray, count: Int) {
        val d = drive.value
        val normalise = 1f / tanh(d)
        val hp = highpassCoefficient
        val a = onePole(sampleRate, tone.value)
        val lvl = level.value * HUMP_MAKEUP
        val filter = hump

        var hpState = highpassState
        var lp = lowpass
        for (i in 0 until count) {
            val x = buffer[i]
            hpState += hp * (x - hpState)
            val tight = x - hpState
            val driven = tight * d
            // The negative half-cycle is driven less hard, so it rounds off later and never quite
            // reaches the positive one's level: that mismatch is the asymmetry, and it is kept
            // below full scale by not scaling it back up.
            val clipped = if (driven >= 0f) {
                tanh(driven) * normalise
            } else {
                tanh(driven * NEGATIVE_KNEE) * normalise
            }
            val humped = filter.process(clipped)
            lp += a * (humped - lp)
            val out = lp * lvl
            // The hump is a resonant filter and overshoots its steady-state gain on a hard edge.
            // Caught here, at a level the clipper has already made inaudible, rather than let
            // through to the engine's clamp where it would be a bug.
            buffer[i] = if (out > 1f) 1f else if (out < -1f) -1f else out
        }
        highpassState = hpState
        lowpass = lp
    }

    override fun reset() {
        highpassState = 0f
        lowpass = 0f
        hump.reset()
    }

    private companion object {
        const val HIGHPASS_HZ = 720f
        const val HUMP_HZ = 800f
        const val HUMP_DB = 4f
        const val HUMP_Q = 0.9f

        /** -4 dB, so the hump's peak lands at full scale rather than above it. */
        const val HUMP_MAKEUP = 0.631f

        /** The negative half-cycle sees 0.8 of the drive, so it clips later and lands lower. */
        const val NEGATIVE_KNEE = 0.8f
    }
}

/**
 * In the style of a Rat.
 *
 * Hard clipping: the signal is driven and then simply cut off at full scale, which is the buzz and
 * the compression a distortion pedal has and a soft clipper does not. The knobs are the pedal's
 * own. `Filter` is a low-pass that *closes* as it is turned up — at zero it is nearly open, at full
 * it is down around 400 Hz — because that is the way round the real one works and anyone who has
 * used one reaches for it that way.
 */
class RatDrive : AudioProcessor {

    override val name = "Rat"

    @Volatile
    override var enabled = true

    val distortion = Parameter("Distortion", 1f, 100f, 20f, "x", skew = 2f)
    val filter = Parameter("Filter", 0f, 1f, 0.4f)
    val volume = Parameter("Volume", 0f, 1f, 0.6f)

    override val parameters = listOf(distortion, filter, volume)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var lowpass = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
    }

    override fun process(buffer: FloatArray, count: Int) {
        val g = distortion.value
        // Exponential between the two ends, so the middle of the knob is the middle of the sweep in
        // octaves rather than in hertz.
        val cutoff = FILTER_OPEN_HZ * (FILTER_CLOSED_HZ / FILTER_OPEN_HZ).pow(filter.value)
        val a = onePole(sampleRate, cutoff)
        val lvl = volume.value

        var lp = lowpass
        for (i in 0 until count) {
            val driven = buffer[i] * g
            val clipped = if (driven > 1f) 1f else if (driven < -1f) -1f else driven
            lp += a * (clipped - lp)
            buffer[i] = lp * lvl
        }
        lowpass = lp
    }

    override fun reset() {
        lowpass = 0f
    }

    private companion object {
        const val FILTER_OPEN_HZ = 6000f
        const val FILTER_CLOSED_HZ = 400f
    }
}

/**
 * In the style of a fuzz.
 *
 * Full bass into a hard, asymmetric clip that is a square wave by the top of the knob. The
 * asymmetry is a bias added before the clipper, and the bias is **not fixed**: it grows as the
 * signal dies away, tracked by an envelope follower, so a sustained note sputters and gates on its
 * decay the way a fuzz with a tired battery does. The clipper's resting value is subtracted, so
 * silence in is exactly silence out whatever the bias; a one-pole high-pass at 10 Hz takes out the
 * offset the asymmetry leaves on real signal. Two knobs, because that is what a fuzz has.
 */
class FuzzDrive : AudioProcessor {

    override val name = "Fuzz"

    @Volatile
    override var enabled = true

    val fuzz = Parameter("Fuzz", 1f, 100f, 30f, "x", skew = 2f)
    val volume = Parameter("Volume", 0f, 1f, 0.5f)

    override val parameters = listOf(fuzz, volume)

    private var attack = onePole(DEFAULT_SAMPLE_RATE, ATTACK_HZ)
    private var release = onePole(DEFAULT_SAMPLE_RATE, RELEASE_HZ)
    private var dcBlock = onePole(DEFAULT_SAMPLE_RATE, DC_HZ)

    private var envelope = 0f
    private var dcState = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        attack = onePole(sampleRate, ATTACK_HZ)
        release = onePole(sampleRate, RELEASE_HZ)
        dcBlock = onePole(sampleRate, DC_HZ)
    }

    override fun process(buffer: FloatArray, count: Int) {
        val g = fuzz.value
        val lvl = volume.value
        val up = attack
        val down = release
        val dc = dcBlock

        var env = envelope
        var dcs = dcState
        for (i in 0 until count) {
            val x = buffer[i]
            val magnitude = abs(x)
            env += (if (magnitude > env) up else down) * (magnitude - env)
            // Loud: no bias, a symmetric square. Quiet: the bias leans the wave over until one
            // half-cycle falls under the knee, which is the sputter.
            val loudness = env * ENVELOPE_GAIN
            val bias = BIAS_MAX * (1f - (if (loudness > 1f) 1f else loudness))
            val driven = x * g + bias
            val clipped = if (driven > 1f) 1f else if (driven < -1f) -1f else driven
            // Subtracting the resting value keeps silence at zero; dividing by the larger swing
            // keeps the lopsided wave inside full scale whatever the bias is doing.
            val centred = (clipped - bias) / (1f + bias)
            dcs += dc * (centred - dcs)
            val out = (centred - dcs) * lvl
            // The blocker recentres a lopsided wave, which can push its taller side past full
            // scale by the amount it moved. Caught here, like the Screamer's hump.
            buffer[i] = if (out > 1f) 1f else if (out < -1f) -1f else out
        }
        envelope = env
        dcState = dcs
    }

    override fun reset() {
        envelope = 0f
        dcState = 0f
    }

    private companion object {
        const val ATTACK_HZ = 30f
        const val RELEASE_HZ = 1.5f
        const val DC_HZ = 10f

        /** How far the clipper is pushed off centre at silence. */
        const val BIAS_MAX = 0.6f

        /** An input envelope of 0.25 is already "loud": fully symmetric from there up. */
        const val ENVELOPE_GAIN = 4f
    }
}

/**
 * In the style of a driven tube preamp.
 *
 * A soft clip with a small fixed bias, so the two half-cycles round off differently and the second
 * harmonic that makes a valve stage sound warm rather than merely fuzzy is there. Little
 * compression — the curve is normalised so the knee arrives late — and a bass and a treble shelf
 * after it, since what a preamp has is a tone stack rather than a single low-pass. Four knobs.
 */
class TubeDrive : AudioProcessor {

    override val name = "Tube"

    @Volatile
    override var enabled = true

    val gain = Parameter("Gain", 1f, 30f, 6f, "x", skew = 2f)
    val bass = Parameter("Bass", -12f, 12f, 0f, "dB")
    val treble = Parameter("Treble", -12f, 12f, 0f, "dB")
    val level = Parameter("Level", 0f, 1f, 0.7f)

    override val parameters = listOf(gain, bass, treble, level)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private val low = Biquad().apply { lowShelf(DEFAULT_SAMPLE_RATE, BASS_HZ, 0f) }
    private val high = Biquad().apply { highShelf(DEFAULT_SAMPLE_RATE, TREBLE_HZ, 0f) }
    private var lowFor = 0f
    private var highFor = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        low.lowShelf(sampleRate, BASS_HZ, bass.value)
        high.highShelf(sampleRate, TREBLE_HZ, treble.value)
        lowFor = bass.value
        highFor = treble.value
    }

    override fun process(buffer: FloatArray, count: Int) {
        val g = gain.value
        val lvl = level.value
        // Coefficients follow the knobs once per block, and only when a knob has moved: the shelf
        // arithmetic has a few transcendentals in it, which is cheap per block and not per sample.
        val wantLow = bass.value
        if (wantLow != lowFor) {
            low.lowShelf(sampleRate, BASS_HZ, wantLow)
            lowFor = wantLow
        }
        val wantHigh = treble.value
        if (wantHigh != highFor) {
            high.highShelf(sampleRate, TREBLE_HZ, wantHigh)
            highFor = wantHigh
        }
        val rest = tanh(BIAS)
        // Scaled so the larger half-cycle reaches exactly full scale, and the quieter one does not:
        // that imbalance is the second harmonic.
        val normalise = 1f / (1f + rest)
        val lowShelf = low
        val highShelf = high

        for (i in 0 until count) {
            val shaped = (tanh(buffer[i] * g + BIAS) - rest) * normalise
            val out = highShelf.process(lowShelf.process(shaped)) * lvl
            // A shelf at +12 dB is a boost after the clipper, and a boost after a clipper can only
            // be kept honest by clipping again. At the level it bites, the stage is already square.
            buffer[i] = if (out > 1f) 1f else if (out < -1f) -1f else out
        }
    }

    override fun reset() {
        low.reset()
        high.reset()
    }

    private companion object {
        const val BIAS = 0.15f
        const val BASS_HZ = 200f
        const val TREBLE_HZ = 3000f
    }
}
