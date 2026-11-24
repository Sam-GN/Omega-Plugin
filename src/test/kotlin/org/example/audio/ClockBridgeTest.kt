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

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bridge between two clocks, driven by simulated ones shaped like the pairing that exposed the
 * old regulator: a Pocket Master landing 287 frames at 44.1 kHz every 6.5 ms, late by up to a few
 * milliseconds, into a shared output taking 96 frames at 48 kHz every 2 ms.
 *
 * The bounds come from the numbers, not from the code. The one that matters most is the sound: a
 * 1 kHz sine through the bridge must carry no broadband noise above -80 dB — everything more than
 * 100 Hz from the tone, against the tone. A spliced sample is broadband; the same sine with 143
 * samples a second deleted, as the 2.2.4 regulator did, is measured too and must fail that bar.
 */
class ClockBridgeTest {

    private class Run(
        val bridge: ClockBridge,
        val underrunsAfterSettle: Int,
        /** Output after the settle time, when a signal was asked for. */
        val output: FloatArray,
        /** The most the averaged level stood above the target of the moment, after the settle time. */
        val maxExcessMs: Float,
    )

    /**
     * Event by event. Input packets fall due at the input's own clock — nominal rate times
     * `1 + ppm` — and arrive late by [lateness] of their index, never out of order. Output wakes
     * take [wake] frames on the output's clock, which is the reference.
     */
    private fun simulate(
        seconds: Double,
        inputRate: Int = 44_100,
        outputRate: Int = 48_000,
        packet: Int = 287,
        wake: Int = 96,
        ppm: Double = 0.0,
        settleSeconds: Double = 2.0,
        toneHz: Double = 0.0,
        prefillFrames: Int = 0,
        stallAt: Double = -1.0,
        stallSeconds: Double = 0.0,
        /** Frames the input reports it never captured, before packet k; see [ClockBridge.noteHole]. */
        hole: ((Int) -> Int)? = null,
        /** When packet k is stamped as landing, in µs, if not when it is seen; see [alsaRun]. */
        stamp: ((Int) -> Double)? = null,
        lateness: (Int) -> Double,
    ): Run {
        val bridge = ClockBridge(inputRate, outputRate, packet)
        bridge.begin(wake)
        val samples = FloatArray(packet)
        var index = 0L
        fun fill(count: Int) {
            for (i in 0 until count) {
                samples[i] = if (toneHz > 0) (0.5 * sin(2 * PI * toneHz * index / inputRate)).toFloat() else 0f
                index++
            }
        }
        // Anything queued before the output started.
        var pre = prefillFrames
        while (pre > 0) {
            val n = minOf(pre, packet)
            fill(n); bridge.write(samples, n, 0L); pre -= n
        }

        val packetMicros = packet * 1e6 / (inputRate * (1 + ppm * 1e-6))
        val wakeMicros = wake * 1e6 / outputRate
        var k = 1
        var lastArrival = 0.0
        var nextPacket = packetMicros + lateness(k)
        var nextWake = wakeMicros
        val end = seconds * 1e6
        val out = FloatArray(wake)
        val kept = FloatArray(if (toneHz > 0) ((seconds - settleSeconds) * outputRate).toInt() + wake else 0)
        var keptCount = 0
        var underrunsAtSettle = -1
        var maxAverage = -100f
        while (nextWake < end) {
            if (nextPacket <= nextWake) {
                hole?.invoke(k)?.let { if (it > 0) bridge.noteHole(it) }
                fill(packet)
                bridge.write(samples, packet, ((stamp?.invoke(k) ?: nextPacket) * 1000).toLong())
                lastArrival = nextPacket
                k++
                var t = k * packetMicros + lateness(k)
                if (stallAt >= 0 && t / 1e6 in stallAt..(stallAt + stallSeconds)) t = (stallAt + stallSeconds) * 1e6
                nextPacket = maxOf(t, lastArrival)
            } else {
                bridge.read(out, wake, (nextWake * 1000).toLong())
                val settled = nextWake / 1e6 >= settleSeconds
                if (settled) {
                    if (underrunsAtSettle < 0) underrunsAtSettle = bridge.underruns
                    maxAverage = maxOf(maxAverage, bridge.averageMs - bridge.targetMs)
                    if (kept.isNotEmpty()) { out.copyInto(kept, keptCount); keptCount += wake }
                }
                nextWake += wakeMicros
            }
        }
        return Run(bridge, bridge.underruns - maxOf(underrunsAtSettle, 0), kept.copyOf(keptCount), maxAverage)
    }

