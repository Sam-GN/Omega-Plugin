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

import org.example.dsp.SincInterpolator
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Follows the drift between two ALSA devices at one nominal rate without a bridge (2.3.1).
 *
 * The lockstep loop reads a period, renders it and writes it, paced by the input. On two clocks the
 * output queue then grows or drains by their difference — delay that builds up, or a dropout. This
 * resamples what is about to be written by a few parts per million, so a period goes out as a frame
 * more or less now and then, and steers the audio in hand back to the level it settled at. Nothing
 * is spliced, and there is no cushion: the two periods already queued are the margin, as before. It
 * costs the kernel's half, [LATENCY_FRAMES].
 *
 * It replaced `ClockBridge` for same-rate pairs: that loop is driven by the output, and PipeWire at
 * 44.1 kHz on a 48 kHz graph stopped taking audio with the queue just over the mark the loop
 * waited for, so nothing played and nothing said so.
 *
 * The level is what `snd_pcm_delay` says, because the queue alone moves in the server's lumps and
 * shows a drift only once a whole lump has slipped. But the delay also holds whatever lies beyond the
 * queue, which a server changes when another program changes its period; [steer] is told the
 * queue as well, and learns its level again when the part beyond it moves by more than a lump could.
 *
 * One thread. Allocates nothing after construction.
 */
internal class DriftFollower(private val rate: Int, private val period: Int) {

    private val kernel = SincInterpolator(rate, rate)
    private val ring = FloatArray(Integer.highestOneBit(maxOf((period + SincInterpolator.TAPS) * 2, 2) - 1) shl 1)
    private val mask = ring.size - 1
    private var write = SincInterpolator.TAPS - 1
    private var pos = 0
    private var frac = 0.0

    private val kp = 2.0 * LOOP_RADIANS / rate
    private val ki = LOOP_RADIANS * LOOP_RADIANS / rate
    private var integral = 0.0
    private var correction = 0.0
    private var seen = 0.0
    private var learned = false
    private var target = 0.0
    private var average = 0.0
    private var beyond = 0.0
    private var beyondReference = 0.0
    private var sumLevel = 0.0
    private var sumBeyond = 0.0
    private var blocks = 0

    /** The most [process] can produce from one period. */
    val maxOutputFrames: Int = ceil(period * (1.0 + 2.0 * MAX_CORRECTION)).toInt() + 2

    // ---- What a log line or a test reads. ----

    @Volatile var correctionPpm = 0f
        private set
    @Volatile var levelMs = 0f
        private set
    @Volatile var targetMs = 0f
        private set

    /** Times the level beyond the queue moved and the target was learned again. */
    @Volatile var relearned = 0
        private set

    /** One rendered period in, [maxOutputFrames] or fewer out. Returns how many. */
    fun process(input: FloatArray, out: FloatArray): Int {
        var w = write
        for (i in 0 until period) ring[(w + i) and mask] = input[i]
        w += period
        write = w
        val step = 1.0 + correction
        var p = pos
        var f = frac
        var n = 0
        while (w - p >= SincInterpolator.TAPS && n < out.size) {
            out[n++] = kernel.at(ring, mask, p, f)
            f += step
            val whole = f.toInt()
            p += whole
            f -= whole
        }
        pos = p
        frac = f
        return n
    }

    /**
     * Once a period, before it is written: [level], the frames between the input and the speaker —
     * `snd_pcm_delay` and what the input is still holding — and [queued], the part of it in our queue.
     */
    fun steer(level: Double, queued: Double) {
        val dt = period.toDouble() / rate
        seen += dt
        if (seen < SETTLE_SECONDS) return
        if (!learned) {
            sumLevel += level
            sumBeyond += level - queued
            blocks++
            if (seen < SETTLE_SECONDS + LEARN_SECONDS) return
            target = sumLevel / blocks
            average = target
            beyondReference = sumBeyond / blocks
            beyond = beyondReference
            targetMs = (target * 1000.0 / rate).toFloat()
            learned = true
        }
        val a = minOf(1.0, dt / AVERAGE_SECONDS)
        average += (level - average) * a
        beyond += (level - queued - beyond) * a
        levelMs = (average * 1000.0 / rate).toFloat()
        if (abs(beyond - beyondReference) > BEYOND_PERIODS * period) {
            relearned++
            forget()
            // The drift is still the drift; only the level has to be found again.
            correction = (ki * integral).coerceIn(-MAX_CORRECTION, MAX_CORRECTION)
            correctionPpm = (correction * 1e6).toFloat()
            return
        }
        // Too much in hand: step faster through the input, so fewer frames go out.
        val error = average - target
        val unclamped = kp * error + ki * (integral + error * dt)
        if (abs(unclamped) < MAX_CORRECTION || unclamped * error < 0) integral += error * dt
        correction = (kp * error + ki * integral).coerceIn(-MAX_CORRECTION, MAX_CORRECTION)
        correctionPpm = (correction * 1e6).toFloat()
    }

    /** The stream restarted: the level is whatever it is primed to now. Keeps the drift learned. */
    fun restart() {
        java.util.Arrays.fill(ring, 0f)
        write = SincInterpolator.TAPS - 1
        pos = 0
        frac = 0.0
        forget()
    }

    private fun forget() {
        seen = 0.0
        learned = false
        sumLevel = 0.0
        sumBeyond = 0.0
        blocks = 0
    }

    companion object {
        /** What it adds: half the kernel. */
        const val LATENCY_FRAMES = SincInterpolator.HALF

        /** Before the level means anything: a stream takes a few periods in bursts as it starts. */
        const val SETTLE_SECONDS = 1.0

        /** The level is the mean over this long. */
        const val LEARN_SECONDS = 2.0

        const val AVERAGE_SECONDS = 1.0

        /** As `ClockBridge`'s loop, critically damped: far slower than any jitter. */
        const val LOOP_RADIANS = 0.1

        /** Half a cent, and several times what two real crystals disagree by. */
        const val MAX_CORRECTION = 0.0003

        /** The part beyond our queue may swing by a lump; further than this it has moved. */
        const val BEYOND_PERIODS = 1.5
    }
}
