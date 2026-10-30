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

import org.example.audio.AudioConfig
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParameterTest {

    @Test
    fun `position round-trips through a skewed range`() {
        val p = Parameter("Time", 20f, 1000f, 350f, "ms", skew = 1.5f)
        for (target in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            p.position = target
            assertEquals(target, p.position, 1e-4f, "position $target")
        }
    }

    @Test
    fun `value is clamped to the declared range`() {
        val p = Parameter("Mix", 0f, 1f, 0.5f)
        p.value = 5f
        assertEquals(1f, p.value)
        p.value = -5f
        assertEquals(0f, p.value)
    }

    @Test
    fun `reset restores the default`() {
        val p = Parameter("Gain", -24f, 24f, 0f, "dB")
        p.value = 12f
        p.reset()
        assertEquals(0f, p.value)
    }
}

class MasterVolumeTest {

    @Test
    fun `full volume passes the signal at unity`() {
        val master = MasterVolume()
        master.volume.value = 100f
        master.reset()

        val buffer = FloatArray(512) { 0.4f }
        master.process(buffer, buffer.size)

        assertEquals(0.4f, buffer.last(), 1e-4f)
    }

    @Test
    fun `zero percent is exact silence, not merely quiet`() {
        val master = MasterVolume()
        master.volume.value = 0f
        master.reset()

        val buffer = FloatArray(1024) { 0.5f }
        master.process(buffer, buffer.size)

        assertEquals(0f, buffer.last(), 1e-6f)
    }

    @Test
    fun `switching off ramps down instead of cutting`() {
        val master = MasterVolume()
        master.volume.value = 100f
        master.reset() // starts at unity, switch still on
        master.enabled = false

        val buffer = FloatArray(8192) { 1f }
        master.process(buffer, buffer.size)

        // Slamming to zero on the first sample is exactly what produces the click.
        assertTrue(buffer[0] > 0.99f, "mute continued from unity, got ${buffer[0]}")
        // A clean ramp: monotonically falling, no overshoot or ringing.
        for (i in 1 until buffer.size) {
            assertTrue(buffer[i] <= buffer[i - 1] + 1e-6f, "ramp still falling at sample $i")
        }
        // 4410 samples is 100 ms at 44.1 kHz — the mute must feel immediate, not faded.
        assertTrue(buffer[4410] < 0.01f, "silent within 100 ms, got ${buffer[4410]}")
    }

    @Test
    fun `switching back on ramps up again`() {
        val master = MasterVolume()
        master.volume.value = 100f
        master.enabled = false
        master.reset() // starts muted
        master.enabled = true

        val buffer = FloatArray(8192) { 1f }
        master.process(buffer, buffer.size)

        assertTrue(buffer[0] < 0.01f, "unmute started from silence, got ${buffer[0]}")
        assertTrue(buffer.last() > 0.99f, "returned to unity, got ${buffer.last()}")
    }
}

class BoardLevelTest {

    @Test
    fun `inactive it is bit-transparent whatever the knob and the switch say`() {
        // Outside split mode the stage is still in the chain, and a stage that merely got within a
        // rounding error of unity would be one you could not leave there.
        val level = BoardLevel()
        level.volume.value = 10f
        level.enabled = false
        level.reset()

        val input = FloatArray(512) { sin(it * 0.07).toFloat() * 0.6f }
        val buffer = input.copyOf()
        level.process(buffer, buffer.size)

        assertContentEquals(input, buffer)
    }

    @Test
    fun `active at its default it sits at minus six dB`() {
        // 71 % through the squared taper is 0.71² = 0.504, a hair under -6 dB. Two boards fed the
        // same guitar are correlated, so two at that gain sum back to the level one board had —
        // which is what keeps switching split on from clipping.
        val level = BoardLevel()
        level.active = true
        level.reset()

        val buffer = FloatArray(256) { 0.8f }
        level.process(buffer, buffer.size)

        assertEquals(0.8f * 0.71f * 0.71f, buffer.last(), 1e-4f)
    }

    @Test
    fun `going inactive ramps back to unity and then stops touching the buffer`() {
        val level = BoardLevel()
        level.active = true
        level.reset() // at 0.5
        level.active = false

        val ramp = FloatArray(8192) { 1f }
        level.process(ramp, ramp.size)
        assertTrue(ramp[0] < 0.51f, "the return to unity started from where it was, got ${ramp[0]}")
        for (i in 1 until ramp.size) {
            assertTrue(ramp[i] >= ramp[i - 1] - 1e-6f, "ramp still rising at sample $i")
        }
        // 8192 samples is 186 ms at 44.1 kHz, against an 11 ms time constant: within a rounding
        // error of unity, and the snap at the start of the next block makes it exact.
        assertTrue(ramp.last() > 0.9999f, "all but arrived, got ${ramp.last()}")

        val input = FloatArray(256) { sin(it * 0.05).toFloat() }
        val after = input.copyOf()
        level.process(after, after.size)
        assertContentEquals(input, after)
    }
}

class GainTest {

    @Test
    fun `plus six dB roughly doubles amplitude`() {
        val gain = Gain()
        gain.gainDb.value = 6f
        gain.reset()

        val buffer = FloatArray(512) { 0.25f }
        gain.process(buffer, buffer.size)

        assertEquals(0.5f, buffer.last(), 0.005f)
    }

