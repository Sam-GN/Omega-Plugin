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
package org.example.dsp.nam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two generations of the `.nam` format, and the container that wraps them.
 *
 * Every model here is small enough to work out by hand: one channel, one layer, a kernel of one,
 * and weights chosen so the whole network reduces to `scale * activation(x)`. That is the point —
 * the interesting failures in a format reader are not crashes but plausible wrong answers, and the
 * only way to catch those is to know what the answer should be before running it.
 */
class NamFileTest {

    /** 0.5.x: one kernel size for the array, a bare `head_size`, an activation named by string. */
    private fun legacy(architecture: String = "WaveNet", activation: String = "ReLU") = """
        {"architecture":"$architecture","config":{"layers":[
          {"input_size":1,"condition_size":1,"head_size":1,"channels":1,"kernel_size":1,
           "dilations":[1],"activation":"$activation","gated":false,"head_bias":true}]},
         "weights":[1,1,0,0,0,0,1,0,2]}
    """.trimIndent()

    /**
     * 0.7.0: per-layer `kernel_sizes`, a `head` object, activations as objects, and the pile of
     * feature flags the newer trainer writes whether or not they are in use.
     */
    private fun current(
        headScale: String = "2",
        activation: String = """{"type":"LeakyReLU","negative_slope":0.5}""",
        extra: String = "",
    ) = """
        {"version":"0.7.0","architecture":"WaveNet","config":{"layers":[
          {"input_size":1,"condition_size":1,
           "head":{"out_channels":1,"kernel_size":1,"bias":true},
           "channels":1,"kernel_sizes":[1],"dilations":[1],
           "activation":[$activation],"bottleneck":1,
           "head1x1":{"active":false,"out_channels":1,"groups":1},
           "layer1x1":{"active":true,"groups":1},
           "groups_input":1,"groups_input_mixin":1,
           "gating_mode":["none"],"secondary_activation":[null],"slimmable":null$extra}],
          "head":null,"head_scale":$headScale},
         "weights":[1,1,0,0,0,0,1,0,$headScale],
         "sample_rate":48000.0}
    """.trimIndent()

    private fun render(loaded: NamFile.Loaded, value: Float): Float {
        val buffer = floatArrayOf(value)
        loaded.network.process(buffer, 1)
        return buffer[0]
    }

    @Test
    fun `reads a 0_5_x model`() {
        val loaded = NamFile.parse(legacy(), "amp.nam")
        assertEquals("amp.nam", loaded.name)
        assertEquals(1, loaded.submodels.size, "a plain model is a container of one")
        assertEquals(1f, render(loaded, 0.5f), 1e-6f, "2 * relu(0.5)")
    }

    @Test
    fun `reads a 0_7_0 model, including the activation's own parameter`() {
        val loaded = NamFile.parse(current(), "amp.nam")
        assertEquals(48_000f, loaded.sampleRate)
        assertEquals(1f, render(loaded, 0.5f), 1e-6f, "2 * leaky(0.5)")

        // The negative side is what proves the slope came out of the file rather than a default:
        // 2 * (0.5 * -0.5) = -0.5, where a default 0.01 slope would give -0.01.
        val fresh = NamFile.parse(current(), "amp.nam")
        assertEquals(-0.5f, render(fresh, -0.5f), 1e-6f)
    }

    @Test
    fun `accepts either spelling of the layers key`() {
        val alternate = legacy().replace("\"layers\"", "\"layers_configs\"")
        assertTrue(NamFile.parse(alternate, "amp.nam").network.operationsPerSample > 0)
    }

    @Test
    fun `reads a slimmable container and keeps its models in order`() {
        val container = """
            {"version":"0.7.0","architecture":"SlimmableContainer","config":{"submodels":[
              {"max_value":0.5,"model":${current(headScale = "1")}},
              {"max_value":1.0,"model":${current(headScale = "3")}}]},
             "weights":[],"sample_rate":48000.0,"metadata":{"loudness":-30.0}}
        """.trimIndent()

        val loaded = NamFile.parse(container, "amp.nam")
        assertEquals(2, loaded.submodels.size)
        assertEquals(0.5f, loaded.submodels[0].maxValue)
        assertEquals(1f, loaded.submodels[1].maxValue)

        // `network` is the full-size one, which is the last: 3 * leaky(0.5).
        assertEquals(1.5f, render(loaded, 0.5f), 1e-6f)

        val small = floatArrayOf(0.5f)
        loaded.submodels[0].network.process(small, 1)
        assertEquals(0.5f, small[0], 1e-6f, "the smaller submodel, at its own scale")
    }

