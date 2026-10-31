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
package org.example.dsp.loop

import org.example.dsp.DEFAULT_SAMPLE_RATE
import org.example.dsp.Parameter
import org.example.dsp.Resampler
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Where a lane's playback enters the signal. Recording always takes the finished sound after the master. */
enum class LaneOrder {
    /** Added after the master, under the live guitar: heard as it was recorded. */
    AFTER,

    /** Added to the input ahead of the boards, so the pedals run over it again. */
    THROUGH,
}

/**
 * One lane of the looper: a buffer of up to [Looper.MAX_SECONDS] of mono audio, and how it plays.
 *
 * The audio thread reads [buffer], [length], [active], [order], [level] and the record flags every
 * block and writes [buffer] while recording. Everything it reads is a volatile scalar or an array
 * reference published through one; everything the UI reads back ([length], [recording],
 * [recordedFrames]) is the same. No lock anywhere, by the rules in CLAUDE.md.
 */
class Lane internal constructor(val index: Int) {

    /** Sized by [Looper.prepare] for the stream's rate; empty until a rate is known. */
    @Volatile
    var buffer: FloatArray = FloatArray(0)
        internal set

    /** Frames of audio in [buffer] — recorded, or loaded. Zero is an empty lane. */
    @Volatile
    var length: Int = 0
        internal set

    /** Heard, or silent. The toggle on the lane, and what a bound key flips. */
    @Volatile
    var active: Boolean = true

    @Volatile
    var order: LaneOrder = LaneOrder.AFTER

    /** The lane's volume knob: a percentage with the master's squared taper. */
    val level = Parameter("Level", 0f, 100f, 80f, "%")

    /** Where the audio came from, for the panel: a file's name, "Recorded", or null when empty. */
    @Volatile
    var source: String? = null

    /** Set by the UI to ask for a recording; the audio thread turns it into [recording] at the loop's top. */
    @Volatile
    var armed: Boolean = false
        internal set

    /** True while the audio thread is writing this lane. */
    @Volatile
    var recording: Boolean = false
        internal set

    /** Set by the UI to end a recording or cancel an arm; cleared by the audio thread. */
    @Volatile
    internal var stopRequested: Boolean = false

    /** Frames written in the current or last recording. Read by the panel to draw as it grows. */
    @Volatile
    var recordedFrames: Int = 0
        internal set

    /** Current gain, ramped by the audio thread towards the knob or towards silence. */
    internal var gain = 0f

    /** Sample of the current block at which recording starts, or -1. Audio thread only. */
    internal var startAt = -1

    val isEmpty: Boolean get() = length == 0

    /** The gain the knob asks for, ignoring the ramp: the master's taper, `(percent / 100)²`. */
    fun targetGain(): Float {
        val x = level.value / 100f
        return x * x
    }
}

/**
 * The loop box: up to four lanes of a minute each, played in step and recorded one at a time.
 *
 * Shaped by the threading contract rather than by looper conventions. Every lane's buffer is
 * allocated in [prepare], on the opening thread, so recording is a copy into memory that exists and
 * playback is an add from it. The three calls the engine makes per block — [begin], [mixThrough],
 * [finish] — allocate nothing, take no lock and share one playhead, so a lane added before the
 * boards and a lane added after them are heard at the same position.
 *
 * **One loop length.** The first lane recorded sets it; a later lane records from the top of the
 * loop and stops by itself when it reaches the length, so the lanes stay in time with each other.
 * The length is free again once every lane is empty. A lane loaded from a file keeps its own length
 * and simply plays its first [loopLength] frames.
 *
 * **Recording starts at the top of the loop**, sample-accurately: [begin] knows where in the block
 * the playhead wraps and [finish] starts copying from that sample. With no loop yet it starts at the
 * next block and the playhead is reset to zero, so the first lane's first sample is its first frame.
 *
 * What is recorded is whatever buffer [finish] is handed — the engine hands it the sum after the
 * master, before it adds the lanes that play after. A lane playing *through* the boards is in that
 * sum, processed by the pedals like the guitar, and will be in anything recorded while it plays;
 * that is what through means and a real rig does the same.
 *
 * Lanes outlive everything but the process: they are not cleared by [prepare] — a rate change
 * resamples them — nor by the panel being hidden, nor by the stream stopping.
 */
class Looper {

    val lanes: Array<Lane> = Array(MAX_LANES) { Lane(it) }

    /** How many lanes the panel shows. The audio thread ignores it: an unshown lane is empty. */
    @Volatile
    var laneCount: Int = 1

    /**
     * Loop mode: whether anything is heard or recorded. Off, every lane ramps to silence and the
     * playhead then stops where it is, so switching on again resumes rather than restarts.
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * The transport: paused, every lane ramps to silence and the playhead holds where it is, so
     * play resumes rather than restarts. Starts paused — opening the box is not a reason to play —
     * and REC or PLAY on the panel starts it. Distinct from [enabled], which is the loop board
     * being there at all.
     */
    @Volatile
    var playing: Boolean = false