    @Test
    fun `unity gain leaves the signal alone`() {
        val gain = Gain()
        gain.reset()
        val buffer = FloatArray(256) { 0.3f }
        gain.process(buffer, buffer.size)
        buffer.forEach { assertEquals(0.3f, it, 1e-4f) }
    }
}

class DelayTest {

    @Test
    fun `impulse reappears after the configured delay`() {
        val delay = Delay()
        delay.time.value = 100f // 4410 samples at 44.1 kHz
        delay.feedback.value = 0f
        delay.mix.value = 1f // fully wet, so the output is the delay line alone
        delay.reset()

        val expectedOffset = (100f * AudioConfig.SAMPLE_RATE / 1000f).toInt()
        val buffer = FloatArray(8192)
        buffer[0] = 1f
        delay.process(buffer, buffer.size)

        assertEquals(1f, buffer[expectedOffset], 1e-3f, "echo at sample $expectedOffset")
        assertEquals(0f, buffer[expectedOffset - 50], 1e-4f, "silence before the echo")
    }

    @Test
    fun `feedback decays rather than runs away`() {
        val delay = Delay()
        delay.time.value = 50f
        delay.feedback.value = 0.9f
        delay.mix.value = 1f
        delay.reset()

        val buffer = FloatArray(44_100)
        buffer[0] = 1f
        delay.process(buffer, buffer.size)

        assertTrue(buffer.all { it.isFinite() && abs(it) <= 1.01f }, "delay output stayed bounded")
    }
}

class DrivesTest {

    private fun everyDrive(): List<AudioProcessor> =
        listOf(SmoothDrive(), ScreamerDrive(), RatDrive(), FuzzDrive(), TubeDrive())

    /** Every knob to its loudest end: full drive, full level, tone wide open. */
    private fun crank(drive: AudioProcessor) {
        for (parameter in drive.parameters) {
            parameter.value = when (parameter.name) {
                "Bass", "Treble" -> 12f
                "Filter" -> 0f
                else -> parameter.max
            }
        }
    }

    @Test
    fun `the overdrive offers the five drives with Smooth first`() {
        assertEquals(listOf("Smooth", "Screamer", "Rat", "Fuzz", "Tube"), Overdrive.typeNames)
        val overdrive = Overdrive()
        assertEquals("Smooth", overdrive.typeName, "the default is the sound every old preset was saved with")
        assertEquals(listOf("Type", "Drive", "Tone", "Level"), overdrive.parameters.map { it.name })
        assertEquals(5, overdrive.alternates.size, "every type is built up front for the warm-up")
    }

    @Test
    fun `the knob row is the selected type's own`() {
        val overdrive = Overdrive()
        overdrive.selectType("Fuzz")
        assertEquals(listOf("Type", "Fuzz", "Volume"), overdrive.parameters.map { it.name })
        overdrive.selectType("Tube")
        assertEquals(listOf("Type", "Gain", "Bass", "Treble", "Level"), overdrive.parameters.map { it.name })
        overdrive.selectType("Rat")
        assertEquals(listOf("Type", "Distortion", "Filter", "Volume"), overdrive.parameters.map { it.name })
    }

    @Test
    fun `every drive stays inside full scale at its loudest, at both rates`() {
        for (rate in listOf(44_100f, 96_000f)) {
            for (drive in everyDrive()) {
                drive.prepare(rate, 256)
                crank(drive)
                drive.reset()
                // A square wave at full scale, then a sine, so both the clipper's corners and its
                // filters are driven as hard as they can be.
                val square = FloatArray(4096) { if ((it / 50) % 2 == 0) 1f else -1f }
                drive.process(square, square.size)
                val sine = FloatArray(4096) { sin(it * 0.1).toFloat() }
                drive.process(sine, sine.size)
                for (buffer in listOf(square, sine)) {
                    assertTrue(buffer.all { it.isFinite() }, "${drive.name} produced a non-finite sample at $rate")
                    val peak = buffer.maxOf { abs(it) }
                    assertTrue(peak <= 1.01f, "${drive.name} reached $peak at $rate Hz; a drive must not clip the engine")
                }
            }
        }
    }

    @Test
    fun `every drive is silent on silence, including the biased ones`() {
        // The fuzz and the tube stage push the clipper off centre. Done naively that leaves an
        // offset on the output for nothing in, which is a thump when the pedal is switched on.
        for (drive in everyDrive()) {
            crank(drive)
            drive.reset()
            val buffer = FloatArray(2048)
            drive.process(buffer, buffer.size)
            for ((i, v) in buffer.withIndex()) assertEquals(0f, v, 1e-6f, "${drive.name} sample $i")
        }
    }

    @Test
    fun `every drive actually drives`() {
        // Each adds harmonics: a pure sine comes out with more energy above the fundamental than it
        // went in with. Measured crudely, as the share of the output that is not the input shape.
        for (drive in everyDrive()) {
            drive.reset()
            val input = FloatArray(4096) { sin(it * 0.05).toFloat() * 0.3f }
            val output = input.copyOf()
            drive.process(output, output.size)
            // Settle past the filters, then compare shape: scale the input to the output's peak and
            // measure what is left over.
            val from = 2048
            val outPeak = (from until output.size).maxOf { abs(output[it]) }
            val inPeak = (from until input.size).maxOf { abs(input[it]) }
            assertTrue(outPeak > 0.01f, "${drive.name} produced nothing")
            var residual = 0.0
            for (i in from until output.size) {
                val scaled = input[i] / inPeak * outPeak
                residual += (output[i] - scaled) * (output[i] - scaled)
            }
            assertTrue(residual > 0.1, "${drive.name} left a sine as a sine; it is not driving")
        }
    }

