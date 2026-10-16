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
package org.example.dsp.rhythm

import org.example.dsp.Biquad
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * The kit, synthesised.
 *
 * **Nothing here is sampled, and that is a licence decision before it is a technical one.** A drum
 * kit shipped as audio is an asset with its own copyright, and this program is GPLv3-or-later with
 * exactly one third-party runtime dependency — the same reasoning that keeps NAM captures and
 * impulse responses out of the distribution and leaves only loaders behind. Synthesis has no such
 * question: a kick is an oscillator and an envelope, and an oscillator has no author.
 *
 * It is also the right sound for the job. These are analogue-drum-machine voices rather than
 * imitations of a recorded kit, and against a loud guitar amp a drum machine cuts through where a
 * sampled room mic turns to mud.
 *
 * Every voice obeys the callback's rules: sized in [DrumVoice.prepare] off the audio thread,
 * allocation-free in [DrumVoice.render], and every coefficient derived from the rate it was given
 * rather than from a hardcoded 44 100.
 */
internal interface DrumVoice {

    /** Binds to a device rate. Never called from the audio thread. */
    fun prepare(rate: Float)

    /**
     * Restarts the voice. A drum machine retriggers rather than layers — a second hit on the same
     * voice cuts the first — which is what keeps the voice count fixed and [render] allocation-free.
     */
    fun trigger(velocity: Float)

    fun silence()

    /** One sample. Returns exactly 0 when idle, so an inactive voice costs a compare. */
    fun render(): Float
}

/**
 * A shared sine, read by table rather than computed.
 *
 * The tuned voices sweep their pitch, and a swept oscillator cannot use the coupled recurrence
 * [Tick] does — that one's coefficient is `2·cos(w)` for a *fixed* w, and recomputing it per sample
 * would put a transcendental back in the sample loop it exists to avoid. A table read plus a linear
 * interpolation is two loads, a multiply and an add.
 *
 * 4 096 entries with interpolation puts the worst-case harmonic distortion around −80 dB, which is
 * far below anything audible in a drum voice whose envelope is 40 ms long.
 *
 * The initialiser runs on whichever thread touches this first, so `Transport.prepare` touches it
 * deliberately — first use from inside a callback would be class loading against the deadline.
 */
internal object SineTable {

    private const val SIZE = 4096

    /** One extra entry so interpolation never has to wrap its upper neighbour. */
    private val VALUES = FloatArray(SIZE + 1) { sin(2.0 * PI * it / SIZE).toFloat() }

    /** [phase] in [0, 1). */
    fun at(phase: Float): Float {
        val x = phase * SIZE
        val index = x.toInt()
        val fraction = x - index
        val a = VALUES[index]
        return a + (VALUES[index + 1] - a) * fraction
    }

    /** Forces the table to exist, from a thread that is allowed to allocate. */
    fun warm(): Int = VALUES.size
}

/**
 * White noise, from a xorshift.
 *
 * Not `Random`: `Random.nextFloat()` is fine but this is called once per sample per active voice and
 * a xorshift is three shifts and three xors with no object behind it. Seeded per voice so two hats
 * triggered together do not produce identical noise and cancel or double.
 */
internal class Noise(seed: Int) {

    private var state = if (seed == 0) 0x9E3779B9.toInt() else seed

    fun next(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        // 2^-31, so the result spans roughly [-1, 1).
        return x * 4.656613e-10f
    }
}

/** Time for an envelope to fall 60 dB, as a per-sample multiplier. */
private fun decayPerSample(seconds: Float, rate: Float): Float =
    exp(ln(0.001) / (seconds.coerceAtLeast(0.001f) * rate)).toFloat()

/** Below this an envelope is inaudible and the voice stops doing arithmetic. */
private const val SILENCE = 1e-5f

/**
 * A metronome tick: one decaying sine burst.
 *
 * The oscillator is the coupled recurrence `y[n] = 2·cos(w)·y[n-1] − y[n-2]`, not `sin()` and not a
 * table — one multiply and one subtract per sample. It can do that because a tick's pitch never
 * moves, which is exactly what the drum voices cannot assume.
 *
 * The burst starts from phase zero, so there is no step at the attack and no ramp is needed.
 */
internal class Tick(private val frequency: Float, private val decaySeconds: Float) : DrumVoice {

    private var coefficient = 0f
    private var seed = 0f
    private var decay = 0f

    private var previous = 0f
    private var beforeThat = 0f
    private var envelope = 0f

    override fun prepare(rate: Float) {
        val w = (2.0 * PI * frequency / rate).coerceIn(0.0, PI * 0.9)
        coefficient = (2.0 * cos(w)).toFloat()
        seed = sin(w).toFloat()
        decay = decayPerSample(decaySeconds, rate)
        silence()
    }