    /** Frames in one pass of the loop, or zero while no lane has set it. */
    @Volatile
    var loopLength: Int = 0
        private set

    /** The playhead, in frames from the top of the loop. Written by the audio thread, read by the panel. */
    @Volatile
    var position: Int = 0
        private set

    var sampleRate: Float = DEFAULT_SAMPLE_RATE
        private set

    /** Frames a lane can hold at the current rate: [MAX_SECONDS] of it. */
    @Volatile
    var capacity: Int = 0
        private set

    private var smoothing = smoothingFor(DEFAULT_SAMPLE_RATE)

    /** Where the current block began, and where in it the loop wraps (or -1). Audio thread only. */
    private var blockStart = 0
    private var wrapAt = -1

    /**
     * Whether this block does anything: loop mode on, or a lane whose gain has not yet reached zero
     * after it went off. Decided once per block in [begin], so a disabled looper is run until it is
     * silent — a fade, not a cut — and then costs four reads a block.
     */
    private var running = false

    /**
     * Binds the lanes to the stream's rate: allocates the buffers, or resamples what is in them
     * when the rate has changed. On the opening thread, never the audio thread.
     */
    fun prepare(sampleRate: Float) {
        val newCapacity = (sampleRate * MAX_SECONDS).roundToInt()
        val rateChanged = this.sampleRate != sampleRate && capacity > 0
        if (newCapacity != capacity || rateChanged) {
            for (lane in lanes) {
                val old = lane.buffer
                val oldLength = lane.length
                val fresh = FloatArray(newCapacity)
                if (oldLength > 0 && rateChanged) {
                    lane.length = resample(old, oldLength, this.sampleRate, sampleRate, fresh)
                } else if (oldLength > 0) {
                    System.arraycopy(old, 0, fresh, 0, minOf(oldLength, newCapacity))
                    lane.length = minOf(oldLength, newCapacity)
                }
                lane.buffer = fresh
            }
            if (rateChanged && loopLength > 0) {
                loopLength = scale(loopLength, this.sampleRate, sampleRate).coerceIn(1, newCapacity)
                position = scale(position, this.sampleRate, sampleRate).coerceIn(0, loopLength - 1)
            }
            capacity = newCapacity
        }
        this.sampleRate = sampleRate
        smoothing = smoothingFor(sampleRate)
    }

    // ---------------------------------------------------------------- UI side

    /**
     * Asks for [lane] to be recorded. It starts at the top of the loop, or now when there is none;
     * the audio thread does the starting. A lane already recording is left alone.
     */
    fun arm(lane: Lane) {
        // One at a time. Two lanes armed with no loop yet would both start, each resetting the
        // playhead; the panel disables REC on the other lanes while one records, and this is the
        // same rule kept where it cannot be bypassed.
        if (isRecording) return
        lane.stopRequested = false
        lane.armed = true
    }

    /** Ends a recording where it is, or cancels an arm. The lane keeps what was recorded so far. */
    fun stopRecording(lane: Lane) {
        if (lane.recording || lane.armed) lane.stopRequested = true
    }

    /** Whether any lane is recording or waiting to. */
    val isRecording: Boolean get() = lanes.any { it.recording || it.armed }

    /** A playhead position the UI has asked for, applied by the audio thread at the next block; -1 for none. */
    @Volatile
    private var seekRequest = -1

    /**
     * Moves the playhead to [frame] of the loop: a click on a waveform. Applied at the top of the
     * next block, where the playhead is read, so a block in flight is never torn; set directly as
     * well, for the panel to show while no stream is running. Refused during a recording, whose
     * lane is being written at the playhead and would be torn by it. Nothing to seek in without a
     * loop.
     */
    fun seek(frame: Int) {
        val length = loopLength
        if (length == 0 || isRecording) return
        val target = frame.coerceIn(0, length - 1)
        seekRequest = target
        position = target
    }

    /** Empties [lane]. Frees the loop length when it was the last lane with anything in it. */
    fun clear(lane: Lane) {
        lane.stopRequested = lane.recording || lane.armed
        lane.length = 0
        lane.source = null
        lane.recordedFrames = 0
        if (lanes.none { it.length > 0 || it.recording }) loopLength = 0
    }

    /**
     * Puts [samples] into [lane], already at this looper's rate, keeping at most [capacity] frames.
     * Sets the loop length when there was none. Returns how many frames were kept.
     */
    fun load(lane: Lane, samples: FloatArray, count: Int, source: String): Int {
        if (capacity == 0) prepare(sampleRate)
        val kept = minOf(count, capacity)
        // Length first to zero, so a block that reads the lane while it is copied sees an empty
        // lane rather than half of two recordings.
        lane.length = 0
        System.arraycopy(samples, 0, lane.buffer, 0, kept)
        lane.length = kept
        lane.recordedFrames = kept
        lane.source = source
        if (loopLength == 0 && kept > 0) loopLength = kept
        return kept
    }

