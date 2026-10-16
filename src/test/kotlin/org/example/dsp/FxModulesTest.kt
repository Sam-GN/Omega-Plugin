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
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A steady sine at [hz], one second of it unless [seconds] says otherwise. */
private fun tone(hz: Float, rate: Float, seconds: Float = 1f): FloatArray {
    val frames = (rate * seconds).toInt()
    return FloatArray(frames) { sin(2.0 * PI * hz * it / rate).toFloat() }
}

/**
 * Amplitude of the component at [hz], over the back half of [buffer].
 *
 * One DFT bin evaluated directly, scaled so that a pure sine of amplitude A reads A. That scaling is
 * the point: it lets an assertion be written against a level worked out on paper rather than against
 * whatever the implementation happens to produce.
 */
private fun magnitudeAt(buffer: FloatArray, hz: Float, rate: Float): Float {
    val from = buffer.size / 2
    var re = 0.0
    var im = 0.0
    for (i in from until buffer.size) {
        val theta = 2.0 * PI * hz * i / rate
        re += buffer[i] * cos(theta)
        im += buffer[i] * sin(theta)
    }
    return (2.0 * hypot(re, im) / (buffer.size - from)).toFloat()
}

/**
 * Peak level over the back half of a buffer.
 *
 * The back half specifically: every filter and every envelope follower in here needs some signal
 * before it settles, and measuring across the start would be measuring the transient rather than
 * the thing under test.
 */
private fun settledPeak(buffer: FloatArray): Float {
    var peak = 0f
    for (i in buffer.size / 2 until buffer.size) {
        val v = abs(buffer[i])
        if (v > peak) peak = v
    }
    return peak
}

class EqTest {

    @Test
    fun `an untouched eq is bit-transparent, not merely close`() {
        val eq = Eq()
        eq.prepare(44_100f, 512)
        eq.reset()

        val input = tone(440f, 44_100f, 0.1f)
        val output = input.copyOf()
        eq.process(output, output.size)

        // Exact equality, not a tolerance. Every band is at unity and is skipped rather than run
        // flat, so a module the user has not touched must return the samples it was handed.
        assertContentEquals(input, output)
    }

    @Test
    fun `the low cut removes what is below it and keeps what is above`() {
        val below = runEq(50f) { it.lowCut.value = 300f }
        val above = runEq(2000f) { it.lowCut.value = 300f }

        // A second-order Butterworth high-pass at 300 Hz sits about (50/300)^2 down at 50 Hz, which
        // is roughly -31 dB, or a linear 0.028. Generous room around a number worked out from the
        // filter order rather than read off this implementation.
        assertTrue(below < 0.06f, "50 Hz through a 300 Hz low cut should be gone, was $below")
        assertTrue(above > 0.95f, "2 kHz should pass a 300 Hz low cut untouched, was $above")
    }

    @Test
    fun `a mid cut removes the level it says it does, at the frequency it names`() {
        val cut = runEq(1000f) {
            it.midFrequency.value = 1000f
            it.midGain.value = -12f
            it.midQ.value = 1f
        }
        // -12 dB is a linear 0.251. A peaking filter is exact at its centre frequency whatever the Q.
        assertEquals(0.251f, cut, 0.02f)
    }

    @Test
    fun `a bell tuned somewhere else leaves the tone alone`() {
        val untouched = runEq(3000f) {
            it.midFrequency.value = 250f
            it.midGain.value = -18f
            it.midQ.value = 3f
        }
        assertTrue(untouched > 0.9f, "a 250 Hz notch should not touch 3 kHz, was $untouched")
    }

    @Test
    fun `the corner frequency follows the device rate rather than a hardcoded 44100`() {
        val at44k = runEq(50f, rate = 44_100f) { it.lowCut.value = 300f }
        val at96k = runEq(50f, rate = 96_000f) { it.lowCut.value = 300f }

        // The same knob means the same *frequency* on both devices. Sized against a fixed 44.1 kHz
        // the corner would land at 653 Hz on a 96 kHz device and the two would diverge badly — the
        // exact bug that once pinned the delay line and the reverb tunings.
        assertEquals(at44k, at96k, 0.02f)
    }

    @Test
    fun `it collapses to four knobs and expands to nine`() {
        val eq = Eq()
        assertEquals(9, eq.parameters.size)
        assertEquals(4, eq.compactParameters)
        // The four shown are the ones that fix an instrument, in reach order.
        assertContentEquals(
            listOf("Low Cut", "Mid F", "Mid", "High"),
            eq.parameters.take(eq.compactParameters).map { it.name },
        )
    }