    private fun uniform(random: Random, maxMicros: Double): (Int) -> Double = { random.nextDouble() * maxMicros }

    /**
     * The worst broadband noise in [x], in dB under the tone: each 65 536-sample stretch is
     * windowed, transformed, and the power more than [guardHz] from [toneHz] is set against the
     * total. A spliced sample is broadband — one in a stretch is about -63 dB — while a ratio that
     * wanders by ppm over seconds moves the tone by a fraction of a hertz, inside the guard, which
     * is as inaudible as it is harmless. Kaiser β = 20 keeps the window's own leakage near -150 dB.
     */
    private fun worstNoiseDb(x: FloatArray, toneHz: Double, rate: Int, guardHz: Double = 100.0): Double {
        val n = 1 shl 16
        val window = DoubleArray(n) { i ->
            val r = 2.0 * i / (n - 1) - 1.0
            besselI0(20.0 * sqrt(maxOf(0.0, 1.0 - r * r))) / besselI0(20.0)
        }
        var worst = -300.0
        var start = 0
        while (start + n <= x.size) {
            val re = DoubleArray(n) { x[start + it] * window[it] }
            val im = DoubleArray(n)
            fft(re, im)
            var tone = 0.0
            var rest = 0.0
            for (k in 1 until n / 2) {
                val p = re[k] * re[k] + im[k] * im[k]
                if (abs(k.toDouble() * rate / n - toneHz) <= guardHz) tone += p else rest += p
            }
            worst = maxOf(worst, 10 * log10(maxOf(rest, 1e-30) / tone))
            start += n
        }
        assertTrue(worst > -300.0, "no stretch long enough to measure")
        return worst
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        var k = 1
        while (k < 200) {
            val half = x / 2.0 / k
            term *= half * half
            sum += term
            if (term < 1e-16 * sum) break
            k++
        }
        return sum
    }