    @Test
    fun `Smooth is the original overdrive, sample for sample`() {
        // The formula, written out independently: tanh(x·d)/tanh(d), one-pole low-pass, level.
        val drive = SmoothDrive()
        drive.drive.value = 8f
        drive.tone.value = 3000f
        drive.level.value = 0.7f
        drive.reset()
        val input = FloatArray(1024) { sin(it * 0.07).toFloat() * 0.5f }
        val output = input.copyOf()
        drive.process(output, output.size)

        val a = 1f - kotlin.math.exp(-2f * Math.PI.toFloat() * 3000f / 44_100f)
        var lp = 0f
        for (i in input.indices) {
            val clipped = kotlin.math.tanh(input[i] * 8f) / kotlin.math.tanh(8f)
            lp += a * (clipped - lp)
            assertEquals(lp * 0.7f, output[i], 1e-5f, "sample $i")
        }
    }

    @Test
    fun `the Rat's filter closes as it is turned up`() {
        fun brightness(filter: Float): Float {
            val rat = RatDrive()
            rat.distortion.value = 1f
            rat.volume.value = 1f
            rat.filter.value = filter
            rat.reset()
            // A high tone well above where the filter can reach: how much survives says how open it is.
            val buffer = FloatArray(4096) { sin(it * 2.0).toFloat() * 0.3f }
            rat.process(buffer, buffer.size)
            return (2048 until buffer.size).maxOf { abs(buffer[it]) }
        }
        assertTrue(brightness(0f) > brightness(0.5f), "half way should be darker than open")
        assertTrue(brightness(0.5f) > brightness(1f), "fully up should be darkest")
    }

    @Test
    fun `the fuzz sputters as a note dies away`() {
        // The whole trick of the voice: a loud signal clips symmetrically and a quiet one is leaned
        // over by the bias, so the positive and negative peaks stop matching as the level falls.
        val fuzz = FuzzDrive()
        fuzz.fuzz.value = 40f
        fuzz.volume.value = 1f
        fuzz.reset()
        fun asymmetry(amplitude: Float): Float {
            val buffer = FloatArray(8192) { sin(it * 0.05).toFloat() * amplitude }
            fuzz.process(buffer, buffer.size)
            val tail = buffer.copyOfRange(6144, 8192)
            return abs(tail.max() + tail.min())
        }
        val loud = asymmetry(0.5f)
        val quiet = asymmetry(0.02f)
        assertTrue(quiet > loud + 0.05f, "quiet should be more lopsided than loud: $quiet vs $loud")
    }
}

class OverdriveTest {

    @Test
    fun `output stays bounded under extreme drive`() {
        val overdrive = Overdrive()
        overdrive.drive.value = 50f
        overdrive.level.value = 1f
        overdrive.reset()

        val buffer = FloatArray(2048) { if (it % 2 == 0) 1f else -1f }
        overdrive.process(buffer, buffer.size)

        assertTrue(buffer.all { it.isFinite() && abs(it) <= 1.01f }, "soft clipper stayed within range")
    }

    @Test
    fun `silence in, silence out`() {
        val overdrive = Overdrive()
        overdrive.reset()
        val buffer = FloatArray(512)
        overdrive.process(buffer, buffer.size)
        buffer.forEach { assertEquals(0f, it, 1e-6f) }
    }
}

class ReverbTest {

    @Test
    fun `tail decays and stays finite`() {
        val reverb = Reverb()
        reverb.roomSize.value = 0.8f
        reverb.mix.value = 1f
        reverb.reset()

        val buffer = FloatArray(88_200)
        buffer[0] = 1f
        reverb.process(buffer, buffer.size)

        assertTrue(buffer.all { it.isFinite() }, "reverb produced finite output")

        val early = buffer.copyOfRange(0, 22_050).maxOf { abs(it) }
        val late = buffer.copyOfRange(66_150, 88_200).maxOf { abs(it) }
        assertTrue(late < early, "tail decayed: early=$early late=$late")
    }
}

/**
 * The effects used to bake 44.1 kHz into their coefficients and buffer sizes, so every control
 * silently meant something different on a device running at another rate. These pin the fix.
 */
class RateDependenceTest {

    @Test
    fun `delay lands its echo at the right time on a 48 kHz device`() {
        val delay = Delay()
        delay.prepare(48_000f, 256)
        delay.time.value = 100f
        delay.feedback.value = 0f
        delay.mix.value = 1f
        delay.reset()

        val expected = 4_800 // 100 ms at 48 kHz, not the 4410 a hardcoded rate would give
        val buffer = FloatArray(12_000)
        buffer[0] = 1f
        delay.process(buffer, buffer.size)

        assertEquals(1f, buffer[expected], 1e-3f, "echo at sample $expected")
        assertEquals(0f, buffer[expected - 50], 1e-4f, "silence before the echo")
    }