    /**
     * Removes the lane at [index] from the shown lanes, shifting the ones below it up. Refused while
     * anything is recording, since the copy would race the lane being written.
     */
    fun removeLane(index: Int): Boolean {
        if (index <= 0 || index >= laneCount || isRecording) return false
        // The lanes are a fixed pool, so removal is a copy up and an empty lane at the bottom. The
        // copy is of settings and audio alike; the audio thread sees each lane change at a block
        // boundary and nothing it reads can be half of two lanes.
        for (i in index until laneCount - 1) copyLane(from = lanes[i + 1], to = lanes[i])
        clear(lanes[laneCount - 1])
        lanes[laneCount - 1].active = true
        lanes[laneCount - 1].order = LaneOrder.AFTER
        lanes[laneCount - 1].level.value = lanes[laneCount - 1].level.default
        laneCount--
        return true
    }

    fun addLane(): Lane? {
        if (laneCount >= MAX_LANES) return null
        val lane = lanes[laneCount]
        laneCount++
        return lane
    }

    private fun copyLane(from: Lane, to: Lane) {
        to.length = 0
        System.arraycopy(from.buffer, 0, to.buffer, 0, from.length)
        to.length = from.length
        to.active = from.active
        to.order = from.order
        to.level.value = from.level.value
        to.source = from.source
        to.recordedFrames = from.recordedFrames
        to.armed = false
        to.recording = false
    }

    // ------------------------------------------------------------- audio side

    /**
     * Opens a block of [frames]: fixes the playhead, finds the wrap, and starts any armed lane.
     * Must be the first of the three calls in a block.
     */
    fun begin(frames: Int) {
        val length = loopLength
        var sounding = false
        for (lane in lanes) {
            lane.startAt = -1
            if (lane.gain != 0f) sounding = true
            if (lane.stopRequested) {
                lane.stopRequested = false
                if (lane.recording) endRecording(lane, lane.recordedFrames)
                lane.armed = false
            }
        }
        val requested = seekRequest
        if (requested >= 0) {
            seekRequest = -1
            if (length > 0 && !isRecording) position = requested.coerceIn(0, length - 1)
        }
        val live = enabled && playing
        running = live || sounding
        if (!live) {
            blockStart = position
            wrapAt = -1
            return
        }
        if (length == 0) {
            // No loop yet: an armed lane starts now, from the top.
            for (lane in lanes) {
                if (lane.armed) {
                    position = 0
                    lane.armed = false
                    lane.recording = true
                    lane.recordedFrames = 0
                    lane.length = 0
                    lane.startAt = 0
                    break
                }
            }
            blockStart = position
            wrapAt = -1
            return
        }
        blockStart = position
        // `>=`, not `>`: a loop that is a whole number of blocks long wraps exactly on a block
        // boundary, and that wrap is still a top of the loop. The armed lane then starts at
        // `frames`, which is the first sample of the next block.
        wrapAt = if (blockStart + frames >= length) length - blockStart else -1
        if (wrapAt >= 0) {
            for (lane in lanes) {
                if (lane.armed && !lane.recording) {
                    lane.armed = false
                    lane.recording = true
                    lane.recordedFrames = 0
                    lane.length = 0
                    lane.startAt = wrapAt
                }
            }
        }
    }

    /** Adds the lanes that play through the boards into [buffer], which is about to enter them. */
    fun mixThrough(buffer: FloatArray, frames: Int) {
        if (!running) return
        mix(buffer, frames, LaneOrder.THROUGH)
    }

    /**
     * Records into the lane that is recording from [buffer] — the finished sound — then adds the
     * lanes that play after the master, then moves the playhead. The last of the three calls.
     */
    fun finish(buffer: FloatArray, frames: Int) {
        if (!running) return
        if (enabled && playing) record(buffer, frames)
        mix(buffer, frames, LaneOrder.AFTER)
        advance(frames)
    }

    private fun record(buffer: FloatArray, frames: Int) {
        val lanes = lanes
        for (li in lanes.indices) {
            val lane = lanes[li]
            if (!lane.recording) continue
            val from = if (lane.startAt >= 0) lane.startAt else 0
            val limit = if (loopLength > 0) loopLength else capacity
            var at = lane.recordedFrames
            val dest = lane.buffer
            var i = from
            while (i < frames && at < limit) {
                dest[at++] = buffer[i++]
            }
            lane.recordedFrames = at
            if (at >= limit) endRecording(lane, at)
        }
    }