    /** In place, radix 2. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(angle * k)
                    val wi = sin(angle * k)
                    val ar = re[i + k + len / 2]
                    val ai = im[i + k + len / 2]
                    val tr = ar * wr - ai * wi
                    val ti = ar * wi + ai * wr
                    re[i + k + len / 2] = re[i + k] - tr
                    im[i + k + len / 2] = im[i + k] - ti
                    re[i + k] += tr
                    im[i + k] += ti
                }
            }
            len = len shl 1
        }
    }

    @Test
    fun `the measured pairing never drops out`() {
        for (seed in 1L..5L) {
            val r = simulate(seconds = 60.0, settleSeconds = 0.0, lateness = uniform(Random(seed), 2_600.0))
            assertEquals(0, r.bridge.underruns, "seed $seed: dropouts")
        }
    }

    @Test
    fun `lateness beyond what it started for costs at most one dropout, then none`() {
        for (seed in 1L..5L) {
            val r = simulate(seconds = 60.0, settleSeconds = 5.0, lateness = uniform(Random(seed), 5_000.0))
            assertTrue(r.bridge.underruns <= 1, "seed $seed: ${r.bridge.underruns} dropouts")
            assertEquals(0, r.underrunsAfterSettle, "seed $seed: dropouts after it had learned the input")
        }
    }

    @Test
    fun `two crystals 200 ppm apart either way are followed, not spliced`() {
        for (ppm in doubleArrayOf(-200.0, 200.0)) {
            val r = simulate(seconds = 600.0, ppm = ppm, lateness = uniform(Random(3), 2_600.0))
            assertEquals(0, r.bridge.underruns, "$ppm ppm: dropouts")
            // Within the few ppm the input's lateness moves it by: a hundredth of a cent.
            assertTrue(abs(r.bridge.correctionPpm - ppm) < 15.0, "$ppm ppm: settled at ${r.bridge.correctionPpm} ppm")
        }
    }

    @Test
    fun `the level stays near the target and the target never grows on the measured input`() {
        val r = simulate(seconds = 120.0, settleSeconds = 10.0, ppm = 100.0, lateness = uniform(Random(4), 2_600.0))
        // Where it starts, from the numbers: the kernel 1.45 ms, the safety margin 1, a 2 ms wake,
        // half a 6.5 ms packet and the 3 ms allowance for lateness.
        val startMs = 64 * 1000.0 / 44_100 + 1.0 + 96 * 1000.0 / 48_000 + 287 * 500.0 / 44_100 + 3.0
        assertTrue(r.bridge.targetMs <= startMs + 0.01, "target grew to ${r.bridge.targetMs} ms from $startMs")
        assertTrue(r.maxExcessMs <= 1.0f, "level averaged ${r.maxExcessMs} ms over its target")
    }

    @Test
    fun `the measure catches what the old regulator did`() {
        // A perfect 1 kHz sine at 48 kHz with one sample deleted 143 times a second, as measured on
        // the Pocket Master into the Cirrus output in 2.2.4. If this read clean the tone tests
        // below would prove nothing.
        val rate = 48_000
        val out = FloatArray(rate * 3)
        var source = 0L
        for (i in out.indices) {
            if (i % (rate / 143) == 0) source++
            out[i] = (0.5 * sin(2 * PI * 1_000.0 * source / rate)).toFloat()
            source++
        }
        val db = worstNoiseDb(out, 1_000.0, rate)
        assertTrue(db > -60.0, "143 splices a second measured $db dB")
    }

    @Test
    fun `a tone through a converted pair comes out clean`() {
        // 100 ppm fast, with the measured lateness: the residual is the resampler's floor alone.
        val ppm = 100.0
        val r = simulate(seconds = 40.0, settleSeconds = 20.0, ppm = ppm, toneHz = 1_000.0, lateness = uniform(Random(5), 2_600.0))
        assertEquals(0, r.bridge.underruns)
        val db = worstNoiseDb(r.output, 1_000.0 * (1 + ppm * 1e-6), 48_000)
        assertTrue(db < -80.0, "worst residual $db dB")
    }

    @Test
    fun `a tone through a pair at one rate comes out clean`() {
        val ppm = -150.0
        val r = simulate(
            seconds = 40.0, settleSeconds = 20.0, inputRate = 48_000, packet = 264, ppm = ppm,
            toneHz = 1_000.0, lateness = uniform(Random(6), 2_600.0),
        )
        assertEquals(0, r.bridge.underruns)
        val db = worstNoiseDb(r.output, 1_000.0 * (1 + ppm * 1e-6), 48_000)
        assertTrue(db < -80.0, "worst residual $db dB")
    }

    @Test
    fun `a tone from a 96 kHz input comes out clean`() {
        val ppm = 50.0
        val r = simulate(
            seconds = 40.0, settleSeconds = 20.0, inputRate = 96_000, packet = 480, ppm = ppm,
            toneHz = 1_000.0, lateness = uniform(Random(7), 2_600.0),
        )
        assertEquals(0, r.bridge.underruns)
        val db = worstNoiseDb(r.output, 1_000.0 * (1 + ppm * 1e-6), 48_000)
        assertTrue(db < -80.0, "worst residual $db dB")
    }

    @Test
    fun `a stall is one dropout and one step of target, and nothing after`() {
        // 150 ms with nothing, then everything at once.
        val r = simulate(
            seconds = 30.0, settleSeconds = 0.0, stallAt = 10.0, stallSeconds = 0.15,
            lateness = uniform(Random(8), 2_600.0),
        )
        assertEquals(1, r.bridge.underruns)
        val start = ClockBridge(44_100, 48_000, 287).also { it.begin(96) }.targetMs
        assertTrue(r.bridge.targetMs <= start + ClockBridge.DROPOUT_STEP_MS + 0.01, "target rose to ${r.bridge.targetMs} from $start ms")
    }

    @Test
    fun `the backlog a stall delivers is thrown away, not kept as latency`() {
        // 150 ms of input lands at once when the stall ends. Kept, it would be 150 ms of delay for
        // good. Cut, the level restarts at the target; the packets came late, so it reads a couple
        // of milliseconds high until the controller has drained that, well inside ten seconds.
        val justAfter = simulate(
            seconds = 10.5, settleSeconds = 10.16, stallAt = 10.0, stallSeconds = 0.15,
            lateness = uniform(Random(9), 2_600.0),
        )
        assertTrue(justAfter.maxExcessMs <= 5.0f, "level ${justAfter.maxExcessMs} ms just after")
        val later = simulate(
            seconds = 21.0, settleSeconds = 20.0, stallAt = 10.0, stallSeconds = 0.15,
            lateness = uniform(Random(9), 2_600.0),
        )
        assertTrue(later.maxExcessMs <= 0.5f, "level ${later.maxExcessMs} ms ten seconds after")
    }

    @Test
    fun `audio queued before the output started is cut to the target`() {
        val r = simulate(seconds = 5.0, settleSeconds = 0.0, prefillFrames = 22_050, lateness = uniform(Random(10), 2_600.0))
        assertEquals(0, r.bridge.underruns)
        assertTrue(r.maxExcessMs <= 1.0f, "level ${r.maxExcessMs} ms: the half second was kept")
    }

    /**
     * Windows' ordinary shared period, which the hybrid falls back to when an output refuses the
     * short one: 441 frames every 10 ms against 264 every 6 ms, one packet in five 4 ms late. The
     * 2.2.3 regulator dropped out every third wake here.
     */
    @Test
    fun `on the ordinary shared period the measured input never drops out`() {
        for (ppm in doubleArrayOf(0.0, 50.0, -50.0)) {
            val r = simulate(
                seconds = 60.0, settleSeconds = 0.0, inputRate = 44_100, outputRate = 44_100, packet = 264,
                wake = 441, ppm = ppm, lateness = { k -> if (k % 5 == 0) 4_000.0 else 300.0 },
            )
            assertEquals(0, r.bridge.underruns, "$ppm ppm: dropouts")
        }
    }

