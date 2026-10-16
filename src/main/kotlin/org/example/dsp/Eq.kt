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

/**
 * Five-band equaliser: a high-pass, a low shelf, two sweepable bells and a high shelf.
 *
 * The one module the app was most obviously missing. Before it, the only filter anywhere in the
 * signal path was [Overdrive]'s single-pole TONE control, which meant the only way to touch the top
 * end was to run a distortion you did not want.
 *
 * **Four knobs collapsed, nine expanded** — see [compactParameters]. The four are chosen to be the
 * ones that fix a real instrument rather than to be a tidy subset: LOW CUT for the thump a piezo
 * puts under everything, MID F and MID to find and remove the quack that undersaddle pickups have
 * somewhere between 800 Hz and 3 kHz, and HIGH to take the glass off the top. The other five are
 * there when that is not enough, and cost nothing until asked for.
 *
 * **Every band exists at all times.** Expanding does not construct a filter — the `+` button can be
 * pressed while the stream is running, and building anything on that path is precisely what the
 * threading contract forbids. A band the user has not touched sits at unity gain and is skipped by a
 * comparison, so hidden bands cost one branch each and nothing else.
 *
 * Coefficients are re-derived only when a knob has actually moved, and then once per block rather
 * than once per sample. The design arithmetic allocates nothing (see [Biquad]), so doing it on the
 * audio thread is safe; doing it 44 100 times a second would merely be wasteful.
 */
class Eq : AudioProcessor {

    override val name = "EQ"

    @Volatile
    override var enabled = false

    // -------------------------------------------------------------- collapsed

    val lowCut = Parameter("Low Cut", 20f, 500f, 20f, "Hz", skew = 2f)
    val midFrequency = Parameter("Mid F", 200f, 5000f, 800f, "Hz", skew = 2f)
    val midGain = Parameter("Mid", -18f, 18f, 0f, "dB")
    val highGain = Parameter("High", -18f, 18f, 0f, "dB")

    // --------------------------------------------------------------- expanded

    val midQ = Parameter("Mid Q", 0.3f, 6f, 1f, skew = 1.5f)
    val mid2Frequency = Parameter("Mid2 F", 300f, 8000f, 2800f, "Hz", skew = 2f)
    val mid2Gain = Parameter("Mid2", -18f, 18f, 0f, "dB")
    val lowGain = Parameter("Low", -18f, 18f, 0f, "dB")
    val highFrequency = Parameter("High F", 1500f, 12000f, 4000f, "Hz", skew = 2f)

    override val parameters = listOf(
        lowCut, midFrequency, midGain, highGain,
        midQ, mid2Frequency, mid2Gain, lowGain, highFrequency,
    )

    /** LOW CUT, MID F, MID, HIGH. The rest arrive with the `+`. */
    override val compactParameters = 4

    private var sampleRate = DEFAULT_SAMPLE_RATE

    private val highPass = Biquad()
    private val lowShelf = Biquad()
    private val midBell = Biquad()
    private val mid2Bell = Biquad()
    private val highShelf = Biquad()

    // Which sections are doing anything. A band at unity gain is skipped rather than run flat: a
    // flat biquad is not free, and at nine parameters most of them are flat most of the time.
    private var highPassOn = false
    private var lowShelfOn = false
    private var midBellOn = false
    private var mid2BellOn = false
    private var highShelfOn = false

    /** The knob values the current coefficients were designed for; see [design]. */
    private val designedFor = FloatArray(parameters.size) { Float.NaN }
    private var designedRate = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        // Forces a redesign on the next block: the same knob values mean different coefficients at a
        // different rate, and this is the bug that pins a filter to 44.1 kHz if it is not handled.
        designedRate = 0f
    }

    override fun process(buffer: FloatArray, count: Int) {
        design()

        // Hoisted: the per-sample loop reads locals, never fields.
        val hp = highPass
        val ls = lowShelf
        val m1 = midBell
        val m2 = mid2Bell
        val hs = highShelf
        val hpOn = highPassOn
        val lsOn = lowShelfOn
        val m1On = midBellOn
        val m2On = mid2BellOn
        val hsOn = highShelfOn

        for (i in 0 until count) {
            var x = buffer[i]
            if (hpOn) x = hp.process(x)
            if (lsOn) x = ls.process(x)
            if (m1On) x = m1.process(x)
            if (m2On) x = m2.process(x)
            if (hsOn) x = hs.process(x)
            buffer[i] = x
        }
    }

    override fun reset() {
        highPass.reset()
        lowShelf.reset()
        midBell.reset()
        mid2Bell.reset()
        highShelf.reset()
    }

    /**
     * Re-derives whatever has moved since the last block, and nothing that has not.
     *
     * The guard is worth having twice over: it keeps five sets of transcendentals out of the common
     * block, and — the part that is audible — re-designing a section every block would reset nothing
     * but would still write new coefficients into a filter whose state assumes the old ones, which
     * on a fast knob sweep is a stream of small discontinuities.
     */
    private fun design() {
        val rate = sampleRate
        var changed = designedRate != rate
        for (i in parameters.indices) {
            val v = parameters[i].value
            if (designedFor[i] != v) {
                designedFor[i] = v
                changed = true
            }
        }
        if (!changed) return
        designedRate = rate

        // At the bottom of its travel the knob means "off" rather than "20 Hz", so the section is
        // skipped instead of being run as a filter that does almost nothing.
        highPassOn = lowCut.value > lowCut.min + 0.5f
        if (highPassOn) highPass.highPass(rate, lowCut.value, BUTTERWORTH_Q)

        lowShelfOn = lowGain.value != 0f
        if (lowShelfOn) lowShelf.lowShelf(rate, LOW_SHELF_HZ, lowGain.value)

        midBellOn = midGain.value != 0f
        if (midBellOn) midBell.peaking(rate, midFrequency.value, midGain.value, midQ.value)

        mid2BellOn = mid2Gain.value != 0f
        if (mid2BellOn) mid2Bell.peaking(rate, mid2Frequency.value, mid2Gain.value, MID2_Q)

        highShelfOn = highGain.value != 0f
        if (highShelfOn) highShelf.highShelf(rate, highFrequency.value, highGain.value)
    }

    private companion object {
        /** Maximally flat passband — what a high-pass on an instrument should be. */
        const val BUTTERWORTH_Q = 0.707f

        /** The low shelf's corner. Fixed: a shelf down there is a body-weight control, not a sweep. */
        const val LOW_SHELF_HZ = 180f

        /** The second bell is the wider of the two, for broad tonal moves rather than notching. */
        const val MID2_Q = 0.9f
    }
}
