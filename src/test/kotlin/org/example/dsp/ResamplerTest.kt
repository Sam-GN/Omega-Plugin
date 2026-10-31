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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The converter, checked against arithmetic worked out here rather than against what it returns.
 *
 * A resampler fails quietly. Get the phase accounting wrong and it still produces plausible audio,
 * just at the wrong rate or with images folded back in — which sounds like a slightly worse
 * recording, not like a bug. So every check below is against a number derived independently: the
 * exact 147:160 ratio, unity gain at DC, and a sine that has to come back out as the same sine.
 */
class ResamplerTest {

    private fun sine(index: Int, hertz: Double, rate: Double, amplitude: Float = 0.5f): Float =
        (amplitude * sin(2.0 * PI * hertz * index / rate)).toFloat()

    @Test
    fun `produces output at exactly the ratio of the two rates`() {
        val resampler = Resampler(44_100f, 48_000f, 64)
        val output = FloatArray(resampler.maxOutputFrames)
        val input = FloatArray(64)

        var produced = 0L
        repeat(1_000) { produced += resampler.process(input, 64, output) }

        // 44100:48000 reduces to 147:160, so 64 000 frames in must give 64 000 * 160/147 out.
        val expected = 64_000L * 160 / 147
        assertTrue(
            abs(produced - expected) <= 2,
            "produced $produced where the ratio implies $expected",
        )
    }

    @Test
    fun `and the same in the other direction`() {
        val resampler = Resampler(48_000f, 44_100f, 64)
        val output = FloatArray(resampler.maxOutputFrames)
        val input = FloatArray(64)

        var produced = 0L
        repeat(1_000) { produced += resampler.process(input, 64, output) }

        val expected = 64_000L * 147 / 160
        assertTrue(
            abs(produced - expected) <= 2,
            "produced $produced where the ratio implies $expected",
        )
    }

    @Test
    fun `never writes past the size it says it needs`() {
        val resampler = Resampler(44_100f, 48_000f, 64)
        val output = FloatArray(resampler.maxOutputFrames)
        val input = FloatArray(64) { 0.5f }
        repeat(200) {
            val produced = resampler.process(input, 64, output)
            assertTrue(
                produced <= resampler.maxOutputFrames,
                "produced $produced into a buffer of ${resampler.maxOutputFrames}",
            )
        }
    }

    /**
     * Unity gain at DC, which is not free: the prototype's tails are truncated, and left
     * unnormalised the error reads as a level change the moment the converter is switched in.
     */
    @Test
    fun `passes a constant through unchanged`() {
        val resampler = Resampler(44_100f, 48_000f, 128)
        val output = FloatArray(resampler.maxOutputFrames)
        val input = FloatArray(128) { 1f }

        repeat(40) { resampler.process(input, 128, output) } // fill the filter
        val produced = resampler.process(input, 128, output)

        for (i in 0 until produced) {
            assertEquals(1f, output[i], 1e-3f, "frame $i of a constant")
        }
    }

    /**
     * The declared latency is the real one.
     *
     * Measured with an impulse rather than a tone. A tone cannot answer this: a 1 kHz sine at
     * 44.1 kHz repeats every 44.1 frames, so cross-correlation cannot tell a delay of 21 frames
     * from one of 65 — it reports whichever it finds first, and both are equally good fits. An
     * impulse has one peak and no such ambiguity.
     */
    @Test
    fun `declares the latency it actually adds`() {
        val adapter = RateAdapter(44_100f, 48_000f, 64)
        val blocks = 20
        val result = FloatArray(blocks * 64)
        val block = FloatArray(64)

        for (b in 0 until blocks) {
            java.util.Arrays.fill(block, 0f)
            if (b == 0) block[0] = 1f
            val inner = adapter.toInner(block, 64)
            adapter.fromInner(inner, block, 64)
            System.arraycopy(block, 0, result, b * 64, 64)
        }

        var peak = 0
        for (i in result.indices) if (abs(result[i]) > abs(result[peak])) peak = i

        assertTrue(
            abs(peak - adapter.latencyFrames) <= 2,
            "the impulse came out at $peak frames against a declared ${adapter.latencyFrames}",
        )
    }