    private fun runEq(toneHz: Float, rate: Float = 44_100f, configure: (Eq) -> Unit): Float {
        val eq = Eq()
        configure(eq)
        eq.prepare(rate, 1024)
        eq.reset()
        val buffer = tone(toneHz, rate, 0.5f)
        eq.process(buffer, buffer.size)
        return settledPeak(buffer)
    }
}

class TremoloTest {

    @Test
    fun `zero depth is exact passthrough`() {
        val tremolo = Tremolo()
        tremolo.depth.value = 0f
        tremolo.prepare(44_100f, 512)
        tremolo.reset()

        val input = tone(440f, 44_100f, 0.1f)
        val output = input.copyOf()
        tremolo.process(output, output.size)

        assertContentEquals(input, output)
    }

    @Test
    fun `full depth dips to silence once per cycle, at the rate it was asked for`() {
        val tremolo = Tremolo()
        tremolo.rate.value = 2f
        tremolo.depth.value = 1f
        tremolo.shape.value = 0f
        tremolo.prepare(44_100f, 512)
        tremolo.reset()

        // One second of DC, so the output *is* the gain envelope with nothing else in it.
        val buffer = FloatArray(44_100) { 1f }
        tremolo.process(buffer, buffer.size)

        var dips = 0
        var inDip = false
        for (sample in buffer) {
            if (sample < 0.02f && !inDip) {
                dips++
                inDip = true
            } else if (sample > 0.1f) {
                inDip = false
            }
        }
        // Two hertz for one second is two cycles, therefore two visits to the bottom.
        assertEquals(2, dips, "2 Hz for one second should dip twice, saw $dips")
        assertTrue(buffer.max() > 0.99f, "it should still reach unity at the top of the swing")
    }

    @Test
    fun `it never makes the signal louder than it found it`() {
        val tremolo = Tremolo()
        tremolo.rate.value = 8f
        tremolo.depth.value = 1f
        tremolo.shape.value = 1f
        tremolo.prepare(44_100f, 512)
        tremolo.reset()

        val buffer = FloatArray(44_100) { 1f }
        tremolo.process(buffer, buffer.size)

        assertTrue(buffer.max() <= 1.0001f, "modulation is one-sided, was ${buffer.max()}")
        assertTrue(buffer.min() >= -0.0001f, "gain should never go negative, was ${buffer.min()}")
    }
}

class ChorusTest {

    @Test
    fun `zero mix is exact passthrough`() {
        val chorus = Chorus()
        chorus.mix.value = 0f
        chorus.prepare(44_100f, 512)
        chorus.reset()

        val input = tone(440f, 44_100f, 0.1f)
        val output = input.copyOf()
        chorus.process(output, output.size)

        assertContentEquals(input, output)
    }

    @Test
    fun `full wet is a moving copy, not the dry signal`() {
        val chorus = Chorus()
        chorus.mix.value = 1f
        chorus.depth.value = 1f
        chorus.rate.value = 2f
        chorus.prepare(44_100f, 2048)
        chorus.reset()

        val input = tone(440f, 44_100f, 0.5f)
        val output = input.copyOf()
        chorus.process(output, output.size)

        var differences = 0
        for (i in output.size / 2 until output.size) {
            if (abs(output[i] - input[i]) > 0.01f) differences++
        }
        assertTrue(differences > output.size / 4, "a fully wet chorus should not resemble its input")
    }

    @Test
    fun `it stays bounded with every knob at maximum`() {
        val chorus = Chorus()
        chorus.mix.value = 1f
        chorus.depth.value = 1f
        chorus.rate.value = 5f
        chorus.prepare(44_100f, 2048)
        chorus.reset()

        val buffer = tone(220f, 44_100f, 1f)
        chorus.process(buffer, buffer.size)

        // Two voices at half gain each, and no feedback path — so full wet cannot exceed full dry.
        assertTrue(buffer.max() <= 1.05f, "peaked at ${buffer.max()}")
        assertTrue(buffer.all { it.isFinite() })
    }
}

class TransientShaperTest {