    /**
     * The lateness the Pocket Master showed on hardware, which uniform jitter never modelled:
     * ordinary packets late by up to 2.6 ms, and now and then one 6 ms late. 2.3.0's first build
     * lowered its allowance after ten quiet seconds, walked it to nothing between spikes, and dropped
     * out at the next one, again and again. The first spike may cost one dropout — nothing has
     * shown the bridge it is coming — and no spike after it may.
     */
    @Test
    fun `a rare late packet costs at most one dropout, ever`() {
        for (seed in 1L..3L) {
            val random = Random(seed)
            val r = simulate(seconds = 600.0, settleSeconds = 0.0) { k ->
                if (k % 3_333 == 0) 6_000.0 else random.nextDouble() * 2_600.0      // one in ~20 s
            }
            assertTrue(r.bridge.underruns <= 1, "seed $seed: ${r.bridge.underruns} dropouts in ten minutes")
            assertEquals(0, r.bridge.backlogCuts, "seed $seed: lateness was taken for a backlog")
        }
    }

    /**
     * A stall as the hardware does it: nothing for 100 ms, then the late packets a quarter of a
     * millisecond apart rather than all at once, so the bridge has refilled to its target before
     * most of them land. Kept, they were the latency that built up after a big dropout; they are cut
     * once, and within a second the level is back at its target.
     */
    @Test
    fun `the late packets that follow a stall are cut, not kept`() {
        val packetMicros = 287 * 1e6 / 44_100
        val random = Random(11)
        var burst = 0
        val r = simulate(seconds = 11.2, settleSeconds = 11.0) { k ->
            val nominal = k * packetMicros
            if (nominal in 10.0e6..10.1e6) (10.1e6 - nominal) + (burst++) * 250.0 else random.nextDouble() * 2_600.0
        }
        assertEquals(1, r.bridge.underruns)
        assertEquals(1, r.bridge.backlogCuts)
        assertTrue(r.maxExcessMs <= 1.5f, "level stood ${r.maxExcessMs} ms over its target a second after the stall")
    }

    /**
     * What the Pocket Master did on hardware under WASAPI: now and then it captures nothing for
     * 30 to 50 ms and carries on, frame position unbroken, with nothing to catch up — every packet
     * after is that much later for good. The audio of the hole never existed, so it may cost a
     * dropout; it may not be followed by a cut, since there is no backlog. A guard, not a
     * reproduction: ten cuts were heard in one two-minute run on hardware, and this model of the
     * device does not cut on the old 12 ms accrual cap either, so what caused those is not known.
     */
    @Test
    fun `a hole in the input is never followed by a cut`() {
        val packetMicros = 287 * 1e6 / 44_100
        val random = Random(13)
        val r = simulate(seconds = 120.0, settleSeconds = 0.0) { k ->
            val holes = (k * packetMicros / 15e6).toInt()          // one more 35 ms hole every 15 s
            holes * 35_000.0 + random.nextDouble() * 2_600.0
        }
        assertEquals(0, r.bridge.backlogCuts, "${r.bridge.underruns} dropouts, ${r.bridge.backlogCuts} cuts")
    }

