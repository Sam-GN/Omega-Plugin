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

import org.example.dsp.SincInterpolator
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Joins an input and an output that run on two different clocks, by resampling (2.3.0).
 *
 * Two devices never agree on the rate exactly, so audio either piles up between them or runs out.
 * Until 2.3.0 every two-clock path evened that out by deleting or repeating single samples, and
 * the WASAPI hybrid's controller mistook a USB input's ordinary packet lateness for drift: 12 565
 * splices in 88 s on a Pocket Master into a Cirrus Logic output, each a step in the waveform the
 * drive turned into grit, and 20 dropouts besides. Here nothing is ever spliced:
 *
 * - **The input goes into the ring as it arrives, at its own rate**, and the output side reads it
 *   through a [SincInterpolator] at a fractional position. The step between output samples is the
 *   nominal ratio — 44.1 / 48 when the input is converted, 1 when it is not — times `1 + c`, so the
 *   one conversion does both jobs.
 * - **`c` follows the drift, not the jitter.** Each packet is timestamped as it lands, and the level
 *   is measured as if the input arrived continuously ([smoothLevel]), so the packet-shaped sawtooth
 *   is gone before a wake samples it. That is averaged over [AVERAGE_SECONDS], and a PI controller
 *   steers the average to [target] with a loop about ten seconds slow. Real drift is tens of ppm;
 *   `c` is bounded by [MAX_CORRECTION], a few cents of pitch at the very most.
 * - **The level is set from what the input does, not to the edge.** [target] starts at the kernel,
 *   a wake, half a packet and [START_JITTER_MS] over [SAFETY_MS]. Each second the lowest spare seen
 *   is compared with the safety margin: below it, the target rises by the shortfall; ten seconds
 *   well above it lower the target half a millisecond, down to the same sum without the allowance.
 * - **A real stall is one dropout, not a run of them.** The block that cannot be finished fades out
 *   over [RAMP_MS], the target rises a step, the bridge refills in silence — throwing away anything
 *   beyond the target, so a backlog is not latency — and fades back in.
 *
 * One writer thread ([write]) and one reader thread ([read]), joined by volatile indices and the
 * time of the last landing. No allocation and no locks after construction, on either side.
 * `ClockBridgeTest` drives it with simulated clocks and measures what comes out: a 1 kHz tone
 * through it carries broadband noise 107 dB down at 44.1 to 48 kHz, where the splicing it replaces
 * measured 28.
 *
 * Built for the WASAPI hybrid first. Nothing in it is Windows': the other two-clock paths — WASAPI
 * exclusive and shared, ALSA's converting loop — can move onto it once the hybrid has proved it.
 */
