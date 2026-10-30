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

/**
 * The amp pedal: one fixed place on the board, between the drive and the capture, and a dropdown
 * that picks which family of amplifier it is voiced as.
 *
 * These are **voicings**, not models. A component-level model of one amplifier takes years, and a
 * neural capture of one is what the NAM pedal already loads. What this has is the stages an amp has
 * — a cascaded preamp, a tone stack, a power amp with sag — with the gain structure and the tone
 * curve of a family, so that it sounds like a blackface clean or a plexi crunch rather than like a
 * particular Deluxe or a particular Super Lead. It is for having an amp when there is no capture,
 * and for the ten-second switch between families when trying things. See [AmpVoice].
 *
 * A [TypedModule], so switching fades, every voice is warmed, and a preset keeps every voice's
 * knobs. Six knobs each; the pedal shows four and the expand button reveals Presence and Master.
 */
class Amp : TypedModule(TYPES) {

    override val name = "Amp"

    companion object {
        /** The families. **Append only** — see [TypedModule]. */
        private val TYPES = listOf(
            ModuleType("American Clean") { AmpVoice(AmpVoice.AMERICAN_CLEAN) },
            ModuleType("Tweed") { AmpVoice(AmpVoice.TWEED) },
            ModuleType("British Crunch") { AmpVoice(AmpVoice.BRITISH_CRUNCH) },
            ModuleType("British Chime") { AmpVoice(AmpVoice.BRITISH_CHIME) },
            ModuleType("Modern High Gain") { AmpVoice(AmpVoice.MODERN_HIGH_GAIN) },
        )

        val typeNames: List<String> get() = TYPES.map { it.label }
    }
}

/**
 * One amp family, as signal path.
 *
 * In order, and the order is most of what makes it an amp rather than a pedal:
 *
 * 1. **Input shaping** — a high-pass that sets how tight the bottom is going into the gain (a
 *    high-gain amp cuts far more than a clean one, which is why it stays defined), and for the
 *    chime family a bright shelf, because that circuit's character is treble into the clipper.
 * 2. **Preamp** — two to four cascaded stages, each a biased soft clip with a coupling high-pass
 *    and a gentle low-pass after it. Two stages into each other behave nothing like one stage at
 *    twice the gain: the first rounds the peaks, the second clips what the first left, and the
 *    coupling capacitors between them are why the attack of a note blooms. GAIN drives the first.
 * 3. **Tone stack** — bass, mid and treble. The real thing is a passive network whose shape differs
 *    by family; this is that shape as three filters: a low shelf, a mid peak and a high shelf, with
 *    the family's centre frequencies and, above all, its *mid at noon* — scooped on the American
 *    stacks, full on the British one — which is the thing a player recognises first.
 * 4. **Power amp** — a soft clip with **sag**: an envelope follower that drops the gain after a
 *    loud transient and lets it back up over a couple of hundred milliseconds, which is the
 *    compression and bloom a tube amp has when pushed. PRESENCE is a high shelf after it; the chime
 *    family has CUT instead, a low-pass that closes as it is turned up, as on the real one.
 * 5. **Master** — the output level.
 *
 * The whole path runs at **twice the device rate**. Clipping at 44.1 kHz aliases, and aliasing is
 * the fizz that makes cheap amp simulations sound cheap. The input is zero-stuffed and filtered up,
 * every stage runs at the doubled rate with coefficients designed for it, and the output is
 * filtered and decimated back. It doubles the cost of the voice, which the measured headroom
 * carries easily.
 *
 * Allocation-free in `process`: every filter and state is built up front, and coefficients are
 * recomputed only on the block in which a knob moved.
 */
class AmpVoice(private val spec: Spec) : AudioProcessor {