    @Test
    fun `both knobs at zero is exact passthrough`() {
        val shaper = TransientShaper()
        shaper.prepare(44_100f, 512)
        shaper.reset()

        val input = pluck(44_100f)
        val output = input.copyOf()
        shaper.process(output, output.size)

        // The gain law is written so that zero and zero multiply out to exactly 1.0 rather than to
        // something a rounding away from it. A "transparent" module that is not bit-transparent is a
        // module you cannot leave in the chain.
        assertContentEquals(input, output)
    }

    @Test
    fun `cutting the attack lowers the front of a note and leaves its body`() {
        val flat = shape(attackDb = 0f)
        val cut = shape(attackDb = -12f)

        val rate = 44_100
        val onset = 0 until (rate * 0.01f).toInt()
        val body = (rate * 0.2f).toInt() until (rate * 0.3f).toInt()

        val flatOnset = onset.maxOf { abs(flat[it]) }
        val cutOnset = onset.maxOf { abs(cut[it]) }
        val flatBody = body.maxOf { abs(flat[it]) }
        val cutBody = body.maxOf { abs(cut[it]) }

        assertTrue(cutOnset < flatOnset * 0.8f, "the attack should come down: $cutOnset vs $flatOnset")
        // And the sustained part must not: that is the whole difference from a compressor, which
        // cannot touch one without moving the other.
        assertEquals(flatBody, cutBody, flatBody * 0.1f)
    }

    @Test
    fun `it is level-independent, so a quiet note is shaped like a loud one`() {
        val loud = shape(attackDb = -12f, amplitude = 1f)
        val quiet = shape(attackDb = -12f, amplitude = 0.1f)

        val onset = 0 until (44_100 * 0.01f).toInt()
        val body = (44_100 * 0.2f).toInt() until (44_100 * 0.3f).toInt()

        val loudRatio = onset.maxOf { abs(loud[it]) } / body.maxOf { abs(loud[it]) }
        val quietRatio = onset.maxOf { abs(quiet[it]) } / body.maxOf { abs(quiet[it]) }

        // The difference is taken relative to the slow envelope, so twenty dB of playing dynamics
        // must not change how much of the transient is removed.
        assertEquals(loudRatio, quietRatio, loudRatio * 0.1f)
    }

    private fun shape(attackDb: Float, amplitude: Float = 1f): FloatArray {
        val shaper = TransientShaper()
        shaper.attack.value = attackDb
        shaper.prepare(44_100f, 2048)
        shaper.reset()
        val buffer = pluck(44_100f, amplitude)
        shaper.process(buffer, buffer.size)
        return buffer
    }

    /** A crude pluck: a loud first 10 ms over a quieter steady tone, which is the shape at issue. */
    private fun pluck(rate: Float, amplitude: Float = 1f): FloatArray {
        val attackFrames = (rate * 0.01f).toInt()
        return FloatArray((rate * 0.4f).toInt()) { i ->
            val envelope = if (i < attackFrames) 1f else 0.3f
            (sin(2.0 * PI * 220.0 * i / rate).toFloat()) * envelope * amplitude
        }
    }
}

class DeEsserTest {

    @Test
    fun `a tone below the split frequency is left where it was`() {
        val level = runDeEsser(toneHz = 200f)
        assertTrue(level > 0.75f, "200 Hz should pass a 5 kHz de-esser, was $level")
    }

    @Test
    fun `a loud tone above it is pushed down`() {
        val level = runDeEsser(toneHz = 8000f)
        assertTrue(level < 0.7f, "8 kHz over the threshold should be reduced, was $level")
    }

    @Test
    fun `it never reduces by more than the range knob allows`() {
        // A deliberately absurd overshoot: 40 dB over the threshold against a 6 dB range. Without
        // the floor the band would be ducked into silence, and the cure would be louder than the
        // complaint.
        val level = runDeEsser(toneHz = 8000f, threshold = -60f, range = 6f)
        // 6 dB down is a linear 0.5. Some margin below for the part of the tone that leaks into the
        // low path and is not ducked at all.
        assertTrue(level > 0.4f, "reduction exceeded the 6 dB range, level was $level")
    }

    private fun runDeEsser(toneHz: Float, threshold: Float = -30f, range: Float = 12f): Float {
        val deEsser = DeEsser()
        deEsser.frequency.value = 5000f
        deEsser.threshold.value = threshold
        deEsser.range.value = range
        deEsser.prepare(44_100f, 2048)
        deEsser.reset()
        val buffer = tone(toneHz, 44_100f, 0.5f)
        for (i in buffer.indices) buffer[i] *= 0.8f
        deEsser.process(buffer, buffer.size)
        return settledPeak(buffer) / 0.8f
    }
}

