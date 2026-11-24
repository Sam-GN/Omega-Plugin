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
package org.example.audio.alsa

import org.example.Allocation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DriftFollower] against two simulated clocks: an input whose crystal is out by so many ppm paces
 * the loop, and the output plays at its nominal rate, taking its audio in lumps as a server does.
 */
class DriftFollowerTest {

    private class Result(val start: Double, val end: Double, val lowest: Double, val ppm: Float, val relearned: Int)

    /**
     * [minutes] of it. [steer] off is 2.2.4's loop, which is how the test knows the drift is there.
     * [jumpAt] seconds in, the part beyond the queue grows by [jump] frames.
     */
    private fun run(
        rate: Int, period: Int, lump: Double, ppm: Double, minutes: Int,
        steer: Boolean = true, jumpAt: Double = -1.0, jump: Double = 0.0, lumpyDelay: Boolean = false,
    ): Result {
        val f = DriftFollower(rate, period)
        val input = FloatArray(period)
        val out = FloatArray(f.maxOutputFrames)
        val tick = period / (rate * (1 + ppm / 1e6))
        var t = 0.0
        var written = 2.0 * period
        var start = 0.0
        var end = 0.0
        var lowest = Double.MAX_VALUE
        val blocks = (minutes * 60 / tick).toInt()
        val tenSeconds = (10 / tick).toInt()
        for (b in 0 until blocks) {
            t += tick
            val n = f.process(input, out)
            // The server takes whole lumps; snd_pcm_delay interpolates between them.
            val pulled = floor(t * rate / lump) * lump
            val continuous = written - t * rate
            val downstream = lump + if (jumpAt in 0.0..t) jump else 0.0
            val heard = if (lumpyDelay) written - pulled else continuous
            if (steer) f.steer(heard + downstream, written - pulled)
            lowest = minOf(lowest, continuous)
            written += if (steer) n else period
            // Averaged over ten seconds: five seconds in, and the last.
            if (b in tenSeconds / 2 until tenSeconds / 2 + tenSeconds) start += continuous / tenSeconds
            if (b >= blocks - tenSeconds) end += continuous / tenSeconds
        }
        return Result(start, end, lowest, f.correctionPpm, f.relearned)
    }

    @Test
    fun `without it the drift builds up`() {
        // 100 ppm of 44100 is 4.41 frames a second; nineteen minutes and three quarters of it.
        val r = run(44_100, 256, 235.2, 100.0, 20, steer = false)
        assertEquals(4.41 * 1185, r.end - r.start, 10.0)
    }

    @Test
    fun `with it the delay ends where it started, whichever clock is fast`() {
        for (rate in intArrayOf(44_100, 48_000)) for (period in intArrayOf(64, 256, 1024)) {
            for (ppm in doubleArrayOf(100.0, 10.0, -100.0)) {
                for (lump in doubleArrayOf(256.0 * rate / 48_000, period.toDouble())) {
                    val r = run(rate, period, lump, ppm, 3)
                    val what = "$rate Hz, $period frames, lumps of $lump, $ppm ppm"
                    assertTrue(abs(r.end - r.start) < rate / 1000.0, "$what: moved ${r.end - r.start} frames")
                    assertTrue(abs(r.ppm - ppm) < 15, "$what: read ${r.ppm}")
                    assertTrue(r.lowest > -period * 0.5, "$what: fell to ${r.lowest}")
                    assertEquals(0, r.relearned, what)
                }
            }
        }
    }

    @Test
    fun `a server whose delay moves in lumps`() {
        for (ppm in doubleArrayOf(30.0, -30.0)) for (lump in doubleArrayOf(235.2, 256.0)) {
            val r = run(44_100, 256, lump, ppm, 8, lumpyDelay = true)
            // Not what PipeWire is believed to do, but not checked on one either. Eight minutes at
            // 30 ppm is 635 frames adrift; held within one lump. Equal lumps and periods let
            // a whole lump slip before anything shows, so the queue may once touch empty.
            assertTrue(abs(r.end - r.start) < lump, "$ppm ppm, lumps of $lump: moved ${r.end - r.start}")
            assertTrue(r.lowest > -1, "$ppm ppm, lumps of $lump: fell to ${r.lowest}")
        }
    }

