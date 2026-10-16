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
package org.example.audio

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The latency measurement has to be right before any number it produces means anything, and it is
 * the one piece of the low-latency work that can be verified with no hardware at all: put a delay
 * line of a known length between the probe's output and its input and see whether it reports that
 * length.
 *
 * That is the same discipline as `DelayTest` — assert on an offset computed independently, not on
 * whatever the code currently produces.
 */
class LoopbackProbeTest {

    private val rate = 44_100f
    private val block = 128

    /**
     * Runs [probe] for [seconds] with a synthetic loopback of exactly [delayFrames], optionally
     * with noise or interference mixed into the "input".
     */
    private fun run(
        probe: LoopbackProbe,
        delayFrames: Int,
        seconds: Float = 4f,
        noiseAmplitude: Float = 0f,
        interferenceHz: Double = 0.0,
        interferenceAmplitude: Float = 0f,
    ) {
        probe.prepare(rate, block)
        // The world outside the app: whatever the probe writes comes back delayFrames later.
        val line = FloatArray(delayFrames + block + 1)
        var writeIndex = 0
        val buffer = FloatArray(block)
        val random = Random(20260809)
        var phase = 0.0
        val increment = if (interferenceHz > 0) 2.0 * Math.PI * interferenceHz / rate else 0.0

        val blocks = (rate * seconds / block).toInt()
        repeat(blocks) {
            for (i in 0 until block) {
                // Read what the probe emitted delayFrames ago, and add whatever else is on the wire.
                val readIndex = (writeIndex - delayFrames + line.size) % line.size
                var v = line[readIndex]
                if (noiseAmplitude > 0f) v += (random.nextFloat() * 2f - 1f) * noiseAmplitude
                if (interferenceAmplitude > 0f) {
                    v += (interferenceAmplitude * sin(phase)).toFloat()
                    phase += increment
                }
                buffer[i] = v
                writeIndex = (writeIndex + 1) % line.size
            }
            // Rewind so the probe's output lands in the same slots we just consumed.
            var w = (writeIndex - block + line.size) % line.size
            probe.process(buffer, block)
            for (i in 0 until block) {
                line[w] = buffer[i]
                w = (w + 1) % line.size
            }
        }
    }

    @Test
    fun `measures a known loopback delay to within a millisecond`() {
        // 441 frames is exactly 10 ms at 44.1 kHz — the target this whole exercise is aimed at.
        val probe = LoopbackProbe()
        run(probe, delayFrames = 441)

        val measured = assertNotNull(probe.medianFrames(), "the probe found no loopback at all")
        assertTrue(
            abs(measured - 441) <= 44,
            "expected about 441 frames (10 ms), measured $measured (${measured * 1000f / rate} ms)",
        )
    }

    @Test
    fun `tracks the delay rather than reporting a constant`() {
        // A probe that returned the same answer regardless would pass the test above. Sweep it.
        for (delay in intArrayOf(220, 441, 882, 1764)) {
            val probe = LoopbackProbe()
            run(probe, delayFrames = delay)
            val measured = assertNotNull(probe.medianFrames(), "no loopback found at $delay frames")
            assertTrue(
                abs(measured - delay) <= 44,
                "expected about $delay frames, measured $measured",
            )
        }
    }

    @Test
    fun `finds the burst under broadband noise`() {
        // Measured on the real interface, an idle guitar input sat around -12 dBFS. A detector that
        // only works into silence is not a detector.
        val probe = LoopbackProbe()
        run(probe, delayFrames = 441, seconds = 6f, noiseAmplitude = 0.25f)

        val measured = assertNotNull(probe.medianFrames(), "the burst was lost in the noise")
        assertTrue(abs(measured - 441) <= 66, "expected about 441 frames, measured $measured")
    }

    @Test
    fun `is not fooled by a loud tone at another frequency`() {
        // Mains hum and its harmonics are the realistic interferer, and the reason the detector is
        // a Goertzel at the burst frequency rather than a threshold on level.
        val probe = LoopbackProbe()
        run(probe, delayFrames = 441, seconds = 6f, interferenceHz = 100.0, interferenceAmplitude = 0.5f)

        val measured = assertNotNull(probe.medianFrames(), "the burst was masked by the interference")
        assertTrue(abs(measured - 441) <= 66, "expected about 441 frames, measured $measured")
    }

    @Test
    fun `reports nothing when there is no loopback`() {
        // The failure that matters: with no lead patched, the honest answer is "no result", not a
        // number invented from noise.
        val probe = LoopbackProbe()
        probe.prepare(rate, block)
        val buffer = FloatArray(block)
        val random = Random(7)
        repeat((rate * 5 / block).toInt()) {
            for (i in 0 until block) buffer[i] = (random.nextFloat() * 2f - 1f) * 0.2f
            probe.process(buffer, block)
        }
        assertNull(probe.medianFrames(), "invented a round trip from noise: ${probe.results().toList()}")
    }

    @Test
    fun `rescales with the sample rate`() {
        // 10 ms at 96 kHz is 960 frames, not 441. Anything pinned to 44.1 kHz fails here.
        val probe = LoopbackProbe()
        val fastRate = 96_000f
        probe.prepare(fastRate, block)
        val delay = 960
        val line = FloatArray(delay + block + 1)
        var writeIndex = 0
        val buffer = FloatArray(block)
        repeat((fastRate * 4 / block).toInt()) {
            for (i in 0 until block) {
                buffer[i] = line[(writeIndex - delay + line.size) % line.size]
                writeIndex = (writeIndex + 1) % line.size
            }
            var w = (writeIndex - block + line.size) % line.size
            probe.process(buffer, block)
            for (i in 0 until block) {
                line[w] = buffer[i]
                w = (w + 1) % line.size
            }
        }
        val measured = assertNotNull(probe.medianFrames(), "no loopback found at 96 kHz")
        assertTrue(abs(measured - delay) <= 96, "expected about $delay frames at 96 kHz, measured $measured")
        assertEquals(10f, probe.medianMillis()!!, 1f)
    }

    @Test
    fun `emits nothing during the learning period`() {
        // The noise floor has to be learned against what is really on the input, not against our
        // own tone, or the threshold is set by the thing it is meant to detect.
        val probe = LoopbackProbe()
        probe.prepare(rate, block)
        val buffer = FloatArray(block)
        var peak = 0f
        repeat((rate * 0.9f / block).toInt()) {
            java.util.Arrays.fill(buffer, 0f)
            probe.process(buffer, block)
            for (v in buffer) if (abs(v) > peak) peak = abs(v)
        }
        assertEquals(0f, peak, 0f, "the probe emitted during its own learning window")
    }
}