    @Test
    fun `a tone comes back as the same tone after a round trip`() {
        val adapter = RateAdapter(44_100f, 48_000f, 64)
        val blocks = 400
        val length = blocks * 64
        // Low enough that the fractional part of the group delay cannot masquerade as distortion:
        // aligning on whole frames leaves a sub-frame phase error, and that error grows with
        // frequency.
        val source = FloatArray(length) { sine(it, 300.0, 44_100.0) }
        val result = FloatArray(length)

        val block = FloatArray(64)
        for (b in 0 until blocks) {
            System.arraycopy(source, b * 64, block, 0, 64)
            // The inner stage is the identity here: this is measuring the converter, not the amp.
            val inner = adapter.toInner(block, 64)
            adapter.fromInner(inner, block, 64)
            System.arraycopy(block, 0, result, b * 64, 64)
        }

        assertEquals(0, adapter.starved, "the reservoir ran dry")
        assertEquals(0, adapter.overflowed, "the reservoir overran")

        // Measured well clear of both the start-up transient and the end.
        val from = 4_000
        val until = length - 4_000
        val lag = adapter.latencyFrames

        var error = 0.0
        var signal = 0.0
        var output = 0.0
        for (i in from until until) {
            val want = source[i - lag].toDouble()
            val got = result[i].toDouble()
            error += (got - want) * (got - want)
            signal += want * want
            output += got * got
        }

        // Level first: a converter that quietly changes gain would pass a shape test and still be
        // wrong the moment it is switched in mid-playing.
        assertEquals(1.0, sqrt(output / signal), 0.01, "round-trip gain")
        // Then shape. A phase-accounting mistake does not land near this; it lands at the same
        // order as the signal itself.
        assertTrue(
            sqrt(error / signal) < 0.05,
            "round-trip error ${sqrt(error / signal)} of the signal",
        )
    }

    @Test
    fun `delivers exactly the block size it was asked for, every block`() {
        val adapter = RateAdapter(44_100f, 48_000f, 64)
        val block = FloatArray(64)
        // A varying signal, so a block quietly filled with the wrong thing would still show up.
        var at = 0
        repeat(2_000) {
            for (i in block.indices) block[i] = sine(at + i, 440.0, 44_100.0)
            at += 64
            val inner = adapter.toInner(block, 64)
            assertTrue(inner in 60..80, "inner block of $inner frames")
            adapter.fromInner(inner, block, 64)
        }
        assertEquals(0, adapter.starved, "the reservoir ran dry")
        assertEquals(0, adapter.overflowed, "the reservoir overran")
    }

    @Test
    fun `reset returns it to silence so a second pass repeats the first`() {
        val adapter = RateAdapter(44_100f, 48_000f, 64)
        val first = FloatArray(64) { sine(it, 700.0, 44_100.0) }
        val second = first.copyOf()

        adapter.fromInner(adapter.toInner(first, 64), first, 64)
        adapter.reset()
        adapter.fromInner(adapter.toInner(second, 64), second, 64)

        for (i in first.indices) assertEquals(first[i], second[i], 1e-6f, "frame $i")
    }

    @Test
    fun `uneven chunks produce the same samples as one call, so the phase carries across calls`() {
        // The capture side of a two-clock backend feeds the converter whatever a packet holds, and
        // a shared-mode packet is not a fixed size. The output must not depend on how the input was
        // cut: the polyphase phase and the history have to carry from one call to the next.
        val total = 4_410
        val signal = FloatArray(total) { sine(it, 1_000.0, 44_100.0) }

        val whole = Resampler(44_100f, 48_000f, total)
        val wholeOut = FloatArray(whole.maxOutputFrames)
        val wholeCount = whole.process(signal, total, wholeOut)

        val chunked = Resampler(44_100f, 48_000f, total)
        val chunkedOut = FloatArray(chunked.maxOutputFrames)
        var produced = 0
        var at = 0
        var step = 1
        while (at < total) {
            val take = minOf(step, total - at)
            val piece = signal.copyOfRange(at, at + take)
            val scratch = FloatArray(chunked.maxOutputFrames)
            val n = chunked.process(piece, take, scratch)
            System.arraycopy(scratch, 0, chunkedOut, produced, n)
            produced += n
            at += take
            step = step % 97 + 1
        }

        assertEquals(wholeCount, produced, "frame counts differ")
        for (i in 0 until wholeCount) assertEquals(wholeOut[i], chunkedOut[i], 1e-6f, "frame $i")
    }

}