    /**
     * One family's numbers. Gains are the maximum each preamp stage reaches; the first is scaled by
     * GAIN, the rest are fixed. The later gains are deliberately modest — between one and three —
     * because a stage's output is already inside ±1 and the next stage's drive is that times its
     * gain: at three or more every later stage is square whatever GAIN says, and the knob stops
     * meaning anything. Biases push each stage off centre for even harmonics.
     */
    class Spec(
        val label: String,
        val inputHighPassHz: Float,
        val brightDb: Float,
        val stageGains: FloatArray,
        val stageBiases: FloatArray,
        val couplingHz: Float,
        val millerHz: Float,
        val bassHz: Float,
        val midHz: Float,
        val midQ: Float,
        val midNoonDb: Float,
        val trebleHz: Float,
        val powerDrive: Float,
        val sag: Float,
        val presenceHz: Float,
        /** True for CUT, the closing low-pass, in place of PRESENCE. */
        val cut: Boolean,
    )

    override val name = spec.label

    @Volatile
    override var enabled = true

    val gain = Parameter("Gain", 0f, 10f, 5f)
    val bass = Parameter("Bass", 0f, 10f, 5f)
    val mid = Parameter("Mid", 0f, 10f, 5f)
    val treble = Parameter("Treble", 0f, 10f, 5f)
    val presence = Parameter(if (spec.cut) "Cut" else "Presence", 0f, 10f, 5f)
    val master = Parameter("Master", 0f, 10f, 6f)

    override val parameters = listOf(gain, bass, mid, treble, presence, master)

    /** Gain and the tone stack on the pedal; Presence and Master behind the expand button. */
    override val compactParameters: Int get() = 4

    // Everything below is at the oversampled rate.
    private var rate = DEFAULT_SAMPLE_RATE * OVERSAMPLE

    private val upA = Biquad()
    private val upB = Biquad()
    private val downA = Biquad()
    private val downB = Biquad()
    private val input = Biquad()
    private val bright = Biquad()
    private val stages = spec.stageGains.size
    private val couplingState = FloatArray(stages)
    private val millerState = FloatArray(stages)
    private var couplingCoefficient = 0f
    private var millerCoefficient = 0f
    private val restingOutput = FloatArray(stages) { tanh(spec.stageBiases[it]) }
    private val lowShelf = Biquad()
    private val midPeak = Biquad()
    private val highShelf = Biquad()
    private var envelope = 0f
    private var sagAttack = 0f
    private var sagRelease = 0f
    private val post = Biquad()

    private var bassFor = Float.NaN
    private var midFor = Float.NaN
    private var trebleFor = Float.NaN
    private var presenceFor = Float.NaN

    init {
        design(DEFAULT_SAMPLE_RATE)
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        design(sampleRate)
    }

    /** Everything that depends on the rate; the knob-driven filters are then refreshed per block. */
    private fun design(deviceRate: Float) {
        rate = deviceRate * OVERSAMPLE
        // Anti-image on the way up and anti-alias on the way down: a fourth-order Butterworth at
        // 0.45 of the device rate, as two sections at the Butterworth Q pair.
        val edge = deviceRate * 0.45f
        upA.lowPass(rate, edge, BUTTERWORTH_4_QA)
        upB.lowPass(rate, edge, BUTTERWORTH_4_QB)
        downA.lowPass(rate, edge, BUTTERWORTH_4_QA)
        downB.lowPass(rate, edge, BUTTERWORTH_4_QB)
        input.highPass(rate, spec.inputHighPassHz, BUTTERWORTH_Q)
        if (spec.brightDb != 0f) bright.highShelf(rate, BRIGHT_HZ, spec.brightDb) else bright.bypass()
        couplingCoefficient = onePole(rate, spec.couplingHz)
        millerCoefficient = onePole(rate, spec.millerHz)
        sagAttack = onePole(rate, SAG_ATTACK_HZ)
        sagRelease = onePole(rate, SAG_RELEASE_HZ)
        // Force the tone controls to be recomputed for the new rate.
        bassFor = Float.NaN
        midFor = Float.NaN
        trebleFor = Float.NaN
        presenceFor = Float.NaN
        refreshKnobFilters()
    }

