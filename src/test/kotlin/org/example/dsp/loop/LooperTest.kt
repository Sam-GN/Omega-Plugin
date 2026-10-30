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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The loop box, driven block by block the way the engine drives it.
 *
 * The numbers are worked out from the rules rather than read off the code: a lane recorded over
 * `n` blocks is `n × frames` long and plays back the exact samples it was given; the first lane
 * sets the loop and the second stops itself at that length; a through-lane arrives on the input
 * side and an after-lane on the output side, with the same playhead.
 */
class LooperTest {

    private val frames = 64

    /** A small looper: a "rate" of 640 Hz gives a lane of 38 400 frames, 600 blocks. */
    private fun looper(rate: Float = 640f) = Looper().apply {
        prepare(rate)
        enabled = true
    }

    /** One engine block: a copy of [input], through-lanes added, then "the chain", then finish. */
    private fun block(looper: Looper, input: Float, chain: (FloatArray) -> Unit = {}): FloatArray {
        val buffer = FloatArray(frames) { input }
        looper.begin(frames)
        looper.mixThrough(buffer, frames)
        chain(buffer)
        looper.finish(buffer, frames)
        return buffer
    }

    /** Ramps need time to settle; this runs enough silent blocks for a gain to arrive. */
    private fun settle(looper: Looper) = repeat(50) { block(looper, 0f) }

    @Test
    fun `the first recording starts at once, from frame zero, and sets the loop length`() {
        val looper = looper()
        val lane = looper.lanes[0]
        looper.arm(lane)
        // Each block carries its own number, so the recording can be checked sample by sample.
        repeat(5) { n -> block(looper, (n + 1) * 0.1f) }
        looper.stopRecording(lane)
        block(looper, 0f)

        assertFalse(lane.recording)
        assertEquals(5 * frames, lane.length, "five blocks recorded")
        assertEquals(5 * frames, looper.loopLength, "the first lane sets the loop")
        for (n in 0 until 5) assertEquals((n + 1) * 0.1f, lane.buffer[n * frames + 3], 1e-6f, "block $n")
        assertEquals(Looper.RECORDED, lane.source)
    }

    @Test
    fun `an after-lane plays back under the input at the knob's gain`() {
        val looper = looper()
        val lane = looper.lanes[0]
        lane.level.value = 100f
        looper.arm(lane)
        repeat(4) { n -> block(looper, if (n % 2 == 0) 0.5f else -0.5f) }
        looper.stopRecording(lane)
        settle(looper)

        // The playhead is somewhere in the loop; read where it is and predict the next block.
        val p = looper.position
        val expected = lane.buffer[p]
        val out = block(looper, 0.25f)
        assertEquals(0.25f + expected, out[0], 1e-5f, "input plus the lane at unity")
        lane.level.value = 50f
        settle(looper)
        val p2 = looper.position
        val out2 = block(looper, 0f)
        assertEquals(0.25f * lane.buffer[p2], out2[0], 1e-4f, "50 % is a gain of a quarter, the master's taper")
    }

    @Test
    fun `a through-lane is on the input side, an after-lane on the output side`() {
        val looper = looper()
        val lane = looper.lanes[0]
        lane.level.value = 100f
        looper.arm(lane)
        repeat(4) { block(looper, 0.5f) }
        looper.stopRecording(lane)
        settle(looper)

        // The "chain" halves whatever reaches it. An after-lane is added after that; a through-lane
        // before it, so it is halved too.
        lane.order = LaneOrder.AFTER
        settle(looper)
        val after = block(looper, 0f) { b -> for (i in b.indices) b[i] *= 0.5f }
        assertEquals(0.5f, after[0], 1e-5f, "after: the lane as recorded")

        lane.order = LaneOrder.THROUGH
        settle(looper)
        val through = block(looper, 0f) { b -> for (i in b.indices) b[i] *= 0.5f }
        assertEquals(0.25f, through[0], 1e-5f, "through: the lane went through the chain")
    }

    @Test
    fun `a second lane waits for the top of the loop and stops itself at the loop length`() {
        val looper = looper()
        val first = looper.lanes[0]
        val second = looper.lanes[1]
        looper.arm(first)
        repeat(6) { block(looper, 0.1f) }
        looper.stopRecording(first)
        block(looper, 0f)
        assertEquals(6 * frames, looper.loopLength)

        // Arm the second lane one block into the loop; it must not start until the loop wraps.
        block(looper, 0f)
        looper.arm(second)
        val before = looper.position
        assertTrue(before > 0 && before < looper.loopLength)
        var blocksUntilStart = 0
        while (!second.recording) {
            block(looper, 0.3f)
            blocksUntilStart++
            assertTrue(blocksUntilStart < 10, "it should have started at the wrap")
        }
        assertEquals(0, second.recordedFrames % frames, "started on a block boundary here, where the wrap falls on one")
        // Then it fills exactly one loop and stops without being asked.
        var guard = 0
        while (second.recording) {
            block(looper, 0.3f)
            assertTrue(++guard < 20)
        }
        assertEquals(looper.loopLength, second.length, "a later lane is exactly one loop long")
        assertEquals(0.3f, second.buffer[0], 1e-6f)
        assertEquals(0.3f, second.buffer[second.length - 1], 1e-6f)
    }