    @Test
    fun `delay added beyond the queue does not move the queue`() {
        // Another program makes the server use a 4096-frame period: that is not audio we hold.
        val r = run(48_000, 256, 256.0, 20.0, 5, jumpAt = 60.0, jump = 4096.0)
        assertTrue(r.relearned in 1..2, "relearned ${r.relearned}")
        // What the correction can move in the second or two before it is noticed, 300 ppm for two
        // seconds, is 29 frames, and the drift goes on for the three it takes to learn the level.
        assertTrue(abs(r.end - r.start) < 96, "moved ${r.end - r.start}")
    }

    @Test
    fun `with no correction it is a delay of half the kernel and nothing else`() {
        val f = DriftFollower(48_000, 256)
        val input = FloatArray(256)
        val out = FloatArray(f.maxOutputFrames)
        input[10] = 1f
        val n = f.process(input, out)
        assertEquals(256, n)
        val peak = (0 until n).maxByOrNull { abs(out[it]) }!!
        assertEquals(10 + DriftFollower.LATENCY_FRAMES, peak)
    }

    @Test
    fun `a tone through it while it corrects has no grit`() {
        val rate = 48_000
        val period = 256
        val f = DriftFollower(rate, period)
        // Told there is far too much in hand after settling, so it corrects as hard as it may.
        val input = FloatArray(period)
        val out = FloatArray(f.maxOutputFrames)
        val seconds = 6
        val all = FloatArray(rate * seconds + 4096)
        var count = 0
        var at = 0L
        var wentShort = false
        for (b in 0 until rate * seconds / period) {
            for (i in 0 until period) input[i] = (0.5 * sin(2 * PI * 1000 * at++ / rate)).toFloat()
            val n = f.process(input, out)
            if (n != period) wentShort = true
            for (i in 0 until n) all[count++] = out[i]
            f.steer(if (b * period < rate * 3.2) 512.0 else 4000.0, if (b * period < rate * 3.2) 512.0 else 4000.0)
        }
        assertTrue(wentShort, "it never corrected, so this measured nothing")
        assertEquals(300f, f.correctionPpm, 1f)
        // The last second, at a steady 300 ppm: fit the tone where it now is and measure the rest.
        val n = rate
        val first = count - n
        val w = 2 * PI * 1000 * 1.0003 / rate
        var sc = 0.0; var ss = 0.0
        for (i in 0 until n) { sc += all[first + i] * cos(w * i); ss += all[first + i] * sin(w * i) }
        val a = 2 * sc / n
        val c = 2 * ss / n
        var rest = 0.0
        for (i in 0 until n) { val e = all[first + i] - a * cos(w * i) - c * sin(w * i); rest += e * e }
        val db = 10 * log10(rest / n / ((a * a + c * c) / 2))
        // The fit is exact only over whole cycles and 1000.3 Hz has 0.3 of one over: near -60 dB of
        // the fit's own error. A spliced tone measures 28.
        assertTrue(db < -55, "residue $db dB")
    }

    @Test
    fun `it allocates nothing`() {
        val f = DriftFollower(48_000, 256)
        val input = FloatArray(256)
        val out = FloatArray(f.maxOutputFrames)
        repeat(2_000) { f.process(input, out); f.steer(512.0, 512.0) }
        val before = Allocation.currentThreadBytes()
        repeat(20_000) { f.process(input, out); f.steer(512.0 + (it and 1), 512.0) }
        assertTrue(Allocation.currentThreadBytes() - before < 64 * 1024)
    }

    @Test
    fun `each pair gets the join it was measured on`() {
        assertEquals(AlsaJoin.FOLLOW, joinFor(44_100, 44_100, twoClocks = true, period = 256))
        assertEquals(AlsaJoin.FOLLOW, joinFor(44_100, 44_100, twoClocks = true, period = 1024))
        assertEquals(AlsaJoin.LOCKSTEP, joinFor(44_100, 44_100, twoClocks = false, period = 256))
        assertEquals(AlsaJoin.BRIDGE, joinFor(48_000, 44_100, twoClocks = true, period = 256))
        assertEquals(AlsaJoin.RESERVOIR, joinFor(48_000, 44_100, twoClocks = true, period = 512))
        assertEquals(AlsaJoin.RESERVOIR, joinFor(48_000, 44_100, twoClocks = false, period = 1024))
    }

    @Test
    fun `a stream that stops moving is said once, and a moving one never`() {
        val w = StallWatch(8)
        for (i in 0L until 100L) assertFalse(w.stalled(i))
        repeat(7) { assertFalse(w.stalled(99)) }
        assertTrue(w.stalled(99))
        assertFalse(w.stalled(99))
        // One that never moves at all.
        val dead = StallWatch(8)
        assertEquals(8, (0 until 20).first { dead.stalled(0) })
    }
}