    /** Packet k's place in a run with a 35 ms hole every 15 s: holes so far, and the delay they add. */
    private fun holesBefore(k: Int): Int = (k * (287 * 1e6 / 44_100) / 15e6).toInt()

    private fun holeRun(report: Boolean, seed: Long): Run {
        val random = Random(seed)
        return simulate(
            seconds = 120.0, settleSeconds = 0.0,
            hole = if (report) { k -> if (holesBefore(k) > holesBefore(k - 1)) 35 * 44 else 0 } else null,
        ) { k -> holesBefore(k) * 35_000.0 + random.nextDouble() * 2_600.0 }
    }

    /**
     * Option 2 (2.3.0): a dropout the input caused by not capturing at all is not lateness, and
     * adding delay for it buys nothing — the audio does not exist at any latency. On hardware the
     * Pocket Master's 20-50 ms holes, each charged as lateness, took the bridge to 20.8 ms. Reported
     * as holes, they still drop out, and the target stays where it started.
     */
    @Test
    fun `a hole the input reports costs a dropout and no latency`() {
        val start = ClockBridge(44_100, 48_000, 287).also { it.begin(96) }.targetMs
        for (seed in 1L..3L) {
            val r = holeRun(report = true, seed = seed)
            assertTrue(r.bridge.underruns > 0, "seed $seed: the holes should have dropped out")
            assertEquals(r.bridge.underruns, r.bridge.holesForgiven, "seed $seed: a hole was charged as lateness")
            assertTrue(r.bridge.targetMs <= start + 0.01, "seed $seed: target grew to ${r.bridge.targetMs} from $start ms")
        }
    }

    /**
     * An input with no capture timestamps — ASIO — cannot report a hole, so the bridge judges by
     * what it delivered: the holes come back at the ordinary pace, short by 35 ms, and are holes.
     */
    @Test
    fun `holes nobody reports are recognised by what the input delivered`() {
        val start = ClockBridge(44_100, 48_000, 287).also { it.begin(96) }.targetMs
        for (seed in 1L..3L) {
            val r = holeRun(report = false, seed = seed)
            assertTrue(r.bridge.underruns > 0, "seed $seed: the holes should have dropped out")
            assertEquals(r.bridge.underruns, r.bridge.holesForgiven, "seed $seed: a hole was charged as lateness")
            assertTrue(r.bridge.targetMs <= start + 0.01, "seed $seed: target grew to ${r.bridge.targetMs} from $start ms")
        }
    }

    /**
     * The control, without which the two above prove nothing: a stall whose audio is late but whole
     * — 60 ms of nothing, then every packet at once — is lateness, and must still raise the target.
     */
    @Test
    fun `a stall that catches up is lateness and still raises the target`() {
        val start = ClockBridge(44_100, 48_000, 287).also { it.begin(96) }.targetMs
        val r = simulate(
            seconds = 12.0, settleSeconds = 0.0, stallAt = 10.0, stallSeconds = 0.06,
            lateness = uniform(Random(14), 2_600.0),
        )
        assertEquals(1, r.bridge.underruns)
        assertEquals(0, r.bridge.holesForgiven, "late audio was taken for a hole")
        assertTrue(r.bridge.targetMs >= start + ClockBridge.DROPOUT_STEP_MS - 0.01, "target stayed at ${r.bridge.targetMs} from $start ms")
    }
    @Test
    fun `ordinary lateness is never taken for a backlog`() {
        val r = simulate(seconds = 600.0, settleSeconds = 0.0, ppm = 150.0, lateness = uniform(Random(12), 2_600.0))
        assertEquals(0, r.bridge.backlogCuts)
        assertEquals(0, r.bridge.underruns)
    }