    @Test
    fun `recording starts sample-accurately at a wrap that falls inside a block`() {
        val looper = looper()
        val first = looper.lanes[0]
        looper.arm(first)
        // A loop of 2.5 blocks, so the wrap lands mid-block from then on.
        repeat(2) { block(looper, 0.1f) }
        // Stop after the third block begins: recordedFrames is 3 × 64; make the loop 160 instead by
        // loading a lane of that length as the first one.
        looper.stopRecording(first)
        block(looper, 0f)
        looper.clear(first)
        val oddLength = 160
        looper.load(first, FloatArray(oddLength) { 0.1f }, oddLength, "odd")
        assertEquals(oddLength, looper.loopLength)

        val second = looper.lanes[1]
        looper.arm(second)
        // Feed blocks whose sample i carries the value i, so the first recorded sample says where
        // in the block the recording began.
        var guard = 0
        while (!second.recording) {
            val buffer = FloatArray(frames) { it.toFloat() }
            looper.begin(frames)
            looper.mixThrough(buffer, frames)
            looper.finish(buffer, frames)
            assertTrue(++guard < 10)
        }
        // Recording began within that last block, at the sample where the playhead wrapped.
        assertTrue(second.recordedFrames in 1 until frames, "a partial block was captured: ${second.recordedFrames}")
        val startedAt = frames - second.recordedFrames
        assertEquals(startedAt.toFloat(), second.buffer[0], 0f, "first sample is the one at the wrap")
    }

    @Test
    fun `a lane that stops early keeps what it has and the loop length stands`() {
        val looper = looper()
        looper.arm(looper.lanes[0])
        repeat(8) { block(looper, 0.1f) }
        looper.stopRecording(looper.lanes[0])
        block(looper, 0f)
        val second = looper.lanes[1]
        looper.arm(second)
        var guard = 0
        while (!second.recording) {
            block(looper, 0.2f)
            assertTrue(++guard < 12, "the second lane never started")
        }
        repeat(2) { block(looper, 0.2f) }
        looper.stopRecording(second)
        block(looper, 0f)
        assertTrue(second.length in frames..(3 * frames), "two or three blocks: ${second.length}")
        assertTrue(second.length < looper.loopLength)
        assertEquals(8 * frames, looper.loopLength, "the loop is still the first lane's")
    }

    @Test
    fun `clearing the last lane frees the loop length, clearing one of several does not`() {
        val looper = looper()
        looper.arm(looper.lanes[0])
        repeat(3) { block(looper, 0.1f) }
        looper.stopRecording(looper.lanes[0])
        block(looper, 0f)
        looper.load(looper.lanes[1], FloatArray(100) { 0.2f }, 100, "file")
        looper.clear(looper.lanes[0])
        assertEquals(3 * frames, looper.loopLength, "the loaded lane is still there")
        looper.clear(looper.lanes[1])
        assertEquals(0, looper.loopLength, "nothing left: free again")
    }

    @Test
    fun `switched off, the lanes fade to silence and the playhead stops`() {
        val looper = looper()
        val lane = looper.lanes[0]
        lane.level.value = 100f
        looper.arm(lane)
        repeat(4) { block(looper, 0.5f) }
        looper.stopRecording(lane)
        settle(looper)
        looper.enabled = false
        // The first block after the switch still carries something — a fade, not a cut.
        val fading = block(looper, 0f)
        assertTrue(abs(fading[0]) > 0f && abs(fading[0]) <= 0.5f, "fading: ${fading[0]}")
        settle(looper)
        val silent = block(looper, 0f)
        assertEquals(0f, silent[0])
        val stopped = looper.position
        block(looper, 0f)
        assertEquals(stopped, looper.position, "the playhead holds while the looper is off")
        looper.enabled = true
        settle(looper)
        assertTrue(looper.position != stopped, "and moves again when it is on")
    }