    /** Audio thread. Closes a recording at [frames] and, if this is the first lane, sets the loop. */
    private fun endRecording(lane: Lane, frames: Int) {
        lane.recording = false
        lane.length = frames
        lane.source = if (frames > 0) RECORDED else null
        if (frames > 0 && loopLength == 0) loopLength = frames
    }

    /** Adds this pass's lanes into [buffer], each at its gain, ramping any gain that is not where the knob says. */
    private fun mix(buffer: FloatArray, frames: Int, order: LaneOrder) {
        val length = loopLength
        val s = smoothing
        val lanes = lanes
        for (li in lanes.indices) {
            val lane = lanes[li]
            if (lane.order != order) continue
            val target =
                if (enabled && playing && lane.active && !lane.recording && lane.length > 0) lane.targetGain() else 0f
            var g = lane.gain
            if (abs(target - g) < SNAP) g = target
            if (g == 0f && target == 0f) {
                // Stored even on the way out: the snap to exactly zero is what [begin] reads to
                // decide the lane is silent and the looper can stop being run.
                lane.gain = 0f
                continue
            }
            val src = lane.buffer
            val laneLength = lane.length
            var index = blockStart
            if (g == target) {
                for (i in 0 until frames) {
                    if (index < laneLength) buffer[i] += g * src[index]
                    index++
                    if (length > 0 && index >= length) index = 0
                }
            } else {
                for (i in 0 until frames) {
                    g += (target - g) * s
                    if (index < laneLength) buffer[i] += g * src[index]
                    index++
                    if (length > 0 && index >= length) index = 0
                }
            }
            lane.gain = g
        }
    }

    private fun advance(frames: Int) {
        val length = loopLength
        var p = blockStart + frames
        if (length > 0) {
            while (p >= length) p -= length
        }
        position = p
    }

    /**
     * Runs the realtime paths on a throwaway looper so their code is compiled before the stream
     * starts, as `AudioEngine.warmUp` does for the chain. The real lanes are not touched.
     */
    fun warmUp(scratch: FloatArray, frames: Int, blocks: Int) {
        val dummy = Looper()
        // A "rate" of one block a second: a lane holds sixty blocks, so the first lane fills and
        // sets the loop a quarter of the way through a full pass and the rest of it plays back.
        dummy.prepare(frames.toFloat())
        dummy.enabled = true
        dummy.laneCount = 2
        dummy.lanes[1].order = LaneOrder.THROUGH
        dummy.arm(dummy.lanes[0])
        repeat(blocks) { block ->
            if (block == MAX_SECONDS + MAX_SECONDS / 2) dummy.arm(dummy.lanes[1])
            dummy.begin(frames)
            dummy.mixThrough(scratch, frames)
            dummy.finish(scratch, frames)
        }
        dummy.enabled = false
        repeat(8) {
            dummy.begin(frames)
            dummy.mixThrough(scratch, frames)
            dummy.finish(scratch, frames)
        }
    }

    companion object {
        const val MAX_LANES = 4

        /** A minute: four of these at 96 kHz is about 92 MB of float, inside the 512 MB heap. */
        const val MAX_SECONDS = 60

        /** What [Lane.source] says for audio that was recorded rather than loaded. */
        const val RECORDED = "Recorded"

        private const val SMOOTHING_SECONDS = 500f / 44_100f
        private const val SNAP = 1e-4f

        private fun smoothingFor(rate: Float) = 1f - exp(-1f / (rate * SMOOTHING_SECONDS))

        private fun scale(frames: Int, from: Float, to: Float): Int = (frames.toLong() * to / from).roundToInt()

        /**
         * Resamples [count] frames of [input] from one rate to another into [output], returning how
         * many were written. Offline, with the chain's own polyphase resampler; its group delay of
         * half its taps is shaved off the front so the lane's first beat stays where it was.
         */
        fun resample(input: FloatArray, count: Int, from: Float, to: Float, output: FloatArray): Int {
            if (count == 0) return 0
            val resampler = Resampler(from, to, maxInputFrames = count)
            val produced = FloatArray(resampler.maxOutputFrames + resampler.maxOutputFrames / 8 + 64)
            var written = resampler.process(input, count, produced)
            // Flush the delay line with silence, so the tail of the lane comes out too.
            val delay = resampler.latencyInputFrames.roundToInt() + 1
            val silence = FloatArray(delay)
            val tail = FloatArray((delay * to / from).roundToInt() + 64)
            val more = resampler.process(silence, delay, tail)
            val skip = (resampler.latencyInputFrames * to / from).roundToInt()
            val total = minOf(written + more, output.size + skip)
            var out = 0
            for (i in skip until total) {
                output[out++] = if (i < written) produced[i] else tail[i - written]
            }
            written = out
            return written
        }
    }
}
