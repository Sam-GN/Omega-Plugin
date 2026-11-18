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
                // As the render loop reads: what the regulator says, the rest of the wake silence.
                val take = minOf(reg.read, level)
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

    /**
     * The hybrid when its output refuses the short period (2.2.4): Windows' ordinary shared engine,
     * 441 frames every 10 ms at 44.1 kHz, against the same input's 264 every 5.99 ms. Measured on
     * Bluetooth earbuds: twelve dropouts of one to three frames, every third wake — the packet
     * pattern repeats 2, 2, 1 — for a third of a second. Each read the ring down to empty, which
     * threw away the frames held back since the last, so the ring came back to the same place every
     * cycle; only drift between the two clocks let it out. Here the clocks agree, so nothing would.
     */
    @Test
    fun `on the ordinary shared period the measured input never drops out`() {
        // Every phase of the input's pattern against the output's wakes, matched clocks and 50 ppm
        // either way. The 2.2.3 regulator and read gave 3 to 75 dropouts in 30 s here, up to 42 of
        // them after the first second. Started at a wake plus a packet plus the margin, the ring's
        // low point sits a packet and the margin above a wake, and the input's worst lateness, 4 ms,
        // is 176 frames of that 352.
        for (offset in doubleArrayOf(0.0, 1_000.0, 2_000.0, 3_000.0, 4_000.0, 5_000.0)) {
            for (ppm in doubleArrayOf(0.0, 50.0, -50.0)) {
                val r = simulateFallback(seconds = 30.0, offsetMicros = offset, ppm = ppm) { k -> if (k % 5 == 0) 4_000.0 else 300.0 }
                assertEquals(0, r.underruns.size, "offset $offset us, $ppm ppm: dropouts at wakes ${r.underruns}")
            }
        }
    }

    @Test
    fun `on the ordinary shared period a late packet costs one gap, never a run of them`() {
        // Harsher than measured: lateness drawn at random, so a late packet can be followed by one
        // on time. Gaps may happen; what may not is the 2.2.3 run of one every third wake.
        val random = Random(7)
        val r = simulateFallback(seconds = 60.0, offsetMicros = 1_500.0, ppm = 0.0) {
            if (random.nextInt(5) == 0) 4_000.0 + random.nextDouble() * 1_000.0 else random.nextDouble() * 1_000.0
        }
        for (i in 1 until r.underruns.size) {
            assertTrue(r.underruns[i] - r.underruns[i - 1] >= 10, "dropouts at wakes ${r.underruns}: a run")
        }
        assertTrue(r.underruns.size <= 6, "${r.underruns.size} dropouts in a minute: ${r.underruns}")
    }

    private class FallbackResult(val underruns: List<Int>)

    /**
     * The fallback's shape: 441 frames every 10 ms at 44.1 kHz, against 264 every 5.99 ms, each
     * packet late by [lateness] of its index. The ring is read as the render loop reads it — what
     * [LowPointRegulator.read] says, the rest of the wake silence. Returns the wakes that underran.
     */
    private fun simulateFallback(seconds: Double, offsetMicros: Double, ppm: Double, lateness: (Int) -> Double): FallbackResult {
        val rate = 44_100
        val packet = 264
        val take = 441
        val reg = LowPointRegulator(rate, packet)
        val capturePeriod = packet.toDouble() / rate * 1e6 * (1 + ppm * 1e-6)
        val renderPeriod = take.toDouble() / rate * 1e6
        var level = 0
        var k = 1
        var nextCapture = capturePeriod + offsetMicros + lateness(k)
        var nextRender = renderPeriod
        var wake = 0
        val underruns = ArrayList<Int>()
        val end = seconds * 1e6
        while (nextRender < end) {
            if (nextCapture <= nextRender) {
                level += packet
                k++
                nextCapture = maxOf(k * capturePeriod + offsetMicros + lateness(k), nextCapture)
            } else {
                reg.decide(level, take)
                level -= reg.drop
                if (reg.underrun) underruns += wake
                level -= minOf(reg.read, level)
                if (reg.hold) level += 1
                nextRender += renderPeriod
                wake++
            }
        }
        return FallbackResult(underruns)
    }

    @Test
    fun `a backlog after a stall is cut, not worked off over seconds`() {
        // The stalled packets arrive together, 150 ms over the margin. A frame a wake would take
        // 7200 wakes, fourteen seconds; one second after, the ring must be back at its working level.
        val r = simulate(seconds = 12.0, stallAt = 10.0, stallMicros = 150_000.0, settleSeconds = 11.0)
        assertTrue(r.maxLevelMs <= swingMs + 3.0, "backlog still in the ring: ${r.maxLevelMs} ms")
    }
}