    private fun refreshKnobFilters() {
        val b = bass.value
        if (b != bassFor) {
            lowShelf.lowShelf(rate, spec.bassHz, (b - 5f) * TONE_DB_PER_STEP)
            bassFor = b
        }
        val m = mid.value
        if (m != midFor) {
            midPeak.peaking(rate, spec.midHz, spec.midNoonDb + (m - 5f) * MID_DB_PER_STEP, spec.midQ)
            midFor = m
        }
        val t = treble.value
        if (t != trebleFor) {
            highShelf.highShelf(rate, spec.trebleHz, (t - 5f) * TONE_DB_PER_STEP)
            trebleFor = t
        }
        val p = presence.value
        if (p != presenceFor) {
            if (spec.cut) {
                // Closes as it is turned up: wide open at 0, down to a few kilohertz at 10.
                val cutoff = CUT_OPEN_HZ * (CUT_CLOSED_HZ / CUT_OPEN_HZ).pow(p / 10f)
                post.lowPass(rate, cutoff, BUTTERWORTH_Q)
            } else {
                post.highShelf(rate, spec.presenceHz, (p - 5f) * PRESENCE_DB_PER_STEP)
            }
            presenceFor = p
        }
    }

    override fun process(buffer: FloatArray, count: Int) {
        refreshKnobFilters()
        // The first stage's gain follows the knob on a curve that keeps the bottom third clean-ish
        // and the top third saturated, which is how the knob on an amp feels.
        val knob = gain.value / 10f
        val firstGain = 1f + knob * knob * spec.stageGains[0]
        val out = master.value / 10f
        val drive = spec.powerDrive
        val sagAmount = spec.sag
        val up = sagAttack
        val down = sagRelease
        val cc = couplingCoefficient
        val mc = millerCoefficient
        val gains = spec.stageGains
        val biases = spec.stageBiases
        val rests = restingOutput
        val coupling = couplingState
        val miller = millerState
        val n = stages

        var env = envelope
        for (i in 0 until count) {
            var y = 0f
            // Two oversampled samples per input sample: the sample itself, then a zero, both through
            // the image filter, which turns the pair into a band-limited signal at twice the rate.
            for (phase in 0 until OVERSAMPLE) {
                var v = if (phase == 0) buffer[i] * OVERSAMPLE else 0f
                v = upB.process(upA.process(v))

                v = bright.process(input.process(v))

                for (s in 0 until n) {
                    val g = if (s == 0) firstGain else gains[s]
                    val shaped = tanh(v * g + biases[s]) - rests[s]
                    // Coupling capacitor: a high-pass that takes the stage's offset back out.
                    coupling[s] += cc * (shaped - coupling[s])
                    val coupled = shaped - coupling[s]
                    // Miller capacitance: a gentle low-pass that keeps the fizz from stacking up.
                    miller[s] += mc * (coupled - miller[s])
                    v = miller[s]
                }

                v = highShelf.process(midPeak.process(lowShelf.process(v)))

                val magnitude = abs(v)
                env += (if (magnitude > env) up else down) * (magnitude - env)
                val sagGain = 1f / (1f + sagAmount * env)
                v = tanh(v * drive * sagGain)

                v = post.process(v)

                v = downB.process(downA.process(v))
                if (phase == OVERSAMPLE - 1) y = v
            }
            val scaled = y * out
            // A presence shelf is a boost after the last clipper, and the only honest way to keep a
            // boost after a clipper inside full scale is to clip it again.
            buffer[i] = if (scaled > 1f) 1f else if (scaled < -1f) -1f else scaled
        }
        envelope = env
    }

    override fun reset() {
        upA.reset()
        upB.reset()
        downA.reset()
        downB.reset()
        input.reset()
        bright.reset()
        couplingState.fill(0f)
        millerState.fill(0f)
        lowShelf.reset()
        midPeak.reset()
        highShelf.reset()
        post.reset()
        envelope = 0f
    }