class BiquadLowPassTest {

    @Test
    fun `the low-pass rolls off at the rate its order says it does`() {
        // A second-order Butterworth is |H| = 1 / sqrt(1 + (f/f0)^4). At the corner that is 1/sqrt(2)
        // = 0.707; an octave above it is 1/sqrt(17) = 0.243. Both numbers come from the filter order,
        // not from this implementation.
        assertEquals(1.00f, gainAt(250f, corner = 1000f), 0.02f)
        assertEquals(0.707f, gainAt(1000f, corner = 1000f), 0.02f)
        assertEquals(0.243f, gainAt(2000f, corner = 1000f), 0.02f)
    }

    private fun gainAt(toneHz: Float, corner: Float): Float {
        val filter = Biquad()
        filter.lowPass(44_100f, corner, 0.707f)
        val buffer = tone(toneHz, 44_100f, 0.2f)
        for (i in buffer.indices) buffer[i] = filter.process(buffer[i])
        return settledPeak(buffer)
    }
}

class OctaveTest {

    @Test
    fun `a fresh octave is bit-transparent, not merely close`() {
        val octave = Octave()
        octave.prepare(44_100f, 512)
        octave.reset()

        val input = tone(440f, 44_100f, 0.1f)
        val output = input.copyOf()
        octave.process(output, output.size)

        // MIX defaults to zero, so a slot just switched to Octave changes nothing at all until the
        // knob is moved. Exact equality: an equal-power blend that wrote `1.0 * x + 0.0 * sub` back
        // would be "close", and close is what hides a module you cannot leave in the chain.
        assertContentEquals(input, output)
    }

    @Test
    fun `a 220 Hz note comes out at 110 Hz, and not at 220`() {
        val output = divide(220f, 44_100f)
        val fundamental = magnitudeAt(output, 110f, 44_100f)
        val original = magnitudeAt(output, 220f, 44_100f)

        // A square of peak p has a fundamental of 4p/pi. The square is driven at 1/sqrt(2) of the
        // input peak, so for a 0.8 input that is 4 * 0.707 * 0.8 / pi = 0.72, less a little to the
        // envelope follower's ripple and to the 15 Hz DC blocker. Asserted well below that, because
        // the claim under test is "an octave down is present and strong", not a level.
        assertTrue(fundamental > 0.45f, "110 Hz should dominate the output, was $fundamental")
        assertTrue(fundamental < 1f, "and stay inside the format, was $fundamental")

        // The real proof of division rather than distortion: a square at 110 Hz has odd harmonics
        // only — 330, 550 — so there is nothing at 220 for it to put there. Anything found at the
        // input frequency is dry signal leaking through or a mistracked flip.
        assertTrue(original < 0.1f, "the input frequency should be gone, was $original")
    }

    @Test
    fun `it divides by two at 96 kHz exactly as it does at 44,1`() {
        val at44k = magnitudeAt(divide(220f, 44_100f), 110f, 44_100f)
        val at96k = magnitudeAt(divide(220f, 96_000f), 110f, 96_000f)

        // The standing guard against the hardcoded-44100 bug. Every coefficient here — two filters,
        // three followers and a DC blocker — is derived from the rate handed to prepare, so the same
        // knobs must produce the same sub on either device.
        assertEquals(at44k, at96k, 0.05f)
    }

    @Test
    fun `a note with harmonics still divides, rather than following one of them`() {
        // Closer to a plucked string than a sine is: a 110 Hz fundamental with a second and a third
        // harmonic under it. Both put extra wiggles near the zero crossing, which is exactly what
        // the tracking filter and the comparator's hysteresis are there to ignore.
        val rate = 44_100f
        val input = FloatArray((rate * 0.5f).toInt()) { i ->
            val theta = 2.0 * PI * 110.0 * i / rate
            ((sin(theta) + 0.5 * sin(2 * theta) + 0.3 * sin(3 * theta)) / 1.8).toFloat() * 0.9f
        }
        val output = run(input, rate)

        val sub = magnitudeAt(output, 55f, rate)
        val fundamental = magnitudeAt(output, 110f, rate)

        // 55 Hz is an octave below the note's fundamental and is not present in the input at all —
        // it can only have been synthesised. A divider that had latched onto the second harmonic
        // would have produced 110 Hz instead, so the comparison is the assertion.
        assertTrue(sub > 0.3f, "the octave down should be strong, was $sub")
        assertTrue(sub > fundamental * 3f, "55 Hz should dominate 110 Hz: $sub vs $fundamental")
    }