    @Test
    fun `the full delay range is reachable at 96 kHz`() {
        // The old line was sized for 44.1 kHz, so at 96 kHz the 1000 ms knob clamped near 505 ms —
        // a control lying about what it does.
        val delay = Delay()
        delay.prepare(96_000f, 256)
        delay.time.value = delay.time.max
        delay.feedback.value = 0f
        delay.mix.value = 1f
        delay.reset()

        val expected = 96_000 // 1000 ms at 96 kHz
        val buffer = FloatArray(expected + 2_000)
        buffer[0] = 1f
        delay.process(buffer, buffer.size)

        assertEquals(1f, buffer[expected], 1e-3f, "full-scale delay reached $expected samples")
    }

    @Test
    fun `the overdrive tone control means the same frequency at any rate`() {
        // One-pole step response: time to 63 % of final is the filter's time constant, which is a
        // property of the cutoff alone and must not move with the sample rate.
        val slow = riseTimeMs(44_100f)
        val fast = riseTimeMs(96_000f)
        assertEquals(slow, fast, 0.1f, "time constant at 44.1 kHz vs 96 kHz")
    }

    private fun riseTimeMs(sampleRate: Float): Float {
        val overdrive = Overdrive()
        overdrive.prepare(sampleRate, 256)
        overdrive.drive.value = 1f
        overdrive.tone.value = 200f
        overdrive.level.value = 1f
        overdrive.reset()

        val n = (sampleRate * 0.05f).toInt() // 50 ms, far longer than a 200 Hz pole needs
        val buffer = FloatArray(n) { 1f }
        overdrive.process(buffer, n)

        val target = buffer.last() * 0.632f
        val index = buffer.indexOfFirst { it >= target }
        assertTrue(index > 0, "step response rose at $sampleRate Hz")
        return index * 1000f / sampleRate
    }

    @Test
    fun `the master volume ramp takes the same time at any rate`() {
        assertEquals(muteTimeMs(44_100f), muteTimeMs(96_000f), 1f, "mute ramp at 44.1 vs 96 kHz")
    }

    private fun muteTimeMs(sampleRate: Float): Float {
        val master = MasterVolume()
        master.prepare(sampleRate, 256)
        master.volume.value = 100f
        master.reset()
        master.enabled = false

        val n = (sampleRate * 0.2f).toInt()
        val buffer = FloatArray(n) { 1f }
        master.process(buffer, n)

        val index = buffer.indexOfFirst { it < 0.368f } // one time constant down
        assertTrue(index > 0, "ramp fell at $sampleRate Hz")
        return index * 1000f / sampleRate
    }

    @Test
    fun `reverb rebuilds its filter bank for a new rate and still decays`() {
        val reverb = Reverb()
        reverb.prepare(96_000f, 256)
        reverb.roomSize.value = 0.8f
        reverb.mix.value = 1f
        reverb.reset()

        val buffer = FloatArray(192_000)
        buffer[0] = 1f
        reverb.process(buffer, buffer.size)

        assertTrue(buffer.all { it.isFinite() }, "reverb produced finite output at 96 kHz")
        val early = buffer.copyOfRange(0, 48_000).maxOf { abs(it) }
        val late = buffer.copyOfRange(144_000, 192_000).maxOf { abs(it) }
        assertTrue(late < early, "tail decayed: early=$early late=$late")
    }
}

class CompressorTest {

    private fun compressor() = Compressor().apply {
        prepare(DEFAULT_SAMPLE_RATE, 256)
        threshold.value = -20f
        ratio.value = 4f
        attack.value = 1f
        release.value = 50f
        makeup.value = 0f
        reset()
    }

    /** Steady tone at [amplitude], measured after the envelope has settled. */
    private fun settledPeak(comp: Compressor, amplitude: Float): Float {
        val buffer = FloatArray(20_000) { amplitude * sin(it * 0.06f) }
        comp.process(buffer, buffer.size)
        return buffer.copyOfRange(15_000, 20_000).maxOf { abs(it) }
    }

    @Test
    fun `a signal below the threshold is left alone`() {
        // -20 dB is 0.1; 0.02 is well under it.
        assertEquals(0.02f, settledPeak(compressor(), 0.02f), 0.002f)
    }

    @Test
    fun `a signal above the threshold is pulled back towards it`() {
        val quiet = settledPeak(compressor(), 0.1f)
        val loud = settledPeak(compressor(), 0.8f)

        assertTrue(loud > quiet, "louder in still means louder out: $quiet -> $loud")
        assertTrue(loud < 0.8f, "it was actually compressed, got $loud")
        // 8x the input over the threshold should come out far less than 8x louder.
        assertTrue(loud / quiet < 4f, "the ratio bit: $quiet -> $loud")
    }

    @Test
    fun `a ratio of one is a wire`() {
        val comp = compressor()
        comp.ratio.value = 1f
        assertEquals(0.8f, settledPeak(comp, 0.8f), 0.01f)
    }

    @Test
    fun `makeup gain lifts the output`() {
        val plain = settledPeak(compressor(), 0.5f)
        val lifted = settledPeak(compressor().apply { makeup.value = 6f }, 0.5f)
        assertEquals(2f, lifted / plain, 0.05f)
    }

    @Test
    fun `output stays finite and bounded under a hammering`() {
        val comp = compressor()
        comp.threshold.value = -48f
        comp.ratio.value = 20f
        val buffer = FloatArray(4096) { if (it % 2 == 0) 1f else -1f }
        comp.process(buffer, buffer.size)
        assertTrue(buffer.all { it.isFinite() && abs(it) <= 1.01f }, "stayed in range")
    }
}

class GateTest {