    companion object {
        const val OVERSAMPLE = 2

        private const val BUTTERWORTH_Q = 0.7071f
        private const val BUTTERWORTH_4_QA = 0.5412f
        private const val BUTTERWORTH_4_QB = 1.3066f

        private const val BRIGHT_HZ = 2500f
        private const val TONE_DB_PER_STEP = 2.4f
        private const val MID_DB_PER_STEP = 2.0f
        private const val PRESENCE_DB_PER_STEP = 1.6f
        private const val CUT_OPEN_HZ = 12_000f
        private const val CUT_CLOSED_HZ = 2_500f

        /** ~20 ms in, ~200 ms out: the power supply dropping under a chord and recovering. */
        private const val SAG_ATTACK_HZ = 8f
        private const val SAG_RELEASE_HZ = 0.8f

        private fun onePole(sampleRate: Float, cutoffHz: Float): Float =
            (1f - exp(-2f * PI.toFloat() * cutoffHz / sampleRate)).coerceIn(0f, 1f)

        /** Blackface: scooped stack, lots of headroom, bright. */
        val AMERICAN_CLEAN = Spec(
            label = "American Clean",
            inputHighPassHz = 40f, brightDb = 0f,
            stageGains = floatArrayOf(4f, 1.2f), stageBiases = floatArrayOf(0.1f, 0.1f),
            couplingHz = 30f, millerHz = 10_000f,
            bassHz = 100f, midHz = 500f, midQ = 0.7f, midNoonDb = -6f, trebleHz = 2000f,
            powerDrive = 0.8f, sag = 0.2f, presenceHz = 3500f, cut = false,
        )

        /** Tweed: breaks up early, warm, the tone stack does little. */
        val TWEED = Spec(
            label = "Tweed",
            inputHighPassHz = 50f, brightDb = 0f,
            stageGains = floatArrayOf(10f, 2f), stageBiases = floatArrayOf(0.2f, 0.15f),
            couplingHz = 40f, millerHz = 7_000f,
            bassHz = 120f, midHz = 600f, midQ = 0.6f, midNoonDb = -1f, trebleHz = 1800f,
            powerDrive = 1.5f, sag = 0.8f, presenceHz = 3000f, cut = false,
        )

        /** Plexi: mid-forward, the crunch from the power amp as much as the preamp. */
        val BRITISH_CRUNCH = Spec(
            label = "British Crunch",
            inputHighPassHz = 70f, brightDb = 2f,
            stageGains = floatArrayOf(16f, 2.2f, 1.6f), stageBiases = floatArrayOf(0.15f, 0.2f, 0.1f),
            couplingHz = 60f, millerHz = 8_000f,
            bassHz = 110f, midHz = 650f, midQ = 0.8f, midNoonDb = 1f, trebleHz = 2200f,
            powerDrive = 1.3f, sag = 0.5f, presenceHz = 4000f, cut = false,
        )

        /** AC: jangly top from a bright input, a cut control rather than presence. */
        val BRITISH_CHIME = Spec(
            label = "British Chime",
            inputHighPassHz = 60f, brightDb = 6f,
            stageGains = floatArrayOf(12f, 2f), stageBiases = floatArrayOf(0.2f, 0.2f),
            couplingHz = 50f, millerHz = 9_000f,
            bassHz = 100f, midHz = 1000f, midQ = 0.7f, midNoonDb = -4f, trebleHz = 3000f,
            powerDrive = 1.2f, sag = 0.6f, presenceHz = 0f, cut = true,
        )

        /** Modern: tight, four cascaded stages, scooped. */
        val MODERN_HIGH_GAIN = Spec(
            label = "Modern High Gain",
            inputHighPassHz = 120f, brightDb = 3f,
            stageGains = floatArrayOf(30f, 2.5f, 2f, 1.8f),
            stageBiases = floatArrayOf(0.1f, 0.15f, 0.1f, 0.15f),
            couplingHz = 80f, millerHz = 7_500f,
            bassHz = 100f, midHz = 600f, midQ = 0.9f, midNoonDb = -5f, trebleHz = 2500f,
            powerDrive = 1.0f, sag = 0.15f, presenceHz = 4500f, cut = false,
        )
    }
}