    @Test
    fun `silence in, silence out`() {
        val octave = Octave()
        octave.mix.value = 1f
        octave.prepare(44_100f, 512)
        octave.reset()

        val buffer = FloatArray(4410)
        octave.process(buffer, buffer.size)

        // A comparator with nothing to compare is the one thing that can invent a signal from
        // nothing, so this is the test that says it does not.
        assertTrue(buffer.all { it == 0f }, "peaked at ${buffer.maxOf { abs(it) }}")
    }

    @Test
    fun `it stays shut below the threshold and opens above it`() {
        val rate = 44_100f
        // -54 dBFS against a -42 dB threshold: string noise and the far tail of a decay. A divider
        // without a floor chatters here, which is heard as the octave warbling about a semitone.
        val quiet = run(scaled(tone(110f, rate, 0.5f), 0.002f), rate)
        assertTrue(quiet.all { abs(it) < 1e-4f }, "the sub opened on -54 dBFS, peak ${quiet.maxOf { abs(it) }}")

        // And the same signal 30 dB up, so the test cannot pass by the module simply being dead.
        val played = run(scaled(tone(110f, rate, 0.5f), 0.063f), rate)
        assertTrue(settledPeak(played) > 0.02f, "the sub should open at -24 dBFS, was ${settledPeak(played)}")
    }

    @Test
    fun `it stays bounded with every knob at its extreme`() {
        val rate = 44_100f
        val octave = Octave()
        octave.track.value = octave.track.max
        octave.tone.value = octave.tone.max
        octave.threshold.value = octave.threshold.min
        octave.mix.value = 0.5f
        octave.prepare(rate, 2048)
        octave.reset()

        val buffer = tone(82.4f, rate, 1f)
        octave.process(buffer, buffer.size)

        // Equal power at the middle of the knob is 0.707 of each. The dry is full scale and the sub
        // peaks near 0.707 of it plus a little filter overshoot, so the worst instantaneous sum is
        // sqrt(1 + 0.78^2) = 1.27 — over unity, as any blend of two signals can be, and clamped once
        // at the device boundary rather than by every module in the chain.
        assertTrue(buffer.all { it.isFinite() })
        assertTrue(buffer.max() <= 1.3f, "peaked at ${buffer.max()}")
        assertTrue(buffer.min() >= -1.3f, "troughed at ${buffer.min()}")
    }

    @Test
    fun `it adds no latency, because a divider does not need any`() {
        val rate = 44_100f
        val octave = Octave()
        octave.mix.value = 1f
        octave.prepare(rate, 512)
        octave.reset()

        // The whole reason this is a divider rather than a shifter. Sample n of the output is a
        // function of samples up to n and no further, so a block boundary is not a seam: processing
        // one buffer in one call and in thirty-two must give the same samples, bit for bit.
        val whole = tone(220f, rate, 0.2f)
        octave.process(whole, whole.size)

        octave.reset()
        val split = tone(220f, rate, 0.2f)
        var offset = 0
        val block = FloatArray(256)
        while (offset + 256 <= split.size) {
            System.arraycopy(split, offset, block, 0, 256)
            octave.process(block, 256)
            System.arraycopy(block, 0, split, offset, 256)
            offset += 256
        }
        assertContentEquals(whole.copyOf(offset), split.copyOf(offset))
    }

    private fun scaled(buffer: FloatArray, amplitude: Float): FloatArray {
        for (i in buffer.indices) buffer[i] *= amplitude
        return buffer
    }

    /** Fully wet, so what comes back is the divider's own output and nothing else. */
    private fun divide(hz: Float, rate: Float): FloatArray = run(scaled(tone(hz, rate, 0.5f), 0.8f), rate)

    private fun run(input: FloatArray, rate: Float): FloatArray {
        val octave = Octave()
        octave.mix.value = 1f
        octave.prepare(rate, 4096)
        octave.reset()
        octave.process(input, input.size)
        return input
    }
}

class FxSlotTest {