    private fun gate() = Gate().apply {
        prepare(DEFAULT_SAMPLE_RATE, 256)
        threshold.value = -40f // 0.01
        attack.value = 1f
        hold.value = 20f
        release.value = 20f
        reset()
    }

    @Test
    fun `a signal above the threshold gets through`() {
        val gate = gate()
        val buffer = FloatArray(10_000) { 0.5f * sin(it * 0.05f) }
        gate.process(buffer, buffer.size)
        assertTrue(
            buffer.copyOfRange(8_000, 10_000).maxOf { abs(it) } > 0.4f,
            "the gate opened",
        )
    }

    @Test
    fun `noise below the threshold is silenced`() {
        val gate = gate()
        val buffer = FloatArray(20_000) { 0.001f * sin(it * 0.05f) }
        gate.process(buffer, buffer.size)
        assertTrue(
            buffer.copyOfRange(15_000, 20_000).maxOf { abs(it) } < 1e-4f,
            "quiet input was gated out",
        )
    }

    @Test
    fun `the hold keeps it open across a dip`() {
        // A note, a brief gap shorter than the hold, then the note again. The gate should not slam
        // shut in the gap — that chatter is exactly what hold exists to stop.
        val gate = gate()
        val gapStart = 6_000
        val gapEnd = gapStart + 400 // ~9 ms, well inside the 20 ms hold
        val buffer = FloatArray(12_000) {
            if (it in gapStart until gapEnd) 0f else 0.5f * sin(it * 0.05f)
        }
        gate.process(buffer, buffer.size)

        // Immediately after the gap the signal must still be passing at close to full level.
        val resumed = buffer.copyOfRange(gapEnd + 100, gapEnd + 600).maxOf { abs(it) }
        assertTrue(resumed > 0.3f, "still open after the dip, got $resumed")
    }

    @Test
    fun `it starts closed`() {
        // Opening on the first block would pass precisely the noise the gate exists to remove.
        val gate = gate()
        val buffer = FloatArray(64) { 0.001f }
        gate.process(buffer, buffer.size)
        assertTrue(buffer.all { abs(it) < 1e-4f }, "silent from the first sample")
    }
}

class IrTest {

    @Test
    fun `with no impulse loaded it passes audio through untouched`() {
        val ir = Ir()
        ir.prepare(DEFAULT_SAMPLE_RATE, 256)
        val buffer = FloatArray(512) { sin(it * 0.05f) }
        val original = buffer.copyOf()
        ir.process(buffer, buffer.size)
        assertTrue(buffer.contentEquals(original), "untouched without a cabinet")
    }

    @Test
    fun `a unit impulse reproduces the input`() {
        val ir = Ir()
        ir.prepare(DEFAULT_SAMPLE_RATE, 256)
        // A single 1.0 tap is the identity, and normalising to unit energy leaves it at 1.0.
        ir.loadImpulse(floatArrayOf(1f), DEFAULT_SAMPLE_RATE, "unit")
        ir.reset()

        val buffer = FloatArray(256) { sin(it * 0.05f) }
        val original = buffer.copyOf()
        ir.process(buffer, buffer.size)

        for (i in buffer.indices) assertEquals(original[i], buffer[i], 1e-5f, "sample $i")
    }

    @Test
    fun `a delayed impulse delays the signal by that many samples`() {
        val ir = Ir()
        ir.prepare(DEFAULT_SAMPLE_RATE, 256)
        val taps = FloatArray(64)
        taps[40] = 1f
        ir.loadImpulse(taps, DEFAULT_SAMPLE_RATE, "delayed")
        ir.reset()

        val buffer = FloatArray(256)
        buffer[0] = 1f
        ir.process(buffer, buffer.size)

        assertEquals(1f, buffer[40], 1e-4f, "the impulse came out 40 samples late")
        assertEquals(0f, buffer[39], 1e-5f)
    }

    @Test
    fun `an over-long impulse is truncated rather than refused`() {
        val ir = Ir()
        ir.prepare(DEFAULT_SAMPLE_RATE, 256)
        ir.loadImpulse(FloatArray(Ir.MAX_TAPS * 3) { 0.001f }, DEFAULT_SAMPLE_RATE, "long")
        ir.reset()

        val buffer = FloatArray(1024) { 0.2f }
        ir.process(buffer, buffer.size)
        assertTrue(buffer.all { it.isFinite() }, "still produced finite output")
    }

    @Test
    fun `impulses are normalised so swapping cabinets is not a volume change`() {
        fun tail(scale: Float): Float {
            val ir = Ir()
            ir.prepare(DEFAULT_SAMPLE_RATE, 256)
            ir.loadImpulse(FloatArray(128) { scale * sin(it * 0.3f) }, DEFAULT_SAMPLE_RATE, "cab")
            ir.reset()
            val buffer = FloatArray(1024) { sin(it * 0.05f) }
            ir.process(buffer, buffer.size)
            return buffer.copyOfRange(512, 1024).maxOf { abs(it) }
        }
        // The same shape a hundred times louder must come out at the same level.
        assertEquals(tail(0.01f), tail(1f), 1e-3f)
    }
}

class CabinetTest {

    private fun cabinets(): List<CabinetVoice> = listOf(
        CabinetVoice(CabinetVoice.OPEN_1X12),
        CabinetVoice(CabinetVoice.OPEN_2X12),
        CabinetVoice(CabinetVoice.CLOSED_4X12),
        CabinetVoice(CabinetVoice.SMALL_1X10),
    )