    override fun trigger(velocity: Float) {
        beforeThat = 0f
        previous = seed
        envelope = velocity
    }

    override fun silence() {
        beforeThat = 0f
        previous = 0f
        envelope = 0f
    }

    override fun render(): Float {
        if (envelope < SILENCE) {
            envelope = 0f
            return 0f
        }
        val out = beforeThat * envelope
        val next = coefficient * previous - beforeThat
        beforeThat = previous
        previous = next
        envelope *= decay
        return out
    }
}

/**
 * Kick: a sine whose pitch falls out from under it.
 *
 * The whole character is in the sweep. A sine at a fixed 50 Hz is a test tone; the same sine started
 * at 150 Hz and dropped to 50 over about 25 ms is a kick drum, because the falling pitch is what the
 * ear reads as a beater striking a head under tension. This is the 808's trick and it costs one
 * extra envelope.
 *
 * The pitch envelope is much faster than the amplitude envelope — 25 ms against 420 ms — so the note
 * has settled at its fundamental long before it has finished sounding.
 */
internal class Kick : DrumVoice {

    private var rate = 44_100f
    private var phase = 0f
    private var pitchEnvelope = 0f
    private var pitchDecay = 0f
    private var amplitude = 0f
    private var amplitudeDecay = 0f

    override fun prepare(rate: Float) {
        this.rate = rate
        pitchDecay = decayPerSample(SWEEP_SECONDS, rate)
        amplitudeDecay = decayPerSample(DECAY_SECONDS, rate)
        silence()
    }

    override fun trigger(velocity: Float) {
        phase = 0f
        pitchEnvelope = 1f
        amplitude = velocity
    }

    override fun silence() {
        phase = 0f
        pitchEnvelope = 0f
        amplitude = 0f
    }

    override fun render(): Float {
        if (amplitude < SILENCE) {
            amplitude = 0f
            return 0f
        }
        val frequency = END_HZ + (START_HZ - END_HZ) * pitchEnvelope
        phase += frequency / rate
        if (phase >= 1f) phase -= 1f
        val out = SineTable.at(phase) * amplitude * GAIN
        pitchEnvelope *= pitchDecay
        amplitude *= amplitudeDecay
        return out
    }

    private companion object {
        const val START_HZ = 150f
        const val END_HZ = 48f
        const val SWEEP_SECONDS = 0.025f
        const val DECAY_SECONDS = 0.42f
        const val GAIN = 0.95f
    }
}

/**
 * Snare: a band of noise with two tuned tones under it.
 *
 * Both halves are needed and they are doing different jobs. The tones are the drum's shell — two
 * detuned partials, because one is a tom and three is a chord — and the noise is the snare wire
 * against the bottom head, which is where nearly all of the instrument's identity lives. The noise
 * therefore carries the greater share and decays slightly slower than the tones, which is what a
 * real snare does.
 *
 * The band is two cascaded [Biquad]s rather than a dedicated band-pass, because a high-pass and a
 * low-pass are what that class already offers and a snare's band has no need for a sharper edge than
 * two second-order sections give it.
 */
internal class Snare : DrumVoice {

    private var rate = 44_100f
    private val noise = Noise(0x5EED_1234)
    private val highPass = Biquad()
    private val lowPass = Biquad()

    private var lowPhase = 0f
    private var highPhase = 0f
    private var toneEnvelope = 0f
    private var toneDecay = 0f
    private var noiseEnvelope = 0f
    private var noiseDecay = 0f

    override fun prepare(rate: Float) {
        this.rate = rate
        highPass.highPass(rate, BAND_LOW_HZ, 0.7f)
        lowPass.lowPass(rate, BAND_HIGH_HZ.coerceAtMost(rate * 0.45f), 0.7f)
        toneDecay = decayPerSample(TONE_SECONDS, rate)
        noiseDecay = decayPerSample(NOISE_SECONDS, rate)
        silence()
    }

    override fun trigger(velocity: Float) {
        lowPhase = 0f
        highPhase = 0f
        toneEnvelope = velocity
        noiseEnvelope = velocity
    }

    override fun silence() {
        toneEnvelope = 0f
        noiseEnvelope = 0f
        lowPhase = 0f
        highPhase = 0f
        highPass.reset()
        lowPass.reset()
    }

    override fun render(): Float {
        if (noiseEnvelope < SILENCE && toneEnvelope < SILENCE) {
            noiseEnvelope = 0f
            toneEnvelope = 0f
            return 0f
        }
        lowPhase += LOW_HZ / rate
        if (lowPhase >= 1f) lowPhase -= 1f
        highPhase += HIGH_HZ / rate
        if (highPhase >= 1f) highPhase -= 1f

        val tone = (SineTable.at(lowPhase) + SineTable.at(highPhase)) * toneEnvelope * TONE_GAIN
        val band = lowPass.process(highPass.process(noise.next()))
        val out = tone + band * noiseEnvelope * NOISE_GAIN

        toneEnvelope *= toneDecay
        noiseEnvelope *= noiseDecay
        return out
    }

