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
package org.example.dsp

import org.example.dsp.nam.NamFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The module around the network: which submodel runs, and at what level.
 *
 * Both are things a listener would notice and a shape test would not. The models here reduce to
 * `scale * relu(x)`, with a different scale per submodel, so which one is running can be read
 * straight off the output.
 */
class NamTest {

    private fun model(headScale: String) = """
        {"version":"0.7.0","architecture":"WaveNet","config":{"layers":[
          {"input_size":1,"condition_size":1,
           "head":{"out_channels":1,"kernel_size":1,"bias":true},
           "channels":1,"kernel_sizes":[1],"dilations":[1],
           "activation":[{"type":"ReLU"}],"bottleneck":1,
           "head1x1":{"active":false,"out_channels":1,"groups":1},
           "layer1x1":{"active":true,"groups":1},
           "groups_input":1,"groups_input_mixin":1,
           "gating_mode":["none"],"secondary_activation":[null],"slimmable":null}],
          "head":null,"head_scale":$headScale},
         "weights":[1,1,0,0,0,0,1,0,$headScale],"sample_rate":48000.0}
    """.trimIndent()

    /** Two widths: the small one scales by 1, the full one by 3. */
    private fun slimmable(loudness: String? = null) = """
        {"architecture":"SlimmableContainer","config":{"submodels":[
          {"max_value":0.5,"model":${model("1")}},
          {"max_value":1.0,"model":${model("3")}}]},
         "weights":[],"sample_rate":48000.0${if (loudness == null) "" else ""","metadata":{"loudness":$loudness}"""}}
    """.trimIndent()

    private fun nam(json: String): Nam {
        val module = Nam()
        module.prepare(48_000f, 64)
        module.load(NamFile.parse(json, "amp.nam"))
        return module
    }

    private fun render(module: Nam, value: Float): Float {
        val buffer = floatArrayOf(value)
        module.process(buffer, 1)
        return buffer[0]
    }

    @Test
    fun `passes audio through untouched when nothing is loaded`() {
        val module = Nam()
        module.prepare(48_000f, 64)
        assertEquals(0.5f, render(module, 0.5f), 1e-6f)
        assertEquals(0, module.submodelCount)
    }

    @Test
    fun `runs the full-size submodel by default`() {
        val module = nam(slimmable())
        assertEquals(2, module.submodelCount)
        assertEquals(1.5f, render(module, 0.5f), 1e-6f, "3 * relu(0.5)")
    }

    @Test
    fun `size picks the first submodel it fits inside, as the reference does`() {
        val module = nam(slimmable())

        // The rule is "the first whose max_value the setting falls below", so anything under 0.5
        // takes the small model and anything from 0.5 up takes the full one.
        module.size.value = 0.2f
        assertEquals(0.5f, render(module, 0.5f), 1e-6f, "1 * relu(0.5)")

        module.size.value = 0.5f
        assertEquals(1.5f, render(module, 0.5f), 1e-6f, "0.5 is not below 0.5, so: full size")

        module.size.value = 1f
        assertEquals(1.5f, render(module, 0.5f), 1e-6f)

        module.size.value = 0f
        assertEquals(0.5f, render(module, 0.5f), 1e-6f, "and back down again")
    }

    @Test
    fun `a single-model file ignores size entirely`() {
        val module = nam(model("2"))
        assertEquals(1, module.submodelCount)
        for (size in floatArrayOf(0f, 0.3f, 0.7f, 1f)) {
            module.size.value = size
            assertEquals(1f, render(module, 0.5f), 1e-6f, "at size $size")
        }
    }

    /**
     * Captures are not level-matched to anything, and the spread is far wider than a trim control.
     * Across the fourteen real models this was tested against it runs from -14.7 dB to -43.3 dB —
     * nearly thirty decibels — so without this, choosing a different amp would change the volume
     * more than choosing a different amp changes the tone.
     */
    @Test
    fun `normalises a capture to the target loudness`() {
        val module = nam(slimmable(loudness = "-38.0"))
        // -38 dB brought to -18 dB is twenty decibels of gain, which is exactly ten times.
        assertEquals(10f, module.normalization, 1e-4f)
        assertEquals(15f, render(module, 0.5f), 1e-4f, "3 * relu(0.5) * 10")
    }

    @Test
    fun `leaves a capture alone when the file does not say how loud it is`() {
        val module = nam(slimmable())
        assertEquals(1f, module.normalization, 1e-6f)
    }

    @Test
    fun `normalisation applies to the wet path only`() {
        val module = nam(slimmable(loudness = "-38.0"))
        module.mix.value = 0f
        // All dry: the input, at unity, regardless of how quiet the capture happened to be.
        assertEquals(0.5f, render(module, 0.5f), 1e-4f)
    }

    /**
     * A model whose output is its input delayed by four samples: kernel of two at dilation four,
     * weighted so only the far tap contributes. Anything left in the ring buffers shows up in the
     * first four samples, where the answer must be exactly zero.
     */
    private fun delayModel() = """
        {"version":"0.7.0","architecture":"WaveNet","config":{"layers":[
          {"input_size":1,"condition_size":1,
           "head":{"out_channels":1,"kernel_size":1,"bias":true},
           "channels":1,"kernel_sizes":[2],"dilations":[4],
           "activation":[{"type":"ReLU"}],"bottleneck":1,
           "head1x1":{"active":false,"out_channels":1,"groups":1},
           "layer1x1":{"active":true,"groups":1},
           "groups_input":1,"groups_input_mixin":1,
           "gating_mode":["none"],"secondary_activation":[null],"slimmable":null}],
          "head":null,"head_scale":1},
         "weights":[1,1,0,0,0,0,0,1,0,1],"sample_rate":48000.0}
    """.trimIndent()

    /**
     * Loading runs the network hard before publishing it, so that the JIT has compiled it before the
     * audio thread ever sees it — interpreted, it needs about fifteen cores' worth of CPU and misses
     * every deadline. That pass pushes noise through the model, and this is the check that none of
     * it is left behind: a model must still start from silence.
     */
    @Test
    fun `the warm-up pass leaves no residue in the model`() {
        val module = nam(delayModel())
        val buffer = FloatArray(12)
        buffer[0] = 1f
        module.process(buffer, buffer.size)

        for (i in 0 until 4) assertEquals(0f, buffer[i], 1e-6f, "sample $i before the impulse")
        assertEquals(1f, buffer[4], 1e-6f, "the impulse, four samples late")
        for (i in 5 until buffer.size) assertEquals(0f, buffer[i], 1e-6f, "sample $i after it")
    }

    /**
     * Losing the capture must cost the capture and nothing else.
     *
     * When a preset names a `.nam` that has moved, the window drops the model and asks for it to be
     * pointed at again — but every knob the preset restored has to stay put, or reselecting the file
     * would mean setting the amp up from scratch as well as finding it.
     */
    @Test
    fun `clearing a model leaves the knobs where they were`() {
        val module = nam(slimmable())
        module.level.value = -6f
        module.mix.value = 0.4f
        module.size.value = 0.3f

        module.clear()

        assertEquals(-6f, module.level.value)
        assertEquals(0.4f, module.mix.value)
        assertEquals(0.3f, module.size.value)
    }

    @Test
    fun `clearing a model returns it to passing audio through`() {
        val module = nam(slimmable(loudness = "-38.0"))
        module.clear()
        assertEquals(0.5f, render(module, 0.5f), 1e-6f)
        assertEquals(1f, module.normalization, 1e-6f)
        assertTrue(module.modelName == null)
    }
}