    @Test
    fun `a submodel inherits the container's loudness when it has none of its own`() {
        val container = """
            {"architecture":"SlimmableContainer","config":{"submodels":[
              {"max_value":1.0,"model":${current()}}]},
             "weights":[],"metadata":{"loudness":-33.5}}
        """.trimIndent()
        assertEquals(-33.5f, NamFile.parse(container, "amp.nam").submodels[0].loudness)
    }

    @Test
    fun `a model with no loudness says so rather than guessing one`() {
        assertNull(NamFile.parse(legacy(), "amp.nam").submodels[0].loudness)
    }

    @Test
    fun `refuses a container whose models are not ordered by size`() {
        val container = """
            {"architecture":"SlimmableContainer","config":{"submodels":[
              {"max_value":1.0,"model":${current()}},
              {"max_value":0.5,"model":${current()}}]},
             "weights":[]}
        """.trimIndent()
        val failure = assertFailsWith<NamException> { NamFile.parse(container, "amp.nam") }
        assertTrue(failure.message!!.contains("ordered"), failure.message!!)
    }

    @Test
    fun `refuses a container with no full-size model`() {
        val container = """
            {"architecture":"SlimmableContainer","config":{"submodels":[
              {"max_value":0.5,"model":${current()}}]},
             "weights":[]}
        """.trimIndent()
        val failure = assertFailsWith<NamException> { NamFile.parse(container, "amp.nam") }
        assertTrue(failure.message!!.contains("full-size"), failure.message!!)
    }

    @Test
    fun `refuses an architecture it cannot run, by name`() {
        val failure = assertFailsWith<NamException> { NamFile.parse(legacy("LSTM"), "amp.nam") }
        assertTrue(failure.message!!.contains("LSTM"), failure.message!!)
        // And says what it *can* run, because the message is the whole of the user's diagnosis.
        assertTrue(failure.message!!.contains("WaveNet"), failure.message!!)
    }

    /**
     * Shapes that would otherwise be read with the wrong layout.
     *
     * Grouped convolutions and FiLM both change how many weights there are and where they sit. A
     * reader that ignores the flag consumes the array at the wrong stride and produces a network
     * that runs perfectly happily and sounds like nothing in particular — so each is refused by
     * name instead.
     */
    @Test
    fun `refuses shapes it does not implement rather than misreading them`() {
        val grouped = assertFailsWith<NamException> {
            NamFile.parse(current(extra = ""). replace("\"groups_input\":1", "\"groups_input\":2"), "x.nam")
        }
        assertTrue(grouped.message!!.contains("grouped"), grouped.message!!)

        val film = assertFailsWith<NamException> {
            NamFile.parse(
                current(extra = ""","conv_pre_film":{"active":true,"shift":true,"groups":1}"""),
                "x.nam",
            )
        }
        assertTrue(film.message!!.contains("FiLM"), film.message!!)
    }

    @Test
    fun `refuses a weight array that does not match the configuration`() {
        val short = legacy().replace("[1,1,0,0,0,0,1,0,2]", "[1,1,0,0,0,0,1,0]")
        val failure = assertFailsWith<NamException> { NamFile.parse(short, "x.nam") }
        assertTrue(failure.message!!.contains("needs"), failure.message!!)
    }

    @Test
    fun `explains what is wrong rather than just failing`() {
        val notJson = assertFailsWith<NamException> { NamFile.parse("{ not json", "x.nam") }
        assertTrue(notJson.message!!.contains("not valid JSON"), notJson.message!!)

        val noArchitecture = assertFailsWith<NamException> { NamFile.parse("""{"weights":[1]}""", "x.nam") }
        assertTrue(noArchitecture.message!!.contains("architecture"), noArchitecture.message!!)

        val noConfig = assertFailsWith<NamException> {
            NamFile.parse("""{"architecture":"WaveNet","weights":[1]}""", "x.nam")
        }
        assertTrue(noConfig.message!!.contains("config"), noConfig.message!!)

        val noLayers = assertFailsWith<NamException> {
            NamFile.parse("""{"architecture":"WaveNet","config":{},"weights":[1]}""", "x.nam")
        }
        assertTrue(noLayers.message!!.contains("layer"), noLayers.message!!)
    }
}