    private companion object {
        const val LOW_HZ = 185f
        const val HIGH_HZ = 330f
        const val BAND_LOW_HZ = 700f
        const val BAND_HIGH_HZ = 7_000f
        const val TONE_SECONDS = 0.13f
        const val NOISE_SECONDS = 0.19f
        const val TONE_GAIN = 0.22f
        const val NOISE_GAIN = 0.55f
    }
}

/**
 * Rim click: the same ingredients as a snare, over in a fortieth of the time.
 *
 * A side-stick is a short, hard, woody knock — high partials, almost no body, and a decay you would
 * call a transient rather than a note. It is what a 6/8 ballad wants instead of a snare, because a
 * full backbeat at that tempo overwhelms a quiet part.
 */
internal class Rim : DrumVoice {

    private var rate = 44_100f
    private val noise = Noise(0x13579BDF)
    private val highPass = Biquad()

    private var lowPhase = 0f
    private var highPhase = 0f
    private var envelope = 0f
    private var decay = 0f
    private var clickEnvelope = 0f
    private var clickDecay = 0f

    override fun prepare(rate: Float) {
        this.rate = rate
        highPass.highPass(rate, 2_200f.coerceAtMost(rate * 0.45f), 0.7f)
        decay = decayPerSample(BODY_SECONDS, rate)
        clickDecay = decayPerSample(CLICK_SECONDS, rate)
        silence()
    }

    override fun trigger(velocity: Float) {
        lowPhase = 0f
        highPhase = 0f
        envelope = velocity
        clickEnvelope = velocity
    }

    override fun silence() {
        envelope = 0f
        clickEnvelope = 0f
        lowPhase = 0f
        highPhase = 0f
        highPass.reset()
    }

    override fun render(): Float {
        if (envelope < SILENCE && clickEnvelope < SILENCE) {
            envelope = 0f
            clickEnvelope = 0f
            return 0f
        }
        lowPhase += LOW_HZ / rate
        if (lowPhase >= 1f) lowPhase -= 1f
        highPhase += HIGH_HZ / rate
        if (highPhase >= 1f) highPhase -= 1f

        val tone = (SineTable.at(lowPhase) * 0.6f + SineTable.at(highPhase)) * envelope * TONE_GAIN
        val click = highPass.process(noise.next()) * clickEnvelope * CLICK_GAIN

        envelope *= decay
        clickEnvelope *= clickDecay
        return tone + click
    }

    private companion object {
        const val LOW_HZ = 520f
        const val HIGH_HZ = 1_750f
        const val BODY_SECONDS = 0.038f
        const val CLICK_SECONDS = 0.016f
        const val TONE_GAIN = 0.34f
        const val CLICK_GAIN = 0.40f
    }
}

/**
 * Hi-hat, closed or open: noise with everything below the top end taken off it.
 *
 * Two cascaded high-passes, for 24 dB/oct. One is not enough — a hat sitting on a 12 dB/oct slope
 * keeps enough low-mid to sound like a burst of static rather than like metal, and this is the same
 * arithmetic that makes a single pole a poor detector filter elsewhere in this program.
 *
 * Closed and open are the same voice with different decays, which is also true of the instrument.
 * They are separate [Voice] entries rather than one with a parameter so a pattern can hit an open
 * hat while a closed one is still ringing.
 */
internal class Hat(private val decaySeconds: Float, seed: Int) : DrumVoice {

    private val noise = Noise(seed)
    private val first = Biquad()
    private val second = Biquad()

    private var envelope = 0f
    private var decay = 0f

    override fun prepare(rate: Float) {
        // Nyquist-aware: at 44.1 kHz this is 7.5 kHz, and a rate low enough to put that near the
        // limit gets a corner that still leaves something above it.
        val corner = CORNER_HZ.coerceAtMost(rate * 0.40f)
        first.highPass(rate, corner, 0.7f)
        second.highPass(rate, corner, 0.7f)
        decay = decayPerSample(decaySeconds, rate)
        silence()
    }

    override fun trigger(velocity: Float) {
        envelope = velocity
    }

    override fun silence() {
        envelope = 0f
        first.reset()
        second.reset()
    }

    override fun render(): Float {
        if (envelope < SILENCE) {
            envelope = 0f
            return 0f
        }
        val out = second.process(first.process(noise.next())) * envelope * GAIN
        envelope *= decay
        return out
    }

    private companion object {
        const val CORNER_HZ = 7_500f
        const val GAIN = 0.42f
    }
}