    /**
     * ALSA's shape (2.3.0): one thread, driven by the output. It looks at the input only when the
     * output wakes, so every packet the input captured meanwhile is seen at that wake. With capture
     * timestamps ([stamped]) each is stamped when the device's position last moved, which is when it
     * was captured; without, at the wake. Periods as `AlsaBackend` chooses them for a 256-frame
     * output at 48 kHz: 128 frames when the input runs at 48 kHz too (two cards), 64 at 44.1 kHz
     * when converted.
     */
    private fun alsaRun(
        inputRate: Int, packet: Int, ppm: Double, seconds: Double,
        stamped: Boolean = true, toneHz: Double = 0.0, settle: Double = 2.0,
    ): Run {
        val wakeMicros = 256 * 1e6 / 48_000
        val packetMicros = packet * 1e6 / (inputRate * (1 + ppm * 1e-6))
        return simulate(
            seconds = seconds, settleSeconds = settle, inputRate = inputRate, packet = packet, wake = 256,
            ppm = ppm, toneHz = toneHz,
            stamp = if (stamped) { k -> k * packetMicros } else null,
        ) { k ->
            val captured = k * packetMicros
            // Seen at the first output wake after it was captured, just before that wake reads.
            Math.ceil(captured / wakeMicros) * wakeMicros - 0.001 - captured
        }
    }

    @Test
    fun `ALSA two cards at one rate, 100 ppm apart either way for twenty minutes`() {
        for (ppm in doubleArrayOf(-100.0, 100.0)) {
            val r = alsaRun(48_000, 128, ppm, seconds = 1_200.0)
            assertEquals(0, r.bridge.underruns, "$ppm ppm: dropouts")
            assertTrue(abs(r.bridge.correctionPpm - ppm) < 15.0, "$ppm ppm: settled at ${r.bridge.correctionPpm} ppm")
        }
    }

    @Test
    fun `ALSA converted from 44_1 kHz, 100 ppm apart either way for twenty minutes`() {
        for (ppm in doubleArrayOf(-100.0, 100.0)) {
            val r = alsaRun(44_100, 64, ppm, seconds = 1_200.0)
            assertEquals(0, r.bridge.underruns, "$ppm ppm: dropouts")
            assertTrue(abs(r.bridge.correctionPpm - ppm) < 15.0, "$ppm ppm: settled at ${r.bridge.correctionPpm} ppm")
        }
    }

    /**
     * A driver or plugin that gives no capture timestamps: every packet stamped at the wake that saw
     * it. At an exact 2:1 of periods the level then wobbles on the slow beat between the two clocks,
     * about 27 s at 100 ppm, and the correction chases it — measured, it ended at +22 ppm against a
     * true -100. That is under half a cent and never a dropout, which is what this holds it to; the
     * timestamps are what make it exact.
     */
    @Test
    fun `ALSA without capture timestamps still never drops out`() {
        for (ppm in doubleArrayOf(-100.0, 100.0)) {
            val r = alsaRun(48_000, 128, ppm, seconds = 1_200.0, stamped = false)
            assertEquals(0, r.bridge.underruns, "$ppm ppm: dropouts")
            assertTrue(abs(r.bridge.correctionPpm) < 400.0, "$ppm ppm: correction ran to ${r.bridge.correctionPpm} ppm")
        }
    }

    @Test
    fun `a tone through ALSA's shape comes out clean`() {
        val ppm = 80.0
        val r = alsaRun(44_100, 64, ppm, seconds = 40.0, toneHz = 1_000.0, settle = 20.0)
        assertEquals(0, r.bridge.underruns)
        val db = worstNoiseDb(r.output, 1_000.0 * (1 + ppm * 1e-6), 48_000)
        assertTrue(db < -80.0, "worst residual $db dB")
    }

    @Test
    fun `neither side allocates`() {
        val bridge = ClockBridge(44_100, 48_000, 287)
        bridge.begin(96)
        val input = FloatArray(287) { sin(it * 0.1).toFloat() }
        val output = FloatArray(96)
        var owed = 0.0
        var now = 0L
        fun cycle(blocks: Int) {
            repeat(blocks) {
                owed += 96 * 44_100.0 / 48_000
                while (owed > 0) { bridge.write(input, 287, now); owed -= 287 }
                now += 2_000_000L
                bridge.read(output, 96, now)
            }
        }
        cycle(2_000)
        val before = org.example.Allocation.currentThreadBytes()
        cycle(20_000)
        val allocated = org.example.Allocation.currentThreadBytes() - before
        assertTrue(allocated < 64 * 1024, "allocated $allocated bytes over 20 000 blocks")
    }
}
