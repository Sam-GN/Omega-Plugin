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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `AudioEngine.render` is now the entire audio path — the same body on every backend — so it is
 * worth pinning down without a device. Its lifecycle (open, warm up, start) still needs hardware and
 * is verified with `--selftest`.
 */
class AudioEngineTest {

    private fun engineWith(vararg effects: AudioProcessor) = AudioEngine(EffectChain(effects.toList()))

    /** Nothing here calls prepare(), so the engine's period is its default; that is fine for render. */
    private fun render(engine: AudioEngine, input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        engine.render(input, output, input.size)
        return output
    }

    /**
     * The recorder is fed from the callback, and fed **before** the output clamp.
     *
     * Both halves matter. That it is fed at all is the whole feature; that it happens before the
     * clamp is why the file is float — a take driven past full scale keeps its peaks and can be
     * pulled back down later, where capturing after the clamp would record the damage. The same
     * block therefore has to leave the engine clamped and reach the file unclamped, and this is the
     * only place both can be seen at once.
     */
    @Test
    fun `render feeds the recorder, before the output clamp`() {
        val file = java.nio.file.Files.createTempFile("omega-engine-rec", ".wav").toFile()
        try {
            val engine = engineWith()
            val recorder = Recorder.start(file, 48_000)
            engine.recorder = recorder

            val blocks = 8
            val frames = 64
            val input = FloatArray(frames) { 1.5f } // deliberately past full scale
            val output = FloatArray(frames)
            repeat(blocks) { engine.render(input, output, frames) }

            engine.recorder = null
            recorder.stop()

            assertEquals(0L, recorder.droppedFrames, "nothing should have been dropped")
            for (value in output) {
                assertEquals(1f, value, 1e-6f, "what leaves the engine is clamped")
            }

            val bytes = file.readBytes()
            assertEquals(58L + blocks * frames * 4, file.length(), "every block reached the file")
            val samples = java.nio.ByteBuffer.wrap(bytes, 58, bytes.size - 58)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(blocks * frames) {
                assertEquals(1.5f, samples.float, 0f, "what reaches the file is not clamped")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `silence in, silence out`() {
        val engine = engineWith()
        val output = render(engine, FloatArray(256))
        assertTrue(output.all { it == 0f }, "an empty chain turned silence into something")
        assertEquals(0f, engine.outputLevel, 0f)
    }

    @Test
    fun `an empty chain passes the signal through untouched`() {
        val engine = engineWith()
        val input = FloatArray(256) { kotlin.math.sin(it * 0.1).toFloat() * 0.5f }
        val output = render(engine, input)
        for (i in input.indices) assertEquals(input[i], output[i], 1e-6f, "sample $i changed")
    }

    @Test
    fun `the input buffer is not modified`() {
        // The backend reuses the same input array every callback and several of them hand out a
        // view onto driver memory. Writing back into it would corrupt the device's buffer.
        val engine = engineWith(Doubler())
        val input = FloatArray(64) { 0.25f }
        val copy = input.copyOf()
        render(engine, input)
        assertTrue(input.contentEquals(copy), "render() wrote into its input buffer")
    }

    @Test
    fun `output is clamped to the valid range`() {
        // Anything beyond [-1, 1] wraps to full-scale noise of the opposite sign once the backend
        // converts it to an integer word, which is the loudest possible bug.
        val engine = engineWith(Gainer(8f))
        val output = render(engine, FloatArray(128) { if (it % 2 == 0) 0.9f else -0.9f })
        assertTrue(output.all { it in -1f..1f }, "output escaped [-1, 1]: max ${output.maxOf { abs(it) }}")
        assertEquals(1f, output[0], 1e-6f)
        assertEquals(-1f, output[1], 1e-6f)
    }

    @Test
    fun `a chain that throws produces silence rather than noise, and says so`() {
        // An exception escaping into JNI is not well defined, so the callback swallows it. What
        // must not happen is the driver playing whatever was left in the buffer.
        val engine = engineWith(Exploder())
        val output = render(engine, FloatArray(64) { 0.8f })
        assertTrue(output.all { it == 0f }, "a failed chain let something through")
        assertNotNull(engine.faulted, "the fault was swallowed silently")
    }

    @Test
    fun `the engine survives a chain that throws every block`() {
        val engine = engineWith(Exploder())
        repeat(500) { render(engine, FloatArray(64) { 0.8f }) }
        assertNotNull(engine.faulted)
    }

    @Test
    fun `the meter attacks instantly`() {
        // A transient that arrives and is gone inside one block still has to move the meter, or a
        // guitar's most interesting moments never show.
        val engine = engineWith()
        val input = FloatArray(64)
        input[13] = 0.8f
        render(engine, input)
        assertEquals(0.8f, engine.outputLevel, 1e-3f)
    }

    @Test
    fun `the meter releases slowly and holds the peak longer still`() {
        // The whole reason for ballistics: at a 1.45 ms callback the UI polls one block in twenty,
        // so a per-block peak would simply miss most of them.
        val engine = engineWith()
        val loud = FloatArray(64)
        loud[0] = 0.9f
        render(engine, loud)
        val afterAttack = engine.outputLevel

        repeat(20) { render(engine, FloatArray(64)) }
        assertTrue(
            engine.outputLevel in 0.01f..afterAttack,
            "the meter should decay towards zero, not snap to it: ${engine.outputLevel}",
        )
        assertTrue(
            engine.outputPeakHold > engine.outputLevel,
            "the peak hold should outlast the level: hold ${engine.outputPeakHold}, level ${engine.outputLevel}",
        )
    }

    @Test
    fun `the meter eventually falls back to silence`() {
        val engine = engineWith()
        val loud = FloatArray(64)
        loud[0] = 1f
        render(engine, loud)
        repeat(20_000) { render(engine, FloatArray(64)) }
        assertTrue(engine.outputLevel < 0.001f, "the meter never came back down: ${engine.outputLevel}")
        assertTrue(engine.outputPeakHold < 0.001f, "the peak hold never came back down")
    }

    @Test
    fun `a short block is handled without touching the rest of the buffer`() {
        // Backends may deliver fewer frames than the buffer holds; the tail must be left alone
        // rather than processed as if it were signal.
        val engine = engineWith(Gainer(2f))
        val input = FloatArray(64) { 0.1f }
        val output = FloatArray(64) { -99f }
        engine.render(input, output, 8)
        for (i in 0 until 8) assertEquals(0.2f, output[i], 1e-6f)
        for (i in 8 until 64) assertEquals(-99f, output[i], 0f, "sample $i beyond the block was touched")
    }

    @Test
    fun `render does not allocate on the steady-state path`() {
        // Not a timing assertion — a bytes-allocated one. Allocation is what triggers a collection,
        // and a collection is what stops the callback thread.
        val engine = engineWith(Gainer(1.5f))
        val input = FloatArray(256) { kotlin.math.sin(it * 0.05).toFloat() }
        val output = FloatArray(256)
        repeat(2_000) { engine.render(input, output, 256) }   // warm up and let C2 settle

        val before = allocatedBytes()
        if (before < 0) return                                // not a HotSpot JVM; nothing to assert
        repeat(20_000) { engine.render(input, output, 256) }
        val allocated = allocatedBytes() - before
        assertTrue(
            allocated < 64 * 1024,
            "render() allocated $allocated bytes over 20 000 blocks; the callback path must not allocate",
        )
    }

    /** Bytes this thread has allocated, or -1 where the JVM will not say. */
    private fun allocatedBytes(): Long = try {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val method = bean.javaClass.getMethod("getCurrentThreadAllocatedBytes")
        method.isAccessible = true
        method.invoke(bean) as Long
    } catch (_: Throwable) {
        -1L
    }

    // ---- helpers ------------------------------------------------------------

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

    private class Doubler : AudioProcessor {
        override val name = "Doubler"
        override var enabled = true
        override val parameters = emptyList<Parameter>()
        override fun prepare(sampleRate: Float, maxBlockFrames: Int) {}
        override fun process(buffer: FloatArray, count: Int) {
            for (i in 0 until count) buffer[i] = buffer[i] * 2f
        }
        override fun reset() {}
    }

    private class Exploder : AudioProcessor {
        override val name = "Exploder"
        override var enabled = true
        override val parameters = emptyList<Parameter>()
        override fun prepare(sampleRate: Float, maxBlockFrames: Int) {}
        override fun process(buffer: FloatArray, count: Int): Unit =
            throw IllegalStateException("boom")
        override fun reset() {}
    }
}