    /** Steady-state level of a tone through a cabinet, measured after the filters have settled. */
    private fun levelAt(cabinet: CabinetVoice, hz: Float, rate: Float = DEFAULT_SAMPLE_RATE): Float {
        cabinet.prepare(rate, 256)
        cabinet.reset()
        val n = (rate * 0.2f).toInt()
        val buffer = FloatArray(n) { sin(2.0 * Math.PI * hz * it / rate).toFloat() * 0.3f }
        cabinet.process(buffer, n)
        return (n / 2 until n).maxOf { abs(buffer[it]) }
    }

    @Test
    fun `the IR pedal offers the loaded file first and four cabinets`() {
        assertEquals(listOf("Loaded IR", "1x12 Open", "2x12 Open", "4x12 Closed", "1x10 Small"), Ir.typeNames)
        val ir = Ir()
        assertTrue(ir.usesFile, "a fresh IR pedal is on the file, as it always was")
        assertEquals(listOf("Type", "Mix", "Level"), ir.parameters.map { it.name })
        ir.selectType("4x12 Closed")
        assertTrue(!ir.usesFile)
        assertEquals(listOf("Type", "Mic", "Level"), ir.parameters.map { it.name })
    }

    @Test
    fun `a file loaded while on a cabinet is still there on switching back`() {
        val ir = Ir()
        ir.selectType("1x12 Open")
        ir.loadImpulse(floatArrayOf(1f), DEFAULT_SAMPLE_RATE, "unit")
        assertEquals("unit", ir.impulseName)
        ir.selectType("Loaded IR")
        assertEquals("unit", ir.impulseName)
    }

    @Test
    fun `the mic knob reads as words at its ends and a percentage between`() {
        val mic = CabinetVoice(CabinetVoice.OPEN_1X12).mic
        mic.value = 0f
        assertEquals("OFF-AXIS", mic.formatted())
        mic.value = 1f
        assertEquals("ON-AXIS", mic.formatted())
        mic.value = 0.5f
        assertEquals("50 %", mic.formatted())
    }

    @Test
    fun `every cabinet is silent on silence and finite at full scale`() {
        for (cabinet in cabinets()) {
            cabinet.reset()
            val quiet = FloatArray(1024)
            cabinet.process(quiet, quiet.size)
            assertTrue(quiet.all { it == 0f }, "${cabinet.name} made something of nothing")
            cabinet.level.value = 24f
            cabinet.reset()
            val loud = FloatArray(4096) { if ((it / 40) % 2 == 0) 1f else -1f }
            cabinet.process(loud, loud.size)
            assertTrue(loud.all { it.isFinite() }, "${cabinet.name} blew up")
        }
    }

    @Test
    fun `every cabinet rolls the top off hard, which is the thing a cabinet does`() {
        for (cabinet in cabinets()) {
            val mids = levelAt(cabinet, 1000f)
            val top = levelAt(cabinet, 10_000f)
            // At least 20 dB down at 10 kHz against 1 kHz; a real one is further.
            assertTrue(top < mids * 0.1f, "${cabinet.name}: 10 kHz at $top against 1 kHz at $mids")
        }
    }

    @Test
    fun `the closed 4x12 is darker than the open 1x12`() {
        val combo = CabinetVoice(CabinetVoice.OPEN_1X12)
        val stack = CabinetVoice(CabinetVoice.CLOSED_4X12)
        assertTrue(levelAt(stack, 5000f) < levelAt(combo, 5000f), "a stack should have less at 5 kHz")
        assertTrue(levelAt(stack, 120f) > levelAt(combo, 120f), "and more thump")
    }

    @Test
    fun `on-axis is brighter than off-axis`() {
        val cabinet = CabinetVoice(CabinetVoice.OPEN_2X12)
        cabinet.mic.value = 0f
        val offAxis = levelAt(cabinet, 3000f)
        cabinet.mic.value = 1f
        val onAxis = levelAt(cabinet, 3000f)
        assertTrue(onAxis > offAxis * 1.2f, "on-axis $onAxis should be well above off-axis $offAxis")
    }

    @Test
    fun `a cabinet's curve means the same frequencies at any rate`() {
        // The guard against the hardcoded-44100 bug, as every filter here has: the roll-off at
        // 96 kHz must sit where it sits at 44.1, so the same tone comes out at the same level.
        val cabinet = CabinetVoice(CabinetVoice.CLOSED_4X12)
        val at44 = levelAt(cabinet, 4000f, 44_100f)
        val at96 = levelAt(cabinet, 4000f, 96_000f)
        assertEquals(at44, at96, at44 * 0.1f, "4 kHz through a 4x12 at 44.1 vs 96 kHz")
    }
}

class AmpTest {

    private fun voices(): List<AmpVoice> = listOf(
        AmpVoice(AmpVoice.AMERICAN_CLEAN),
        AmpVoice(AmpVoice.TWEED),
        AmpVoice(AmpVoice.BRITISH_CRUNCH),
        AmpVoice(AmpVoice.BRITISH_CHIME),
        AmpVoice(AmpVoice.MODERN_HIGH_GAIN),
    )

    private fun tone(hz: Float, amplitude: Float, seconds: Float, rate: Float = DEFAULT_SAMPLE_RATE) =
        FloatArray((rate * seconds).toInt()) { sin(2.0 * Math.PI * hz * it / rate).toFloat() * amplitude }