    @Test
    fun `paused, the lanes fade to silence and the playhead holds, and play resumes from there`() {
        val looper = looper()
        val lane = looper.lanes[0]
        lane.level.value = 100f
        looper.arm(lane)
        repeat(4) { block(looper, 0.5f) }
        looper.stopRecording(lane)
        settle(looper)
        looper.playing = false
        val fading = block(looper, 0f)
        assertTrue(abs(fading[0]) > 0f && abs(fading[0]) <= 0.5f, "a fade, not a cut: ${fading[0]}")
        settle(looper)
        assertEquals(0f, block(looper, 0f)[0])
        val held = looper.position
        block(looper, 0f)
        assertEquals(held, looper.position, "the playhead holds while paused")
        looper.playing = true
        settle(looper)
        assertTrue(looper.position != held, "and moves again on play")
        assertEquals(0.5f, block(looper, 0f)[0], 1e-5f, "heard again")
    }

    @Test
    fun `a seek moves the playhead at the next block, clamped to the loop, and never during a recording`() {
        val looper = looper()
        looper.arm(looper.lanes[0])
        repeat(6) { block(looper, 0.1f) }
        looper.stopRecording(looper.lanes[0])
        block(looper, 0f)
        val length = looper.loopLength

        looper.seek(200)
        assertEquals(200, looper.position, "shown at once, for a panel with no stream running")
        block(looper, 0f)
        assertEquals(200 + frames, looper.position, "applied at the top of the next block, then advanced")

        looper.seek(length + 5_000)
        block(looper, 0f)
        assertEquals((length - 1 + frames) % length, looper.position, "clamped to the loop's last frame")

        looper.arm(looper.lanes[1])
        while (!looper.lanes[1].recording) block(looper, 0.2f)
        val before = looper.position
        looper.seek(0)
        block(looper, 0.2f)
        assertEquals((before + frames) % length, looper.position, "a seek during a recording is refused")
    }

    @Test
    fun `an inactive lane is silent, and a bound key could toggle it back`() {
        val looper = looper()
        val lane = looper.lanes[0]
        lane.level.value = 100f
        looper.arm(lane)
        repeat(4) { block(looper, 0.5f) }
        looper.stopRecording(lane)
        lane.active = false
        settle(looper)
        assertEquals(0f, block(looper, 0f)[0])
        lane.active = true
        settle(looper)
        assertEquals(0.5f, block(looper, 0f)[0], 1e-5f)
    }

    @Test
    fun `a rate change resamples the lanes and scales the loop`() {
        val looper = looper(rate = 8_000f)
        val lane = looper.lanes[0]
        // A 100 Hz tone recorded at 8 kHz, four blocks of it.
        looper.arm(lane)
        var n = 0
        repeat(40) {
            val buffer = FloatArray(frames) { i -> kotlin.math.sin(2 * Math.PI * 100 * (n + i) / 8_000.0).toFloat() }
            n += frames
            looper.begin(frames)
            looper.mixThrough(buffer, frames)
            looper.finish(buffer, frames)
        }
        looper.stopRecording(lane)
        block(looper, 0f)
        val lengthAt8k = lane.length
        looper.prepare(16_000f)
        assertEquals(lengthAt8k * 2, lane.length, 4, "twice the frames at twice the rate")
        assertEquals(lane.length, looper.loopLength, 4, "the loop scaled with it")
        // Still a 100 Hz tone: a zero crossing every 80 frames at 16 kHz.
        var crossings = 0
        for (i in 200 until lane.length - 200) if (lane.buffer[i] <= 0f && lane.buffer[i + 1] > 0f) crossings++
        val expected = (lane.length - 400) / 160.0
        assertTrue(abs(crossings - expected) <= 2, "$crossings rising crossings against $expected")
    }

    @Test
    fun `removing a lane shifts the ones below it up and empties the last`() {
        val looper = looper()
        looper.laneCount = 3
        looper.load(looper.lanes[0], FloatArray(10) { 0.1f }, 10, "a")
        looper.load(looper.lanes[1], FloatArray(20) { 0.2f }, 20, "b")
        looper.load(looper.lanes[2], FloatArray(30) { 0.3f }, 30, "c")
        looper.lanes[2].order = LaneOrder.THROUGH
        assertTrue(looper.removeLane(1))
        assertEquals(2, looper.laneCount)
        assertEquals(30, looper.lanes[1].length)
        assertEquals("c", looper.lanes[1].source)
        assertEquals(LaneOrder.THROUGH, looper.lanes[1].order)
        assertEquals(0, looper.lanes[2].length)
        assertFalse(looper.removeLane(0), "the first lane cannot be removed")
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int, message: String) {
        assertTrue(abs(expected - actual) <= tolerance, "$message: expected $expected, got $actual")
    }
}
