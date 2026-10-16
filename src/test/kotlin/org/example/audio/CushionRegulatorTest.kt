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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regulator is the only new piece of realtime logic that can be exercised without a device, so
 * it is worth pinning down: it must do nothing at all across the whole range of levels a healthy
 * device actually settles at, correct in the right direction near the ends of the buffer, and never
 * correct fast enough to be audible.
 */
class CushionRegulatorTest {

    private val block = AudioConfig.BLOCK_FRAMES
    private val capacity = 7_168 // 160 ms at 44.1 kHz

    /** Past the warm-up, so the regulator is live. */
    private val warm = 200

    private fun feed(regulator: CushionRegulator, fill: Int, blocks: Int): Int {
        var totalFrames = 0
        val samples = FloatArray(block + 1)
        repeat(blocks) {
            for (i in 0 until block) samples[i] = i / block.toFloat()
            totalFrames += regulator.regulate(fill, samples, block, quietest = 10)
        }
        return totalFrames
    }

    /** Runs until a splice happens, returning the resulting frame count. */
    private fun spliceOnce(regulator: CushionRegulator, fill: Int, samples: FloatArray): Int {
        repeat(20_000) {
            for (i in 0 until block) samples[i] = i.toFloat()
            val count = regulator.regulate(fill, samples, block, quietest = 10)
            if (count != block) return count
        }
        return block
    }

    @Test
    fun `does nothing during warm-up, when the reported level is meaningless`() {
        val regulator = CushionRegulator(capacity)
        val frames = feed(regulator, fill = 0, blocks = 60)
        assertEquals(60 * block, frames)
        assertEquals(0, regulator.corrections)
        assertTrue(!regulator.isArmed)
    }

    @Test
    fun `stays hands-off anywhere in the middle of the buffer`() {
        // 70 ms to 130 ms is the range real Windows pairings were measured settling at. None of it
        // may provoke a correction, or the regulator ends up fighting the driver forever.
        for (ms in intArrayOf(30, 50, 70, 90, 110, 125)) {
            val regulator = CushionRegulator(capacity)
            feed(regulator, fill = AudioConfig.framesForMs(ms), blocks = warm + 5_000)
            assertEquals(0, regulator.corrections, "corrected at a healthy $ms ms queue")
        }
    }

    @Test
    fun `ignores jitter that averages out`() {
        val regulator = CushionRegulator(capacity)
        val settled = AudioConfig.framesForMs(80)
        val samples = FloatArray(block + 1)
        repeat(8_000) { i ->
            val fill = settled + if (i % 2 == 0) 1_500 else -1_500
            regulator.regulate(fill, samples, block, quietest = 10)
        }
        assertEquals(0, regulator.corrections, "ordinary jitter must not trigger corrections")
    }

    @Test
    fun `drops frames when the queue approaches full`() {
        val regulator = CushionRegulator(capacity)
        val frames = feed(regulator, fill = capacity, blocks = 4_000)
        assertTrue(regulator.corrections > 0, "expected corrections, got none")
        assertEquals(4_000 * block - regulator.corrections, frames)
    }

    @Test
    fun `adds frames when the queue approaches empty`() {
        val regulator = CushionRegulator(capacity)
        val frames = feed(regulator, fill = 0, blocks = 4_000)
        assertTrue(regulator.corrections > 0, "expected corrections, got none")
        assertEquals(4_000 * block + regulator.corrections, frames)
    }

    @Test
    fun `corrects slowly enough to stay inaudible`() {
        val regulator = CushionRegulator(capacity)
        val blocks = 8_000
        feed(regulator, fill = 0, blocks = blocks)
        val seconds = blocks / (AudioConfig.SAMPLE_RATE / block)
        val perSecond = regulator.corrections / seconds
        assertTrue(perSecond <= 45f, "correcting $perSecond frames/s is too fast to hide")
    }

    @Test
    fun `a dropped frame removes exactly one sample and keeps the rest in order`() {
        val regulator = CushionRegulator(capacity)
        val samples = FloatArray(block + 1)
        assertEquals(block - 1, spliceOnce(regulator, capacity, samples), "expected a drop")
        // Index 10 was spliced out, so the sequence should read ... 9, 11, 12 ...
        assertEquals(9f, samples[9])
        assertEquals(11f, samples[10])
        assertEquals(12f, samples[11])
    }

    @Test
    fun `a duplicated frame repeats one sample and keeps the rest in order`() {
        val regulator = CushionRegulator(capacity)
        val samples = FloatArray(block + 1)
        assertEquals(block + 1, spliceOnce(regulator, 0, samples), "expected a duplicate")
        assertEquals(10f, samples[10])
        assertEquals(10f, samples[11])
        assertEquals(11f, samples[12])
    }

    @Test
    fun `disabled regulator never touches the block`() {
        val regulator = CushionRegulator(capacity, enabled = false)
        val frames = feed(regulator, fill = 0, blocks = 4_000)
        assertEquals(4_000 * block, frames)
        assertEquals(0, regulator.corrections)
    }

    @Test
    fun `splice index is clamped so a bad quietest cannot walk off the block`() {
        val regulator = CushionRegulator(capacity)
        val samples = FloatArray(block + 1)
        repeat(8_000) {
            regulator.regulate(0, samples, block, quietest = block + 99)
            regulator.regulate(0, samples, block, quietest = -5)
        }
        assertTrue(regulator.corrections > 0)
    }

    @Test
    fun `resettle disarms so the level is re-learned after an underrun`() {
        val regulator = CushionRegulator(capacity)
        feed(regulator, fill = AudioConfig.framesForMs(80), blocks = 500)
        assertTrue(regulator.isArmed)
        regulator.resettle()
        assertTrue(!regulator.isArmed)
        val frames = feed(regulator, fill = 0, blocks = 60)
        assertEquals(60 * block, frames, "must not splice while re-learning")
    }
}