    @Test
    fun `the amp offers five families, four knobs on the pedal and two behind the button`() {
        assertEquals(
            listOf("American Clean", "Tweed", "British Crunch", "British Chime", "Modern High Gain"),
            Amp.typeNames,
        )
        val amp = Amp()
        assertEquals(listOf("Type", "Gain", "Bass", "Mid", "Treble", "Presence", "Master"), amp.parameters.map { it.name })
        assertEquals(5, amp.compactParameters, "Type plus four on the pedal; Presence and Master expand")
        amp.selectType("British Chime")
        assertEquals("Cut", amp.parameters[5].name, "the chime family has a cut control, not presence")
        assertEquals(5, amp.alternates.size)
    }

    @Test
    fun `every voice stays inside full scale at its loudest, at both rates`() {
        for (rate in listOf(44_100f, 96_000f)) {
            for (voice in voices()) {
                voice.prepare(rate, 256)
                for (parameter in voice.parameters) parameter.value = parameter.max
                voice.reset()
                val square = FloatArray(8192) { if ((it / 50) % 2 == 0) 1f else -1f }
                voice.process(square, square.size)
                val sine = tone(220f, 1f, 0.2f, rate)
                voice.process(sine, sine.size)
                for (buffer in listOf(square, sine)) {
                    assertTrue(buffer.all { it.isFinite() }, "${voice.name} produced a non-finite sample at $rate")
                    val peak = buffer.maxOf { abs(it) }
                    assertTrue(peak <= 1.01f, "${voice.name} reached $peak at $rate Hz")
                }
            }
        }
    }

    @Test
    fun `every voice is silent on silence, despite the biased stages`() {
        for (voice in voices()) {
            for (parameter in voice.parameters) parameter.value = parameter.max
            voice.reset()
            val buffer = FloatArray(4096)
            voice.process(buffer, buffer.size)
            for ((i, v) in buffer.withIndex()) assertEquals(0f, v, 1e-6f, "${voice.name} sample $i")
        }
    }

    /**
     * Total harmonic distortion of a sine through the voice: the share of the output's power that
     * is not at the fundamental. Measured with a tone whose period divides the window exactly —
     * 220.5 Hz is 200 samples at 44.1 kHz, and 4000 samples is twenty cycles — so the fundamental's
     * bin is leakage-free and the filters' phase shifts, which a waveform comparison would count as
     * distortion, do not register.
     */
    private fun distortion(voice: AmpVoice, gainKnob: Float): Double {
        voice.gain.value = gainKnob
        voice.reset()
        val rate = DEFAULT_SAMPLE_RATE
        val hz = 220.5f
        val output = tone(hz, 0.3f, 0.5f)
        voice.process(output, output.size)
        val n = 4000
        val from = output.size - n
        var re = 0.0
        var im = 0.0
        var power = 0.0
        for (i in 0 until n) {
            val x = output[from + i].toDouble()
            val angle = 2.0 * Math.PI * hz * i / rate
            re += x * kotlin.math.cos(angle)
            im += x * kotlin.math.sin(angle)
            power += x * x
        }
        val amplitude = 2.0 * kotlin.math.sqrt(re * re + im * im) / n
        val fundamental = amplitude * amplitude / 2.0
        val total = power / n
        return kotlin.math.sqrt((1.0 - fundamental / total).coerceAtLeast(0.0))
    }

    @Test
    fun `more gain is more distortion, on every voice`() {
        for (voice in voices()) {
            val clean = distortion(voice, 1f)
            val driven = distortion(voice, 9f)
            assertTrue(driven > clean, "${voice.name}: gain 9 gave $driven against gain 1 at $clean")
        }
    }

    @Test
    fun `the clean voice stays cleaner than the high-gain one at the same knob`() {
        val clean = distortion(AmpVoice(AmpVoice.AMERICAN_CLEAN), 5f)
        val modern = distortion(AmpVoice(AmpVoice.MODERN_HIGH_GAIN), 5f)
        assertTrue(modern > clean * 2, "high gain $modern should be well past clean $clean at noon")
    }

    @Test
    fun `the tone stack moves the frequencies it says it moves`() {
        fun level(voice: AmpVoice, hz: Float): Float {
            voice.reset()
            val buffer = tone(hz, 0.05f, 0.2f)
            voice.process(buffer, buffer.size)
            return (buffer.size / 2 until buffer.size).maxOf { abs(buffer[it]) }
        }
        val voice = AmpVoice(AmpVoice.AMERICAN_CLEAN)
        voice.gain.value = 1f
        voice.bass.value = 10f
        val bassUp = level(voice, 80f)
        voice.bass.value = 0f
        val bassDown = level(voice, 80f)
        assertTrue(bassUp > bassDown * 2f, "bass 10 vs 0 at 80 Hz: $bassUp vs $bassDown")

        voice.bass.value = 5f
        voice.treble.value = 10f
        val trebleUp = level(voice, 4000f)
        voice.treble.value = 0f
        val trebleDown = level(voice, 4000f)
        assertTrue(trebleUp > trebleDown * 2f, "treble 10 vs 0 at 4 kHz: $trebleUp vs $trebleDown")
    }

