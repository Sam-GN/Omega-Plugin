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

import org.example.dsp.AudioProcessor
import org.example.dsp.EffectChain
import org.example.dsp.Parameter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two promises the engine makes every module, whatever the backend does (2.2.4):
 *
 * - no `process` call is longer than the block `prepare` was given, even when the backend calls
 *   `render` with more — a shared WASAPI output asked for 264 wrote periods of 441 and overflowed
 *   the NAM pedal's converter;
 * - no `prepare` or `reset` runs while the audio thread is inside the chain, even when the driver
 *   changes its format while the stream runs, as ASIO's panel and a JACK server do.
 *
 * Driven through a fake backend, so `AudioEngine.start` prepares the chain exactly as it does for a
 * device.
 */
class BlockSizeContractTest {

    @Test
    fun `a call longer than the prepared block reaches no module longer than it`() {
        val strict = Strict()
        val engine = AudioEngine(EffectChain(listOf(strict, Gainer(0.5f))))
        val fake = FakeBackend(bufferFrames = 264)
        engine.start(fake, fake.request)
        try {
            val input = FloatArray(970) { (it % 97) / 97f - 0.5f }
            val kept = input.copyOf()
            val output = FloatArray(970)
            for (frames in intArrayOf(441, 529, 970, 264, 1)) {
                engine.render(input, output, frames)
                for (i in 0 until frames) assertEquals(input[i] * 0.5f, output[i], 0f, "$frames frames, sample $i")
            }
            assertTrue(strict.longest in 1..264, "a module was handed ${strict.longest} frames against 264 prepared")
            assertTrue(input.contentEquals(kept), "the backend's input buffer was written to")
            assertNull(engine.faulted)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `the oversized path does not allocate`() {
        val engine = AudioEngine(EffectChain(listOf(Gainer(1.5f))))
        val fake = FakeBackend(bufferFrames = 264)
        engine.start(fake, fake.request)
        try {
            val input = FloatArray(970) { kotlin.math.sin(it * 0.05).toFloat() }
            val output = FloatArray(970)
            repeat(2_000) { engine.render(input, output, 970) }
            val before = allocatedBytes()
            if (before < 0) return
            repeat(20_000) { engine.render(input, output, 970) }
            val allocated = allocatedBytes() - before
            assertTrue(allocated < 64 * 1024, "render() allocated $allocated bytes splitting 20 000 oversized blocks")
        } finally {
            engine.stop()
        }
    }

    /**
     * A driver changing its format mid-stream, against a callback running flat out. [Strict] records
     * any `process` that overlaps its own `prepare` or `reset`; before 2.2.4 nothing stopped them
     * overlapping, and the format change also re-sized the block, so blocks made at the old size
     * arrived at a chain prepared for the new one.
     */
    @Test
    fun `a format change while running never prepares a module the callback is inside`() {
        val strict = Strict(prepareMillis = 3)
        val engine = AudioEngine(EffectChain(listOf(strict)))
        val fake = FakeBackend(bufferFrames = 256)
        engine.start(fake, fake.request)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val audio = Thread {
            val input = FloatArray(512) { 0.25f }
            val output = FloatArray(512)
            // The backend keeps calling at the size it was opened with, as an ASIO driver that has
            // asked for a reset does, whatever the chain is being prepared for meanwhile.
            while (!stop.get()) engine.render(input, output, 256)
        }
        try {
            audio.start()
            for (size in intArrayOf(64, 512, 128, 256, 32, 1024, 64, 256)) {
                fake.events!!.onFormatChanged(fake.format(size))
                Thread.sleep(5)
            }
        } finally {
            stop.set(true)
            audio.join(5_000)
            engine.stop()
        }
        assertEquals(0, strict.overlaps.get(), "process ran while prepare or reset was running")
        assertEquals(0, strict.oversized.get(), "a block reached the module longer than it was last prepared for")
        assertNull(engine.faulted)
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Fails the way the NAM converter did, but by counting: a block longer than the last `prepare`,
     * or a `process` that lands while `prepare` or `reset` is still running.
     */
    private class Strict(private val prepareMillis: Long = 0) : AudioProcessor {
        override val name = "Strict"
        override var enabled = true
        override val parameters = emptyList<Parameter>()

        @Volatile private var prepared = Int.MAX_VALUE
        @Volatile private var busy = false
        @Volatile var longest = 0
        val overlaps = AtomicInteger()
        val oversized = AtomicInteger()

        override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
            busy = true
            prepared = maxBlockFrames
            if (prepareMillis > 0) Thread.sleep(prepareMillis)
            busy = false
        }

        override fun process(buffer: FloatArray, count: Int) {
            if (busy) overlaps.incrementAndGet()
            if (count > prepared) oversized.incrementAndGet()
            if (count > longest) longest = count
        }

        override fun reset() {
            busy = true
            if (prepareMillis > 0) Thread.sleep(prepareMillis)
            busy = false
        }
    }

    private class Gainer(private val factor: Float) : AudioProcessor {
        override val name = "Gainer"
        override var enabled = true
        override val parameters = emptyList<Parameter>()
        override fun prepare(sampleRate: Float, maxBlockFrames: Int) {}
        override fun process(buffer: FloatArray, count: Int) {
            for (i in 0 until count) buffer[i] *= factor
        }
        override fun reset() {}
    }

    /** Opens a stream that does nothing but report its format and hold on to the engine's events. */
    private class FakeBackend(private val bufferFrames: Int) : AudioBackend {
        override val id = BackendId.JAVA_SOUND
        override val availability = Availability.Available
        private val device = BackendDevice(BackendId.JAVA_SOUND, "fake", "fake", 2, 2)
        val request = StreamRequest(device, 0, device, intArrayOf(0, 1), 44_100, bufferFrames)
        var events: StreamEvents? = null

        fun format(frames: Int) = StreamFormat(44_100, frames, frames, frames, 0)

        override fun devices() = listOf(device)
        override fun capabilities(device: BackendDevice, sampleRate: Int?) =
            DeviceCapabilities(listOf(44_100), listOf(bufferFrames), bufferFrames, Pairing.ANY_PAIR)

        override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream {
            this.events = events
            val opened = format(bufferFrames)
            return object : AudioStream {
                override val format = opened
                override val xruns = 0
                override val deadlineMisses = 0
                override val worstCallbackMicros = 0
                override fun start() {}
                override fun stop() {}
                override fun close() {}
            }
        }
    }

    private fun allocatedBytes(): Long = try {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val method = bean.javaClass.getMethod("getCurrentThreadAllocatedBytes")
        method.isAccessible = true
        method.invoke(bean) as Long
    } catch (_: Throwable) {
        -1L
    }
}
