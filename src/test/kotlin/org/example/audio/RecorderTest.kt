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

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The recorder, checked against the WAV specification rather than against itself.
 *
 * Every size in a WAV header is derived here by hand, because the whole point of the file is that
 * something *else* opens it — and a header that is wrong in a way this code agrees with is exactly
 * the failure that survives to the moment you try to load the take.
 */
class RecorderTest {

    private fun recordInto(file: File, rate: Int, blocks: Int, frames: Int, fill: (Int) -> Float) {
        val recorder = Recorder.start(file, rate)
        val block = FloatArray(frames)
        var at = 0
        repeat(blocks) {
            for (i in 0 until frames) block[i] = fill(at + i)
            at += frames
            recorder.write(block, frames)
        }
        // Blocks until the writer has drained, so everything below is deterministic.
        recorder.stop()
        assertEquals(0L, recorder.droppedFrames, "nothing should have been dropped")
        assertEquals(null, recorder.failure, "no failure expected")
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    private fun tagAt(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    @Test
    fun `writes a canonical IEEE float wav header`() {
        val file = Files.createTempFile("omega-rec", ".wav").toFile()
        try {
            val frames = 512
            val blocks = 10
            recordInto(file, 48_000, blocks, frames) { 0.25f }

            val bytes = file.readBytes()
            val total = (blocks * frames).toLong()
            val data = total * 4 // 32-bit mono

            // Sizes worked out from the spec, not read back from the writer.
            assertEquals(58L + data, file.length(), "file length is header plus samples")

            assertEquals("RIFF", tagAt(bytes, 0))
            assertEquals((58 - 8 + data).toInt(), intAt(bytes, 4), "RIFF size counts everything after it")
            assertEquals("WAVE", tagAt(bytes, 8))

            assertEquals("fmt ", tagAt(bytes, 12))
            assertEquals(18, intAt(bytes, 16), "fmt is 18 bytes for a non-PCM format")
            assertEquals(3, shortAt(bytes, 20), "format 3 is IEEE float")
            assertEquals(1, shortAt(bytes, 22), "mono")
            assertEquals(48_000, intAt(bytes, 24))
            assertEquals(48_000 * 4, intAt(bytes, 28), "byte rate")
            assertEquals(4, shortAt(bytes, 32), "block align")
            assertEquals(32, shortAt(bytes, 34), "bits per sample")
            assertEquals(0, shortAt(bytes, 36), "cbSize")

            assertEquals("fact", tagAt(bytes, 38))
            assertEquals(4, intAt(bytes, 42))
            assertEquals(total.toInt(), intAt(bytes, 46), "fact carries the sample count")

            assertEquals("data", tagAt(bytes, 50))
            assertEquals(data.toInt(), intAt(bytes, 54))
        } finally {
            file.delete()
        }
    }

    /**
     * The samples come back exactly, **including ones above full scale**.
     *
     * That is the reason for float. The engine records before its output clamp, so a take driven
     * into the clip keeps its peaks and can be pulled back down later; if this ever quietly became
     * an integer format, that would silently stop being true.
     */
    @Test
    fun `samples survive the round trip, including peaks above full scale`() {
        val file = Files.createTempFile("omega-rec", ".wav").toFile()
        try {
            val frames = 256
            val blocks = 4
            val value = { i: Int -> if (i % 3 == 0) 1.5f else -0.125f * (i % 7) }
            recordInto(file, 44_100, blocks, frames) { value(it) }

            val bytes = file.readBytes()
            val samples = ByteBuffer.wrap(bytes, 58, bytes.size - 58).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until blocks * frames) {
                assertEquals(value(i), samples.float, 0f, "frame $i")
            }
        } finally {
            file.delete()
        }
    }

    /** A second implementation agrees it is a wav — the closest thing here to "Audacity opens it". */
    @Test
    fun `another decoder reads it back`() {
        val file = Files.createTempFile("omega-rec", ".wav").toFile()
        try {
            recordInto(file, 48_000, 8, 512) { 0.5f }

            AudioSystem.getAudioInputStream(file).use { stream ->
                val format = stream.format
                assertEquals(AudioFormat.Encoding.PCM_FLOAT, format.encoding)
                assertEquals(48_000f, format.sampleRate)
                assertEquals(1, format.channels)
                assertEquals(32, format.sampleSizeInBits)
                assertEquals(8L * 512, stream.frameLength)
            }
        } finally {
            file.delete()
        }
    }

    /**
     * The header is patched as it goes, so a take abandoned by a crash is still openable.
     *
     * Simulated by copying the file mid-recording — the copy is exactly what a crash would leave —
     * and checking it describes the audio it contains rather than claiming to be empty.
     */
    @Test
    fun `a file abandoned mid-recording still describes what it holds`() {
        val file = Files.createTempFile("omega-rec", ".wav").toFile()
        val rate = 8_000 // Low, so the five-second patch interval arrives quickly.
        try {
            val recorder = Recorder.start(file, rate)
            val block = FloatArray(1_000) { 0.1f }
            // Twelve seconds of audio: comfortably past at least one patch.
            repeat(96) {
                recorder.write(block, block.size)
                Thread.sleep(1)
            }
            Thread.sleep(200) // let the writer catch up and patch

            val abandoned = file.readBytes()
            recorder.stop()

            val declared = intAt(abandoned, 54)
            assertTrue(declared > 0, "an abandoned file must not claim to be empty")
            assertTrue(
                declared <= abandoned.size - 58,
                "it must not claim more audio than it holds: $declared vs ${abandoned.size - 58}",
            )
            assertEquals(declared / 4, intAt(abandoned, 46), "fact and data agree")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a full ring drops whole blocks and counts them`() {
        val file = Files.createTempFile("omega-rec", ".wav").toFile()
        try {
            val recorder = Recorder.start(file, 48_000)
            // Far more than the ring holds, faster than any writer could drain it.
            val block = FloatArray(4096)
            repeat(400) { recorder.write(block, block.size) }
            val dropped = recorder.droppedFrames
            recorder.stop()

            assertTrue(dropped > 0, "the ring should have overflowed in this test")
            // Whole blocks only: a partial write would shorten the file against the performance.
            assertEquals(0L, dropped % 4096, "dropped $dropped frames, not a whole number of blocks")
        } finally {
            file.delete()
        }
    }
}