    @Test
    fun `a pushed tweed sags - the level drops after the attack and holds lower`() {
        val voice = AmpVoice(AmpVoice.TWEED)
        voice.gain.value = 8f
        voice.master.value = 10f
        voice.reset()
        val buffer = tone(220f, 0.8f, 0.5f)
        voice.process(buffer, buffer.size)
        val rate = DEFAULT_SAMPLE_RATE.toInt()
        // The first few milliseconds, before the supply has dropped, against the sustained level.
        val attack = (0 until rate / 100).maxOf { abs(buffer[it]) }
        val sustain = (rate * 4 / 10 until buffer.size).maxOf { abs(buffer[it]) }
        assertTrue(sustain < attack * 0.97f, "no sag: attack $attack, sustain $sustain")
    }

    @Test
    fun `the chime family's cut darkens as it is turned up`() {
        fun top(cut: Float): Float {
            val voice = AmpVoice(AmpVoice.BRITISH_CHIME)
            voice.gain.value = 1f
            voice.presence.value = cut
            voice.reset()
            val buffer = tone(6000f, 0.05f, 0.2f)
            voice.process(buffer, buffer.size)
            return (buffer.size / 2 until buffer.size).maxOf { abs(buffer[it]) }
        }
        assertTrue(top(0f) > top(10f) * 2f, "cut 0 at ${top(0f)} should be far brighter than cut 10 at ${top(10f)}")
    }
}

class EffectChainTest {

    @Test
    fun `disabled effects are bypassed`() {
        val overdrive = Overdrive().apply { enabled = false }
        val delay = Delay().apply { enabled = false }
        val reverb = Reverb().apply { enabled = false }
        val gain = Gain().apply { enabled = false }

        val chain = EffectChain(listOf(gain, overdrive, delay, reverb))
        chain.reset()

        val buffer = FloatArray(512) { 0.4f }
        chain.process(buffer, buffer.size)

        buffer.forEach { assertEquals(0.4f, it, 1e-6f) }
    }

    @Test
    fun `a disabled master volume mutes the chain rather than being skipped`() {
        // Every other effect bypasses when switched off. The master must not: skipping it would
        // leave the signal at full level, which is the opposite of what its switch means.
        val master = MasterVolume().apply { volume.value = 100f }
        val chain = EffectChain(
            listOf(
                master,
                Gain(),
                // Time-based effects held off deliberately: the master sits at the front of the
                // chain, so their tails outlive the mute ramp by seconds and would turn this
                // assertion about the chain's dispatch rule into a test of reverb decay.
                Overdrive().apply { enabled = false },
                Delay().apply { enabled = false },
                Reverb().apply { enabled = false },
            )
        )
        chain.reset()
        master.enabled = false

        val buffer = FloatArray(8192) { 0.5f }
        chain.process(buffer, buffer.size)

        assertTrue(abs(buffer.last()) < 1e-3f, "chain fell silent, got ${buffer.last()}")
    }

    /** Boosting into a clipper is not the same as clipping then boosting — order is the signal path. */
    private fun saturatedTail(gainFirst: Boolean): Float {
        val gain = Gain().apply { gainDb.value = 12f }
        val overdrive = Overdrive().apply {
            enabled = true
            drive.value = 20f
            level.value = 1f
        }
        val chain = EffectChain(if (gainFirst) listOf(gain, overdrive) else listOf(overdrive, gain))
        chain.prepare(DEFAULT_SAMPLE_RATE, 256)
        chain.reset()

        val buffer = FloatArray(2048) { 0.2f }
        chain.process(buffer, buffer.size)
        return buffer.last()
    }

    @Test
    fun `the order of the chain changes the sound`() {
        val gainFirst = saturatedTail(gainFirst = true)
        val driveFirst = saturatedTail(gainFirst = false)
        assertTrue(
            abs(gainFirst - driveFirst) > 0.01f,
            "order mattered: gain-first=$gainFirst drive-first=$driveFirst",
        )
    }

    @Test
    fun `reorder rewires the chain`() {
        val gain = Gain().apply { gainDb.value = 12f }
        val overdrive = Overdrive().apply {
            enabled = true
            drive.value = 20f
            level.value = 1f
        }
        val chain = EffectChain(listOf(gain, overdrive))
        chain.reorder(listOf(overdrive, gain))

        assertEquals(listOf("Overdrive", "Gain"), chain.effects.map { it.name })
    }

    @Test
    fun `reorder refuses anything that is not a permutation`() {
        val gain = Gain()
        val overdrive = Overdrive()
        val chain = EffectChain(listOf(gain, overdrive))

        // Dropping an effect, and substituting a different one, are both bugs rather than re-orders.
        assertFailsWith<IllegalArgumentException> { chain.reorder(listOf(gain)) }
        assertFailsWith<IllegalArgumentException> { chain.reorder(listOf(gain, Reverb())) }
        assertFailsWith<IllegalArgumentException> { chain.reorder(listOf(gain, gain)) }
    }

    @Test
    fun `enabled chain runs in signal order without blowing up`() {
        val gain = Gain().apply { gainDb.value = 3f }
        val overdrive = Overdrive().apply { enabled = true }
        val delay = Delay().apply { enabled = true }
        val reverb = Reverb().apply { enabled = true }

        val chain = EffectChain(listOf(gain, overdrive, delay, reverb))
        chain.reset()

        val buffer = FloatArray(4096) { kotlin.math.sin(it * 0.05f) * 0.5f }
        chain.process(buffer, buffer.size)

        assertTrue(buffer.all { it.isFinite() }, "full chain produced finite output")
    }
}