class ClockBridge(
    private val inputRate: Int,
    private val outputRate: Int,
    /** The input's packet, in its own frames: the lump the level moves by when one lands. */
    private val packetFrames: Int,
) {
    private val ring = FloatArray(Integer.highestOneBit(maxOf(inputRate, 4096) * 2 - 1) shl 1)
    private val mask = ring.size - 1
    private val kernel = SincInterpolator(inputRate, outputRate)
    private val taps = SincInterpolator.TAPS

    /** Written by the input thread only. */
    @Volatile private var writeIndex = 0

    /** The oldest frame the reader still needs. Written by the output thread only. */
    @Volatile private var readIndex = 0

    // ---- Output side: touched only by the thread calling read. ----

    private val nominal = inputRate.toDouble() / outputRate
    private val ramp = maxOf(outputRate * RAMP_MS / 1000, 1)
    private val safety = inputRate * SAFETY_MS / 1000.0
    private val step = inputRate * STEP_MS / 1000.0
    private val dropoutStep = inputRate * DROPOUT_STEP_MS / 1000.0
    private val lowerMargin = inputRate * LOWER_MARGIN_MS / 1000.0
    private val backlog = inputRate * BACKLOG_MS / 1000.0
    private val backlogHold = outputRate * BACKLOG_HOLD_MS / 1000
    private val holeSlack = inputRate * HOLE_SLACK_MS / 1000.0
    private val accrualCap = maxOf(2.0 * packetFrames, inputRate * ACCRUAL_CAP_MS / 1000.0)
    private val maxTarget = taps + inputRate * MAX_TARGET_MS / 1000.0
    private val kp = 2.0 * DAMPING * LOOP_RADIANS / inputRate
    private val ki = LOOP_RADIANS * LOOP_RADIANS / inputRate

    private var pos = 0
    private var frac = 0.0
    private var running = false
    private var floorTarget = 0.0
    private var wakeFrames = 0
    private var target = 0.0
    private var average = 0.0
    private var integral = 0.0
    private var correction = 0.0
    private var fadeIn = 0
    private var windowFrames = 0
    private var windowMin = Double.MAX_VALUE
    private var windowUnderran = false
    private var quietWindows = 0
    private var previousMin = Double.MAX_VALUE
    private var previousUnderran = false
    private var previousHole = false
    private var holesAtWindow = 0

    /** A dropout waiting to be judged late or missing; see [judgeDropout]. */
    private var judging = false
    private var judgedAllowance = 0.0
    private var holesAtDropout = 0
    private var writeAtDropout = 0
    private var landedAtDropout = 0L
    private var resumedNanos = 0L

    /** Dropouts judged holes by what the input delivered, not by its say-so. Reader side. */
    private var judgedHoles = 0
    private var judgedAtWindow = 0

    /**
     * The most of the lateness allowance the input has used in any one second this session, in
     * input frames. The target is never lowered below the floor plus this and [LOWER_MARGIN_MS]:
     * an input late by 4 ms once a minute will be late by 4 ms again, and ten quiet seconds prove
     * nothing about it. 2.3.0's first build lowered on that evidence alone and walked the allowance
     * to zero; the dropouts came after.
     */
    private var peakUsed = 0.0

    /** The correction averaged over a minute of steady running: the drift, without what a backlog or a refill did to it. */
    private var driftEstimate = 0.0

    /** Wakes in a row the level has stood a backlog over the target, as output frames. */
    private var backlogFrames = 0

    /** A cut in progress: the read point `crossOffset` frames ahead fades in over `crossLength` samples. */
    private var crossOffset = 0
    private var crossLeft = 0
    private var crossLength = 0
    private val cutRamp = maxOf(outputRate * CUT_RAMP_MS / 1000, 1)

    // ---- What a log line or a test reads. ----

    /** Audio lost: blocks that could not be finished because the input had not delivered. */
    @Volatile var underruns = 0
        private set

    /** Input frames thrown away because the ring was full. Should stay zero. */
    @Volatile var overflows = 0
        private set

    /** True once audio has first played; false again while refilling after an underrun. */
    @Volatile var playing = false
        private set

    /** The current rate correction, in parts per million: the measured drift, once settled. */
    @Volatile var correctionPpm = 0f
        private set

    /** The level as the controller sees it — averaged — in milliseconds of input. */
    @Volatile var averageMs = 0f
        private set

    /** The level the bridge keeps, in milliseconds of input. */
    @Volatile var targetMs = 0f
        private set

    /** What the bridge adds on average, in output frames: the target, less the kernel's look-ahead. */
    @Volatile var latencyFrames = 0
        private set

    /** Backlogs cut back to the target, each with a crossfade. */
    @Volatile var backlogCuts = 0
        private set

    /** The most of the lateness allowance used in any second, in milliseconds. See [peakUsed]. */
    @Volatile var peakLatenessMs = 0f
        private set

    // ---- Input side: what the input's timing actually is. Written by the input thread only. ----

    private var lastLandedNanos = 0L

    /** The longest time between two packets landing, in milliseconds. A packet's period is the norm. */
    @Volatile var longestGapMs = 0f
        private set

    /** Packets that landed more than 2 ms, and more than 5 ms, later than one period after the last. */
    @Volatile var lateOver2Ms = 0
        private set
    @Volatile var lateOver5Ms = 0
        private set

    private val packetNanos = packetFrames * 1_000_000_000L / inputRate

    /** Holes the input has reported: stretches it never captured. See [noteHole]. */
    @Volatile var holes = 0
        private set

    /** The frames those holes were worth, in total. */
    @Volatile var holeFrames = 0L
        private set

    /** Dropouts judged to be holes, and so not charged as lateness. Output thread. */
    @Volatile var holesForgiven = 0
        private set

    /**
     * The input says it captured nothing for [frames] frames before the packet it is about to
     * [write] — from the device's own capture timestamps, which only a WASAPI input has. Call before
     * that write, on the input thread, so the reader sees the hole no later than the packet after it.
     */
    fun noteHole(frames: Int) {
        holeFrames += frames
        holes++
    }

    private fun noteLanding(nanos: Long) {
        val last = lastLandedNanos
        if (nanos == last) return          // more slices of the same packet
        lastLandedNanos = nanos
        if (last == 0L) return
        val gap = nanos - last
        val gapMs = gap / 1e6f
        if (gapMs > longestGapMs) longestGapMs = gapMs
        val late = gap - packetNanos
        if (late > 2_000_000L) lateOver2Ms++
        if (late > 5_000_000L) lateOver5Ms++
    }

    /**
     * When the newest frames landed, by `System.nanoTime` or the caller's own clock. Stored before
     * [writeIndex], so a reader that sees the new index sees this time with it.
     */
    @Volatile private var writeNanos = 0L

    /** Copies [count] input samples in, landed at [nanos]. Input thread only. */
    fun write(source: FloatArray, count: Int, nanos: Long) {
        var w = writeIndex
        val limit = readIndex + ring.size
        for (i in 0 until count) {
            if (w - limit >= 0) { overflows++; break }
            ring[w and mask] = source[i]
            w++
        }
        noteLanding(nanos)
        writeNanos = nanos
        writeIndex = w
    }

    /** [count] frames of silence in: the device reported a silent packet. Input thread only. */
    fun writeSilence(count: Int, nanos: Long) {
        var w = writeIndex
        val limit = readIndex + ring.size
        for (i in 0 until count) {
            if (w - limit >= 0) { overflows++; break }
            ring[w and mask] = 0f
            w++
        }
        noteLanding(nanos)
        writeNanos = nanos
        writeIndex = w
    }

    /**
     * The level as if the input arrived continuously: what is in the ring, plus what the input has
     * captured since its last packet landed, less half a packet.
     *
     * The ring itself is a sawtooth — up a packet when one lands, down a wake at each read — and an
     * output reading it at a different period samples that sawtooth at a phase that creeps round.
     * At the 10 ms fallback period against a 6 ms input the phase came round every 2.6 s, too slow
     * for any average to remove, and the controller chased it to 900 ppm and two dropouts a minute.
     * Counting the frames accrued since the last arrival takes the sawtooth out before it is
     * sampled. Accrual is capped at [ACCRUAL_CAP_MS] (or two packets, if longer), so an input that has
     * stopped is not imagined for long. It was two packets, 12 ms, until the Pocket Master's own
     * timestamps showed gaps of 30 to 50 ms between packets on hardware: past the cap the estimate
     * falls through such a gap and the controller reacts to a level that is not there. Raised on that
     * reasoning; the ten unexplained cuts heard in one run were not reproduced either way.
     */
    private fun smoothLevel(w: Int, nanos: Long): Double {
        val accrued = ((nanos - writeNanos) * inputRate / 1e9).coerceIn(0.0, accrualCap)
        return (w - pos) + accrued - packetFrames / 2.0
    }

    /**
     * Sets the working level for an output that takes up to [maxWakeFrames] a wake, and starts from
     * silence. Output thread, before the first [read]. Allocates nothing.
     */
    fun begin(maxWakeFrames: Int) {
        wakeFrames = maxWakeFrames
        floorTarget = floorFor(maxWakeFrames)
        setTarget(floorTarget + inputRate * START_JITTER_MS / 1000.0)
        running = false
        playing = false
        integral = 0.0
        correction = 0.0
        correctionPpm = 0f
        windowFrames = 0
        windowMin = Double.MAX_VALUE
        windowUnderran = false
        quietWindows = 0
        peakUsed = 0.0
        peakLatenessMs = 0f
        driftEstimate = 0.0
        backlogFrames = 0
        crossOffset = 0
        crossLeft = 0
        previousMin = Double.MAX_VALUE
        previousUnderran = false
        previousHole = false
        holesAtWindow = holes
        judgedAtWindow = judgedHoles
        judging = false
    }

    /** Fills [out] with exactly [frames] output samples, at [nanos] on the writer's clock. Output thread only. */
    fun read(out: FloatArray, frames: Int, nanos: Long) {
        val w = writeIndex
        // What this block needs in the ring to be finished: its frames at the ratio, and the kernel.
        val needed = taps + ceil(frames * nominal * (1.0 + MAX_CORRECTION)).toInt() + 1
        // The first input after a dropout: a hole, if there was one, was reported before it.
        if (judging) judgeDropout(w, nanos)
        if (!running) {
            // Keep no more than the target while waiting: what arrived before the output was ready,
            // or the backlog a stalled input delivers at once, would otherwise be latency for good.
            if (w - pos > needed) {
                val excess = (smoothLevel(w, nanos) - target).toInt()
                if (excess > 0) pos += minOf(excess, w - pos - needed)
            }
            readIndex = pos
            if (w - pos < needed || smoothLevel(w, nanos) < target - 1.0) {
                java.util.Arrays.fill(out, 0, frames, 0f)
                return
            }
            running = true
            playing = true
            frac = 0.0
            average = target
            // The drift is what it was before the gap; what the integral did while the level fell is not.
            integral = driftEstimate / ki
            backlogFrames = 0
            fadeIn = ramp
        }

        val dt = frames.toDouble() / outputRate
        val level = smoothLevel(w, nanos)

        // A backlog: the late packets of a stall, landing after the bridge has already refilled to
        // its target. Draining it at the controller's 2000 ppm would hold it as delay for half a
        // minute — the latency that built up after a big dropout in 2.3.0's first build. Once the
        // level has stood that far over the target for long enough not to be jitter, the excess is
        // cut: the read point moves ahead, crossfaded over [CUT_RAMP_MS], so nothing steps.
        val excess = level - target
        if (crossLeft == 0 && excess > backlog) backlogFrames += frames else backlogFrames = 0
        if (backlogFrames >= backlogHold) {
            val cut = excess.toInt()
            if (w - (pos + cut) >= needed) {
                crossOffset = cut
                crossLeft = cutRamp
                crossLength = cutRamp
                average -= cut
                integral = driftEstimate / ki
                backlogCuts++
            }
            backlogFrames = 0
        }

        average += (level - crossOffset - average) * minOf(1.0, dt / AVERAGE_SECONDS)
        averageMs = (average * 1000.0 / inputRate).toFloat()
        val error = average - target
        // Too full: read faster. Integrate only while the correction is inside its bounds, or when
        // integrating would bring it back.
        val unclamped = kp * error + ki * (integral + error * dt)
        if (abs(unclamped) < MAX_CORRECTION || unclamped * error < 0) integral += error * dt
        correction = (kp * error + ki * integral).coerceIn(-MAX_CORRECTION, MAX_CORRECTION)
        correctionPpm = (correction * 1e6).toFloat()
        // Learn the drift only while the level is where it belongs, so a backlog or a refill never
        // teaches it anything.
        if (abs(error) < safety && crossLeft == 0) {
            driftEstimate += (correction - driftEstimate) * minOf(1.0, dt / DRIFT_SECONDS)
        }
        val ratio = nominal * (1.0 + correction)

        var p = pos
        var f = frac
        var n = 0
        while (n < frames) {
            if (w - (p + crossOffset) < taps) break
            var v = kernel.at(ring, mask, p, f)
            if (crossLeft > 0) {
                val ahead = kernel.at(ring, mask, p + crossOffset, f)
                val g = (crossLength - crossLeft + 1).toFloat() / (crossLength + 1)
                v += (ahead - v) * g
                if (--crossLeft == 0) {
                    p += crossOffset
                    crossOffset = 0
                }
            }
            out[n] = v
            f += ratio
            val whole = f.toInt()
            p += whole
            f -= whole
            n++
        }
        pos = p
        frac = f
        readIndex = p

        if (fadeIn > 0) {
            var i = 0
            while (i < n && fadeIn > 0) {
                out[i] *= (ramp - fadeIn + 1).toFloat() / (ramp + 1)
                fadeIn--
                i++
            }
        }

        if (n < frames) {
            // The input has not delivered. Fade out what this block did get, then silence until the
            // ring is back at the target.
            val m = minOf(n, ramp)
            for (i in 0 until m) out[n - 1 - i] *= (i + 1).toFloat() / (m + 1)
            java.util.Arrays.fill(out, n, frames, 0f)
            underruns++
            running = false
            playing = false
            windowUnderran = true
            crossOffset = 0
            crossLeft = 0
            // Late or missing? Not known until the input comes back: see [judgeDropout].
            judging = true
            judgedAllowance = target - floorTarget
            holesAtDropout = holes
            writeAtDropout = w
            landedAtDropout = writeNanos
            resumedNanos = 0L
        } else {
            // Spare against the level's own average, not the raw low point: after a hole the whole
            // level sits lower until the controller has refilled it, and that is not lateness.
            val spare = (w - p - taps).toDouble() - (average - target)
            if (spare < windowMin) windowMin = spare
        }

        windowFrames += frames
        if (windowFrames >= outputRate) adapt()
    }

    /**
     * Once a second: move the target by what the input has shown it needs.
     *
     * Up at once, by what the second's lowest point fell short of the safety margin and a step more.
     * Down slowly — a step after [QUIET_WINDOWS_TO_LOWER] seconds without needing it — and never
     * below the floor, the most lateness seen this session ([peakUsed]) and [LOWER_MARGIN_MS].
     */
    private fun adapt() {
        // A second is judged one second late, so a hole that began in it and was only reported when
        // the input resumed, in the next, still clears it: a hole is missing audio, which no amount
        // of allowance would have covered, and must not be charged as lateness (2.3.0, option 2).
        val holeNow = holes != holesAtWindow || judgedHoles != judgedAtWindow
        judgedAtWindow = judgedHoles
        holesAtWindow = holes
        val judgeable = !previousUnderran && !previousHole && !holeNow && previousMin != Double.MAX_VALUE
        if (judgeable) {
            notePeak((target - floorTarget) - (previousMin - safety))
            if (previousMin < safety) {
                setTarget(target + (safety - previousMin) + step)
                quietWindows = 0
            } else if (++quietWindows >= QUIET_WINDOWS_TO_LOWER) {
                val lowest = floorTarget + peakUsed + lowerMargin
                if (target - step >= lowest) setTarget(target - step)
                quietWindows = 0
            }
        } else if (previousUnderran || holeNow) {
            quietWindows = 0
        }
        previousMin = windowMin
        previousUnderran = windowUnderran
        previousHole = holeNow
        windowUnderran = false
        windowMin = Double.MAX_VALUE
        windowFrames = 0
    }

    /**
     * After a dropout, once the input has been delivering again for [JUDGE_MS]: was it late, or did
     * it stop? Returns true once judged.
     *
     * Late audio is what the allowance is for, so a late one raises the target and records the
     * lateness. A hole is missing audio: the dropout was unavoidable at any latency, and raising the
     * target for it only adds delay. On the Pocket Master the holes ran 20 to 50 ms every fifteen
     * seconds under WASAPI and up to 35 ms under its ASIO driver, and charging each as lateness took
     * the bridge to 20 ms and prevented nothing.
     *
     * Two ways to know. An input with capture timestamps says so through [noteHole]. Any other —
     * ASIO — is judged by what it delivered: late audio comes back with a burst that catches up, so
     * from the last landing before the stall to the latest one the input has delivered all that time
     * was worth; missing audio comes back at the ordinary pace and stays short by the hole. Short by
     * more than a packet and [HOLE_SLACK_MS] is a hole.
     */
    private fun judgeDropout(w: Int, nanos: Long): Boolean {
        if (holes != holesAtDropout) {
            judging = false
            holesForgiven++
            return true
        }
        if (w == writeAtDropout) return false
        if (resumedNanos == 0L) resumedNanos = nanos
        if (nanos - resumedNanos < JUDGE_MS * 1_000_000L) return false
        judging = false
        val worth = (writeNanos - landedAtDropout) * inputRate / 1e9
        val delivered = (w - writeAtDropout).toDouble()
        if (landedAtDropout != 0L && worth - delivered > packetFrames + holeSlack) {
            holesForgiven++
            judgedHoles++
            return true
        }
        notePeak(judgedAllowance)
        setTarget(target + dropoutStep)
        return true
    }

    private fun notePeak(used: Double) {
        if (used > peakUsed) {
            peakUsed = used
            peakLatenessMs = (used * 1000.0 / inputRate).toFloat()
        }
    }

    /**
     * The level is sampled at a wake, before it reads, so it must always cover a whole wake and the
     * kernel; packets landing in lumps swing it half a packet either side of its average.
     */
    private fun floorFor(maxWakeFrames: Int): Double =
        taps + safety + maxWakeFrames * nominal + packetFrames / 2.0

    private fun setTarget(value: Double) {
        target = minOf(value, maxTarget)
        targetMs = (target * 1000.0 / inputRate).toFloat()
        latencyFrames = latencyOf(target, wakeFrames)
    }

    /**
     * The average delay a sample spends here, in output frames, at a [level] sampled before a wake
     * of [wake] frames reads: the first sample read has waited the level less the kernel's
     * look-ahead, the last a wake less, so on average half a wake less.
     */
    private fun latencyOf(level: Double, wake: Int): Int =
        ceil((level - SincInterpolator.HALF - wake * nominal / 2.0) / nominal).toInt()

    /**
     * What [latencyFrames] will be at the start, for an output taking up to [maxWakeFrames] a wake —
     * so a stream can declare it before its output thread has called [begin]. Any thread.
     */
    fun startingLatencyFrames(maxWakeFrames: Int): Int =
        latencyOf(minOf(floorFor(maxWakeFrames) + inputRate * START_JITTER_MS / 1000.0, maxTarget), maxWakeFrames)

    /**
     * Runs both sides over a few hundred blocks of noise on a throwaway instance, so the first real
     * wakes are compiled code. Call on the output thread before the stream starts; it allocates.
     */
    fun warmUp(wakeFrames: Int) {
        val scratch = ClockBridge(inputRate, outputRate, packetFrames)
        scratch.begin(wakeFrames)
        val input = FloatArray(packetFrames) { ((it * 2654435761L.toInt()) shr 9) * 1e-10f }
        val output = FloatArray(wakeFrames)
        val wakeNanos = wakeFrames * 1_000_000_000L / outputRate
        var owed = 0.0
        var now = 0L
        repeat(WARM_UP_BLOCKS) {
            owed += wakeFrames * nominal
            while (owed > 0) { scratch.write(input, packetFrames, now); owed -= packetFrames }
            now += wakeNanos
            scratch.read(output, wakeFrames, now)
        }
    }

    companion object {
        /** The level is averaged over this long before the controller sees it. */
        const val AVERAGE_SECONDS = 1.0

        /**
         * The loop's natural frequency, rad/s: about ten seconds to settle, far below any jitter. At
         * 0.2 a USB input's ordinary lateness moved the ratio by up to 90 ppm with no drift at all;
         * inaudible, but no reason to have it.
         */
        const val LOOP_RADIANS = 0.1

        /** Critically damped: no overshoot after a step in the target. */
        const val DAMPING = 1.0

        /** 2000 ppm: ten times any real crystal, and 3.5 cents of pitch at the very most. */
        const val MAX_CORRECTION = 0.002

        /** Spare the lowest point of the level is kept above, beyond what the next output needs. */
        const val SAFETY_MS = 1

        /** Allowance for the input's lateness before any has been measured. */
        const val START_JITTER_MS = 3

        /** How far the target is lowered at a time, and the margin added when a second falls short. */
        const val STEP_MS = 0.5

        /** How far a dropout raises the target. */
        const val DROPOUT_STEP_MS = 1.0

        /** Kept over the floor and the worst lateness seen, however long the input behaves. */
        const val LOWER_MARGIN_MS = 1.0

        /** A level this far over the target, held for [BACKLOG_HOLD_MS], is a backlog and is cut. */
        const val BACKLOG_MS = 8

        /** Long enough that a burst of jitter is never taken for a backlog. */
        const val BACKLOG_HOLD_MS = 30

        /** How long after the input resumes a dropout is judged, for a catch-up burst to have landed. */
        const val JUDGE_MS = 30

        /** Beyond a packet, the shortfall that makes a dropout a hole rather than lateness. */
        const val HOLE_SLACK_MS = 2

        /** How long past the last packet the level estimate goes on counting input. */
        const val ACCRUAL_CAP_MS = 50

        /** The crossfade across a cut. */
        const val CUT_RAMP_MS = 3

        /** How long the correction is averaged to learn the drift. */
        const val DRIFT_SECONDS = 60.0

        /** The most the target may reach above the kernel. */
        const val MAX_TARGET_MS = 40

        /** Seconds in a row without needing the allowance before the target is lowered a step. */
        const val QUIET_WINDOWS_TO_LOWER = 60

        /** Fade at either edge of a dropout. */
        const val RAMP_MS = 1

        private const val WARM_UP_BLOCKS = 400
    }
}
