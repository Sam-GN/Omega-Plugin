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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes the output to a WAV file, without the audio thread ever touching a disk.
 *
 * **The callback does one thing here: copy into a ring.** No allocation, no lock, no syscall, no
 * chance of blocking — a disk that pauses for half a second is routine, and a callback that waited
 * for one would take the whole stream down. A separate ordinary-priority thread drains the ring to
 * the file, and the ring is large enough (about five seconds) that a stall has to be extraordinary
 * before anything is lost. If it ever is, [droppedFrames] counts it rather than leaving a silent
 * gap nobody knows about.
 *
 * **32-bit float, mono, at the device's rate.** Float because the whole chain is float and Audacity's
 * own internal format is 32-bit float, so this is a lossless handoff with no dithering — and because
 * float has headroom above 0 dBFS, so a take driven into the clip is still recoverable rather than
 * squared off. Mono because the engine is mono end to end and the second channel would be a copy.
 */
class Recorder private constructor(
    val file: File,
    private val sampleRate: Int,
    private val output: RandomAccessFile,
) {

    private val ring = FloatArray(RING_FRAMES)

    /** Frames handed over by the audio thread. Only it writes; everyone else reads. */
    @Volatile
    private var writeIndex = 0L

    /** Frames taken by the writer thread. Only it writes. */
    @Volatile
    private var readIndex = 0L

    @Volatile
    private var running = true

    /** Frames lost because the writer could not keep up. Should stay at zero. */
    @Volatile
    var droppedFrames = 0L
        private set

    /** Why writing stopped early, or null. Read by the UI after [stop]. */
    @Volatile
    var failure: String? = null
        private set

    /** Frames actually on disk. */
    private var stored = 0L

    val elapsedSeconds: Float get() = writeIndex.toFloat() / sampleRate

    private val writer = Thread({ drain() }, "omega-recorder").apply {
        isDaemon = true
        start()
    }

    /**
     * Called from the audio callback. Never blocks, never allocates, never throws.
     *
     * Dropping a whole block rather than a partial one keeps the file's timing honest: a short write
     * would silently shorten the recording relative to the performance.
     */
    fun write(samples: FloatArray, count: Int) {
        val at = writeIndex
        val free = RING_FRAMES - (at - readIndex).toInt()
        if (count > free) {
            droppedFrames += count
            return
        }
        var index = (at and MASK).toInt()
        var copied = 0
        while (copied < count) {
            val chunk = minOf(count - copied, RING_FRAMES - index)
            System.arraycopy(samples, copied, ring, index, chunk)
            index = (index + chunk) and MASK.toInt()
            copied += chunk
        }
        // Publishes everything written above, and only once all of it is in place.
        writeIndex = at + count
    }

    /** Finishes the file. Blocks until the writer has drained, so the file is complete on return. */
    fun stop() {
        running = false
        runCatching { writer.join(JOIN_TIMEOUT_MS) }
        runCatching {
            patchSizes()
            output.close()
        }.onFailure { failure = failure ?: (it.message ?: it.javaClass.simpleName) }
    }

    private fun drain() {
        val staging = FloatArray(CHUNK_FRAMES)
        val bytes = ByteArray(CHUNK_FRAMES * BYTES_PER_FRAME)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var sincePatch = 0L

        while (true) {
            val from = readIndex
            val available = (writeIndex - from).toInt()
            if (available <= 0) {
                // Only leave once there is nothing left, so stopping does not truncate the tail.
                if (!running) break
                runCatching { Thread.sleep(POLL_MS) }
                continue
            }

            val count = minOf(available, CHUNK_FRAMES)
            var index = (from and MASK).toInt()
            var copied = 0
            while (copied < count) {
                val chunk = minOf(count - copied, RING_FRAMES - index)
                System.arraycopy(ring, index, staging, copied, chunk)
                index = (index + chunk) and MASK.toInt()
                copied += chunk
            }

            buffer.clear()
            for (i in 0 until count) buffer.putFloat(staging[i])

            try {
                if ((stored + count) * BYTES_PER_FRAME > MAX_DATA_BYTES) {
                    // A WAV's sizes are unsigned 32-bit. Stop cleanly at the ceiling rather than
                    // writing a file whose header cannot describe it — about six hours at 48 kHz.
                    failure = "reached the 4 GB limit a WAV file can describe"
                    running = false
                    break
                }
                output.write(bytes, 0, count * BYTES_PER_FRAME)
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                running = false
                break
            }

            readIndex = from + count
            stored += count
            sincePatch += count

            // The header carries the length, so a file abandoned by a crash would claim to be
            // empty. Rewriting the three size fields every few seconds costs two seeks and twelve
            // bytes, and turns "lost the take" into "lost the last few seconds of it".
            if (sincePatch >= sampleRate * PATCH_SECONDS) {
                sincePatch = 0
                runCatching { patchSizes() }
            }
        }
    }

    /** Fills in the three lengths a WAV header cannot know until the end. */
    private fun patchSizes() {
        val dataBytes = stored * BYTES_PER_FRAME
        val resume = output.filePointer
        writeIntAt(RIFF_SIZE_OFFSET, (HEADER_BYTES - 8 + dataBytes).toInt())
        writeIntAt(FACT_SAMPLES_OFFSET, stored.toInt())
        writeIntAt(DATA_SIZE_OFFSET, dataBytes.toInt())
        output.seek(resume)
    }

    private fun writeIntAt(offset: Long, value: Int) {
        val four = ByteArray(4)
        four[0] = value.toByte()
        four[1] = (value shr 8).toByte()
        four[2] = (value shr 16).toByte()
        four[3] = (value shr 24).toByte()
        output.seek(offset)
        output.write(four)
    }

    companion object {

        /**
         * Opens a file and starts the writer.
         *
         * Throws if it cannot, because a recording that silently did not start is the worst outcome
         * available — you find out when you go looking for the take.
         */
        fun start(file: File, sampleRate: Int): Recorder {
            file.parentFile?.mkdirs()
            val output = RandomAccessFile(file, "rw")
            try {
                output.setLength(0)
                output.write(header(sampleRate))
            } catch (t: Throwable) {
                runCatching { output.close() }
                throw t
            }
            return Recorder(file, sampleRate, output)
        }

        /**
         * A canonical IEEE-float WAV header: `fmt ` of 18 bytes, then `fact`, then `data`.
         *
         * Format 3 with an 18-byte `fmt ` and a `fact` chunk is what libsndfile and Audacity write
         * for float WAV. A 16-byte `fmt ` and no `fact` is common and usually works, but "usually"
         * is the wrong target for a file whose whole purpose is to open somewhere else.
         */
        private fun header(sampleRate: Int): ByteArray {
            val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(tag("RIFF"))
            buffer.putInt(0) // patched
            buffer.put(tag("WAVE"))
            buffer.put(tag("fmt "))
            buffer.putInt(18)
            buffer.putShort(3) // WAVE_FORMAT_IEEE_FLOAT
            buffer.putShort(1) // channels
            buffer.putInt(sampleRate)
            buffer.putInt(sampleRate * BYTES_PER_FRAME)
            buffer.putShort(BYTES_PER_FRAME.toShort())
            buffer.putShort(32)
            buffer.putShort(0) // cbSize
            buffer.put(tag("fact"))
            buffer.putInt(4)
            buffer.putInt(0) // patched
            buffer.put(tag("data"))
            buffer.putInt(0) // patched
            return buffer.array()
        }

        private fun tag(text: String) = text.toByteArray(Charsets.US_ASCII)

        const val BYTES_PER_FRAME = 4
        const val HEADER_BYTES = 58

        private const val RIFF_SIZE_OFFSET = 4L
        private const val FACT_SAMPLES_OFFSET = 46L
        private const val DATA_SIZE_OFFSET = 54L

        /** Power of two, so the ring wraps with a mask. About 5.5 seconds at 48 kHz. */
        private const val RING_FRAMES = 1 shl 18
        private const val MASK = (RING_FRAMES - 1).toLong()

        private const val CHUNK_FRAMES = 8192
        private const val POLL_MS = 20L
        private const val PATCH_SECONDS = 5
        private const val JOIN_TIMEOUT_MS = 4000L
        private const val MAX_DATA_BYTES = 4_000_000_000L
    }
}