    @Test
    fun `it offers every type and starts on the first`() {
        val slot = FxSlot()
        assertEquals(FxSlot.typeNames, slot.type.choices)
        assertEquals(FxSlot.typeNames.first(), slot.typeName)
        // The warm-up reaches the unselected types through this, and nothing else does.
        assertEquals(FxSlot.typeNames.size, slot.alternates.size)
    }

    @Test
    fun `its label is positional, and a preset never sees it`() {
        assertEquals("FX 1", FxSlot.labelFor(1))
        assertEquals("FX 7", FxSlot.labelFor(7))
        assertTrue(FxSlot.isSlotName("FX 3"))
        assertTrue(!FxSlot.isSlotName("Reverb"))
        assertTrue(!FxSlot.isSlotName("FX one"))

        val slot = FxSlot(1)
        slot.label = FxSlot.labelFor(4)
        assertEquals("FX 4", slot.name)
    }

    @Test
    fun `changing type fades through silence instead of stepping`() {
        val slot = FxSlot()
        // Both types configured to pass audio untouched, so anything that is not the input is the
        // fade and nothing else.
        slot.applyValues(mapOf("Chorus.Mix" to 0f, "Tremolo.Depth" to 0f))
        slot.selectType("Chorus")
        slot.prepare(44_100f, 64)
        slot.reset()

        val captured = ArrayList<Float>()
        fun run(blocks: Int) {
            repeat(blocks) {
                val block = FloatArray(64) { 1f }
                slot.process(block, block.size)
                block.forEach { captured += it }
            }
        }

        run(4)
        slot.selectType("Tremolo")
        run(40)

        assertTrue(captured.min() < 0.05f, "the fade should reach silence, floor was ${captured.min()}")
        assertEquals(1f, captured.last(), 1e-3f, "and come back to unity on the other side")

        // The point of the fade. A switch that merely swapped effects between blocks would show up
        // here as a single step of 1.0.
        var worst = 0f
        for (i in 1 until captured.size) {
            val step = abs(captured[i] - captured[i - 1])
            if (step > worst) worst = step
        }
        assertTrue(worst < 0.01f, "biggest single-sample step was $worst")
    }

    @Test
    fun `every type's knobs survive a round trip, not just the selected one`() {
        val slot = FxSlot()
        slot.selectType("Chorus")
        slot.applyValues(mapOf("Chorus.Rate" to 3.5f, "Tremolo.Rate" to 7.25f))

        val captured = slot.captureValues()
        assertEquals(3.5f, captured["Chorus.Rate"])
        assertEquals(7.25f, captured["Tremolo.Rate"])

        // Dial something else in, then put the saved sound back.
        slot.applyValues(emptyMap())
        slot.applyValues(captured)
        assertEquals(7.25f, slot.captureValues()["Tremolo.Rate"])
    }

    @Test
    fun `a name this build does not have leaves the slot on what it was`() {
        val slot = FxSlot()
        slot.selectType("Tremolo")
        slot.selectType("Frobnicator")
        // Rather than resolving to whatever now sits at some remembered index.
        assertEquals("Tremolo", slot.typeName)
    }

    @Test
    fun `an untouched slot knows it is untouched`() {
        val slot = FxSlot()
        assertTrue(slot.isUntouched(), "a slot straight out of the box has nothing to lose")

        slot.enabled = true
        assertTrue(!slot.isUntouched())

        slot.enabled = false
        assertTrue(slot.isUntouched())

        slot.applyValues(mapOf("Chorus.Rate" to 4f))
        assertTrue(!slot.isUntouched(), "a knob moved on a type that is not even selected still counts")
    }

    @Test
    fun `the visible parameters follow the selected type`() {
        val slot = FxSlot()
        slot.selectType("Transient")
        // Type, then the shaper's two.
        assertContentEquals(listOf("Type", "Attack", "Sustain"), slot.parameters.map { it.name })

        slot.selectType("Delay")
        assertContentEquals(listOf("Type", "Time", "Feedback", "Mix"), slot.parameters.map { it.name })
    }

    @Test
    fun `preparing binds every type, not only the selected one`() {
        val slot = FxSlot()
        slot.selectType("Chorus")
        slot.prepare(96_000f, 512)
        slot.reset()

        // Switch to a type that was never selected while prepared, and give it a full fade to land.
        slot.selectType("Delay")
        repeat(80) {
            val block = FloatArray(512) { 0.5f }
            slot.process(block, block.size)
            assertTrue(block.all { it.isFinite() })
        }
        assertEquals("Delay", slot.typeName)
    }
}
