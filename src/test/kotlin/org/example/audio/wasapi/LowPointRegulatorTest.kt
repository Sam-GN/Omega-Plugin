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
package org.example.audio.wasapi

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hybrid's ring, driven by two simulated clocks shaped like the pair it was built on: an
 * exclusive input landing 264 frames at 44.1 kHz (287.3 at the engine's 48) every 5.99 ms with up
 * to 2.6 ms of lateness, and a shared output taking 96 frames every 2 ms. The bounds come from
 * those numbers, not from the code: a ring held as tight as it safely can holds one wake plus the
 * margin at its low point, and swings above that by up to one packet plus the jitter — a packet
 * 2.6 ms late followed by one on time.
 */
class LowPointRegulatorTest {

    private val rate = 48_000
    private val packet = 287   // 264 frames at 44.1 kHz, in 48 kHz frames
    private val want = 96

    /** One wake, one packet, the jitter, and a frame-a-wake correction in flight, in ms. */
    private val swingMs = 2.0 + 6.0 + 2.6 + 1.0

    private class Result(val underrunsAfterSettle: Int, val meanLevelMs: Double, val maxLevelMs: Double, val finalMargin: Int)

    /**
     * Event-by-event: capture packets arrive late by a random amount but never out of order;
     * render wakes take [want] frames on time. Returns what a listener would care about after
     * [settleSeconds]: dropouts, and how much audio sat in the ring at each wake.
     */
    private fun simulate(
        seconds: Double,
        jitterMicros: Double = 2_600.0,
        ppm: Double = 0.0,
        settleSeconds: Double = 2.0,
        stallAt: Double = -1.0,
        stallMicros: Double = 0.0,
        prefill: Int = 0,
        seed: Long = 1,
    ): Result {
        val random = Random(seed)
        val reg = LowPointRegulator(rate, packet)
        val capturePeriod = 264.0 / 44_100 * 1e6 * (1 + ppm * 1e-6)
        val renderPeriod = want.toDouble() / rate * 1e6
        var level = prefill
        var produced = 0.0
        var k = 0
        var lastDelivery = 0.0
        var nextRender = renderPeriod
        var underruns = 0
        var levelSum = 0.0
        var levelMax = 0
        var wakes = 0
        val end = seconds * 1e6
        var nextCapture = capturePeriod + random.nextDouble() * jitterMicros
        while (nextRender < end) {
            if (nextCapture <= nextRender) {
                // Frames due by this packet's nominal time, at the engine's rate.
                produced += 264.0 * rate / 44_100
                val whole = produced.toInt()
                level += whole
                produced -= whole
                k++
                lastDelivery = nextCapture
                var t = (k + 1) * capturePeriod + random.nextDouble() * jitterMicros
                if (stallAt > 0 && t / 1e6 in stallAt..(stallAt + stallMicros / 1e6)) t = stallAt * 1e6 + stallMicros
                nextCapture = maxOf(t, lastDelivery)
            } else {
                reg.decide(level, want)
                level -= reg.drop
                val settled = nextRender / 1e6 > settleSeconds
                if (reg.underrun && settled) underruns++
                if (settled && reg.primed) {
                    levelSum += level; levelMax = maxOf(levelMax, level); wakes++
                }
                val take = minOf(want, level)
                level -= take
                if (reg.hold) level += 1
                nextRender += renderPeriod
            }
        }
        val ms = 1000.0 / rate
        return Result(underruns, levelSum / wakes * ms, levelMax * ms, reg.margin)
    }

    @Test
    fun `the measured pairing runs without a dropout once the margin has settled`() {
        for (seed in 1L..5L) {
            val r = simulate(seconds = 60.0, seed = seed)
            assertEquals(0, r.underrunsAfterSettle, "seed $seed: dropouts after the first two seconds")
        }
    }

    @Test
    fun `the ring holds a wake, the margin and at most a packet, and no more`() {
        val r = simulate(seconds = 60.0)
        val ceilingMs = swingMs + r.finalMargin * 1000.0 / rate
        assertTrue(r.maxLevelMs <= ceilingMs, "ring reached ${r.maxLevelMs} ms against $ceilingMs")
        // The old rule's ceiling was three output wakes plus a packet and then some; this one
        // averages under half a packet over the low point.
        assertTrue(r.meanLevelMs < 2.0 + r.finalMargin * 1000.0 / rate + 6.0, "ring averaged ${r.meanLevelMs} ms")
    }

    @Test
    fun `two crystals 200 ppm apart either way stay bounded for ten minutes`() {
        for (ppm in doubleArrayOf(-200.0, 200.0)) {
            val r = simulate(seconds = 600.0, ppm = ppm)
            assertEquals(0, r.underrunsAfterSettle, "$ppm ppm: dropouts")
            assertTrue(r.maxLevelMs <= swingMs + 10.0, "$ppm ppm: ring grew to ${r.maxLevelMs} ms")
        }
    }

    @Test
    fun `audio queued before the output started is cut at the first wake`() {
        // Half a second of input already waiting: left alone it would be half a second of delay.
        val r = simulate(seconds = 10.0, prefill = rate / 2, settleSeconds = 0.0)
        assertTrue(r.maxLevelMs <= swingMs + 3.0, "start was not trimmed: ${r.maxLevelMs} ms")
    }

    @Test
    fun `a stall costs one step of margin, not one per empty wake or per window`() {
        // A 150 ms stall is ~75 empty wakes over three windows; charging each wake a step would hit
        // the 10 ms cap, and each window, 3.5 ms.
        val r = simulate(seconds = 30.0, stallAt = 10.0, stallMicros = 150_000.0)
        val marginMs = r.finalMargin * 1000.0 / rate
        assertTrue(marginMs <= 2.0 + 1.0, "one stall raised the margin to $marginMs ms")
    }

    @Test
    fun `a backlog after a stall is cut, not worked off over seconds`() {
        // The stalled packets arrive together, 150 ms over the margin. A frame a wake would take
        // 7200 wakes, fourteen seconds; one second after, the ring must be back at its working level.
        val r = simulate(seconds = 12.0, stallAt = 10.0, stallMicros = 150_000.0, settleSeconds = 11.0)
        assertTrue(r.maxLevelMs <= swingMs + 3.0, "backlog still in the ring: ${r.maxLevelMs} ms")
    }
}
