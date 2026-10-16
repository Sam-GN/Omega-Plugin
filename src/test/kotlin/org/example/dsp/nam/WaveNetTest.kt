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

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How many weights an architecture must carry, derived from the shapes rather than from the code
 * under test.
 *
 * This is the load-bearing check. A `.nam` file is one flat array and nothing in it says where each
 * layer's weights begin — read the shapes differently from how they were written and the model still
 * runs, just as something other than the amplifier it was captured from. If the count computed here
 * matches what the unpacker consumes, the shapes agree.
 *
 * Deliberately a second, independent derivation rather than a call to `WaveNet.weightCount`: a test
 * that asks the code under test what the answer should be proves nothing.
 */
private fun expectedWeights(configs: List<LayerArrayConfig>, head: HeadConfig? = null): Int {
    var total = 0
    for (config in configs) {
        total += config.inputSize * config.channels // rechannel, no bias
        for (layer in config.dilations.indices) {
            val mid =
                if (config.gating[layer] == Gating.NONE) config.bottleneck else 2 * config.bottleneck
            total += config.kernelSizes[layer] * mid * config.channels + mid // conv + bias
            total += config.conditionSize * mid // condition mix-in, no bias
            if (config.layer1x1) total += config.bottleneck * config.channels + config.channels
            if (config.head1x1) {
                total += config.bottleneck * config.head1x1Channels + config.head1x1Channels
            }
        }
        val into = if (config.head1x1) config.head1x1Channels else config.bottleneck
        total += into * config.headChannels * config.headKernelSize
        if (config.headBias) total += config.headChannels
    }
    if (head != null) {
        var into = configs[configs.size - 1].headChannels
        for (i in head.kernelSizes.indices) {
            val out = if (i == head.kernelSizes.size - 1) head.outChannels else head.channels
            total += out * into * head.kernelSizes[i] + out
            into = head.channels
        }
    }
    return total + 1 // head scale, last
}

/** Builds a layer array config without spelling out every field at every call site. */
internal fun layerArray(
    dilations: IntArray,
    kernelSizes: IntArray = IntArray(dilations.size) { 1 },
    inputSize: Int = 1,
    conditionSize: Int = 1,
    channels: Int = 1,
    bottleneck: Int = channels,
    activation: String = "ReLU",
    gating: Int = Gating.NONE,
    layer1x1: Boolean = true,
    head1x1: Boolean = false,
    head1x1Channels: Int = 1,
    headChannels: Int = 1,
    headKernelSize: Int = 1,
    headBias: Boolean = true,
): LayerArrayConfig = LayerArrayConfig(
    inputSize = inputSize,
    conditionSize = conditionSize,
    channels = channels,
    bottleneck = bottleneck,
    kernelSizes = kernelSizes,
    dilations = dilations,
    primary = Array(dilations.size) { Activation.of(activation) },
    gating = IntArray(dilations.size) { gating },
    secondary = arrayOfNulls(dilations.size),
    layer1x1 = layer1x1,
    head1x1 = head1x1,
    head1x1Channels = head1x1Channels,
    headChannels = headChannels,
    headKernelSize = headKernelSize,
    headBias = headBias,
)

/** The shape a standard 0.5.x NAM capture uses. */
private fun standardConfigs(): List<LayerArrayConfig> {
    val dilations = IntArray(10) { 1 shl it }
    val kernels = IntArray(10) { 3 }
    return listOf(
        layerArray(dilations, kernels, inputSize = 1, channels = 16, headChannels = 8, headBias = false),
        layerArray(dilations, kernels, inputSize = 16, channels = 8, headChannels = 1, headBias = true),
    )
}

/** The shape of the 0.7.0 slimmable captures this reader was built against. */
private fun slimmableConfig(channels: Int): LayerArrayConfig {
    val dilations = intArrayOf(
        1, 3, 7, 17, 41, 101, 239,
        1, 3, 7, 17, 41, 101, 239,
        1, 13,
        1, 3, 7, 17, 41, 101, 239,
    )
    return layerArray(
        dilations = dilations,
        kernelSizes = IntArray(dilations.size) { if (it == 14 || it == 15) 15 else 6 },
        channels = channels,
        activation = "LeakyReLU",
        headChannels = 1,
        headKernelSize = 16,
        headBias = true,
    )
}

class WaveNetShapeTest {

    @Test
    fun `the unpacker consumes exactly the weights the architecture implies`() {
        val configs = standardConfigs()
        val count = expectedWeights(configs)
        // Succeeds only if every layer consumed precisely what its shape says it should.
        val network = WaveNet.build(configs, null, FloatArray(count) { 0.001f })
        assertTrue(network.operationsPerSample > 0)
    }

    /**
     * The two shapes the real 0.7.0 files carry, against counts taken from the files themselves.
     *
     * These are not derived here — they are what `Clean.nam` and its thirteen siblings actually
     * contain. Matching them is the strongest evidence available that the 0.7.0 layout is read the
     * way it was written, short of a reference implementation to diff against.
     */
    @Test
    fun `the real slimmable shapes need exactly the weights their files carry`() {
        assertEquals(1_871, expectedWeights(listOf(slimmableConfig(3))), "3-channel submodel")
        assertEquals(12_146, expectedWeights(listOf(slimmableConfig(8))), "8-channel submodel")

        // And the reader agrees with the derivation, which is what ties the two together.
        assertEquals(1_871, WaveNet.weightCount(listOf(slimmableConfig(3)), null))
        assertEquals(12_146, WaveNet.weightCount(listOf(slimmableConfig(8)), null))
    }

    @Test
    fun `too few weights is refused, not padded`() {
        val configs = standardConfigs()
        val short = FloatArray(expectedWeights(configs) - 1) { 0.001f }
        val failure = assertFailsWith<NamException> { WaveNet.build(configs, null, short) }
        assertTrue(failure.message!!.contains("needs"), failure.message!!)
    }

    @Test
    fun `too many weights is refused, not ignored`() {
        val configs = standardConfigs()
        val long = FloatArray(expectedWeights(configs) + 1) { 0.001f }
        val failure = assertFailsWith<NamException> { WaveNet.build(configs, null, long) }
        assertTrue(failure.message!!.contains("needs"), failure.message!!)
    }

    @Test
    fun `a head that does not feed the next layer is refused`() {
        // Layer 0 emits 8 head channels into a layer accumulating 4: a mis-read architecture, and
        // the kind that would otherwise run and quietly produce nonsense.
        val dilations = intArrayOf(1, 2)
        val kernels = intArrayOf(3, 3)
        val mismatched = listOf(
            layerArray(dilations, kernels, inputSize = 1, channels = 16, headChannels = 8, headBias = false),
            layerArray(dilations, kernels, inputSize = 16, channels = 4, headChannels = 1),
        )
        val failure = assertFailsWith<NamException> {
            WaveNet.build(mismatched, null, FloatArray(expectedWeights(mismatched)))
        }
        assertTrue(failure.message!!.contains("head"), failure.message!!)
    }

    @Test
    fun `a bottleneck with no 1x1 to widen it is refused`() {
        // Without the residual 1x1 the activation output is added straight onto the residual, so a
        // narrower bottleneck cannot work. Refused rather than run with a mismatched add.
        val config = layerArray(intArrayOf(1), channels = 4, bottleneck = 2, layer1x1 = false)
        val failure = assertFailsWith<NamException> {
            WaveNet.build(listOf(config), null, FloatArray(expectedWeights(listOf(config))))
        }
        assertTrue(failure.message!!.contains("bottleneck"), failure.message!!)
    }

    @Test
    fun `a standard capture costs what it is claimed to cost`() {
        val network = WaveNet.build(standardConfigs(), null, FloatArray(expectedWeights(standardConfigs())))
        // Roughly thirteen thousand multiply-adds a sample. This exists so the number cannot drift
        // silently — it is the whole reason this module may or may not run at a given buffer size.
        assertTrue(
            network.operationsPerSample in 10_000..16_000,
            "standard model cost: ${network.operationsPerSample} ops/sample",
        )
    }
}

class WaveNetMathTest {

    /**
     * The smallest network that still exercises the whole path, with weights chosen so the answer
     * can be worked out by hand.
     *
     * One channel, one layer, kernel of one. Re-channel passes the input through, the convolution
     * passes it through, the condition mix-in contributes nothing, the activation is a rectifier,
     * the residual contributes nothing, and the head scale doubles it — so the network is
     * `2 * relu(x)` and nothing else.
     */
    private fun handWorked(): WaveNet {
        val config = layerArray(intArrayOf(1))
        val weights = floatArrayOf(
            1f, // rechannel
            1f, // conv weight
            0f, // conv bias
            0f, // condition mix-in
            0f, // 1x1 weight
            0f, // 1x1 bias
            1f, // head re-channel weight
            0f, // head re-channel bias
            2f, // head scale
        )
        assertEquals(weights.size, expectedWeights(listOf(config)), "the hand-written weights fit")
        return WaveNet.build(listOf(config), null, weights)
    }

    @Test
    fun `produces the value worked out by hand`() {
        val network = handWorked()
        val input = floatArrayOf(0.5f, -0.5f, 1f, 0f, 0.25f)
        val expected = input.map { 2f * max(0f, it) }

        network.process(input, input.size)

        for (i in input.indices) assertEquals(expected[i], input[i], 1e-6f, "sample $i")
    }

    @Test
    fun `a dilated tap reaches back exactly as far as its dilation says`() {
        // Kernel of two at dilation four: the second tap is the current sample, the first is four
        // back. Weighting only the far tap turns the network into a four-sample delay.
        val config = layerArray(intArrayOf(4), intArrayOf(2))
        val weights = floatArrayOf(
            1f, // rechannel
            1f, 0f, // conv weights: far tap 1, current tap 0
            0f, // conv bias
            0f, // mix-in
            0f, 0f, // 1x1 + bias
            1f, 0f, // head re-channel + bias
            1f, // head scale
        )
        assertEquals(weights.size, expectedWeights(listOf(config)))
        val network = WaveNet.build(listOf(config), null, weights)

        val input = FloatArray(16)
        input[0] = 1f
        network.process(input, input.size)

        assertEquals(1f, input[4], 1e-6f, "the impulse emerged four samples later")
        assertEquals(0f, input[3], 1e-6f)
        assertEquals(0f, input[5], 1e-6f)
    }

    /**
     * The head re-channel is a convolution with a kernel in 0.7.0, not a 1x1 as it was in 0.5.x.
     *
     * That distinction is invisible on the models that use a kernel of one and load-bearing on the
     * ones that do not — every real 0.7.0 file seen so far uses sixteen taps. Getting it wrong reads
     * the right weights in the right order and still produces the wrong signal, so it is worth a
     * test of its own.
     */
    @Test
    fun `the head re-channel convolves over time`() {
        val config = layerArray(intArrayOf(1), headKernelSize = 2)
        val weights = floatArrayOf(
            1f, // rechannel
            1f, 0f, // conv weight, bias
            0f, // mix-in
            0f, 0f, // 1x1 + bias
            1f, 0f, // head re-channel: previous sample 1, current 0
            0f, // head re-channel bias
            1f, // head scale
        )
        assertEquals(weights.size, expectedWeights(listOf(config)))
        val network = WaveNet.build(listOf(config), null, weights)

        val input = FloatArray(8)
        input[0] = 1f
        network.process(input, input.size)

        assertEquals(1f, input[1], 1e-6f, "the head's own kernel delayed it by one sample")
        assertEquals(0f, input[0], 1e-6f)
        assertEquals(0f, input[2], 1e-6f)
    }

    @Test
    fun `a gated layer multiplies its two halves`() {
        // channels 1, bottleneck 1, gated: the convolution produces two channels, the first through
        // the primary activation and the second through the gate.
        val config = LayerArrayConfig(
            inputSize = 1,
            conditionSize = 1,
            channels = 1,
            bottleneck = 1,
            kernelSizes = intArrayOf(1),
            dilations = intArrayOf(1),
            primary = arrayOf(Activation.of("Identity")),
            gating = intArrayOf(Gating.MULTIPLY),
            secondary = arrayOf(Activation.of("Identity")),
            layer1x1 = true,
            head1x1 = false,
            head1x1Channels = 1,
            headChannels = 1,
            headKernelSize = 1,
            headBias = true,
        )
        val weights = floatArrayOf(
            1f, // rechannel
            1f, 2f, // conv weights: channel 0 passes x, channel 1 passes 2x
            0f, 0f, // conv biases
            0f, 0f, // mix-in (two outputs, no bias)
            0f, 0f, // 1x1 + bias
            1f, 0f, // head re-channel + bias
            1f, // head scale
        )
        assertEquals(weights.size, expectedWeights(listOf(config)))
        val network = WaveNet.build(listOf(config), null, weights)

        val input = floatArrayOf(0.5f, 0.25f)
        network.process(input, input.size)

        // x * 2x = 2x^2
        assertEquals(2f * 0.5f * 0.5f, input[0], 1e-6f)
        assertEquals(2f * 0.25f * 0.25f, input[1], 1e-6f)
    }

    @Test
    fun `reset clears the history so a second pass repeats the first`() {
        val network = handWorked()
        val first = floatArrayOf(0.5f, 0.25f, 0.125f)
        val second = first.copyOf()

        network.process(first, first.size)
        network.reset()
        network.process(second, second.size)

        for (i in first.indices) assertEquals(first[i], second[i], 1e-6f)
    }
}

class ActivationTest {

    @Test
    fun `reads a bare name and an object with parameters`() {
        assertEquals(0.5f, Activation.of("ReLU").apply(0.5f), 1e-6f)
        assertEquals(0f, Activation.of("ReLU").apply(-0.5f), 1e-6f)

        val leaky = Activation.of(mapOf("type" to "LeakyReLU", "negative_slope" to 0.25))
        assertEquals(0.5f, leaky.apply(0.5f), 1e-6f)
        assertEquals(-0.125f, leaky.apply(-0.5f), 1e-6f, "the file's slope, not the default")
    }

    @Test
    fun `refuses one it does not implement rather than substituting something`() {
        val failure = assertFailsWith<NamException> { Activation.of("Mish") }
        assertTrue(failure.message!!.contains("Mish"), failure.message!!)
    }

    @Test
    fun `refuses a per-channel PReLU instead of using a default slope`() {
        val failure = assertFailsWith<NamException> {
            Activation.of(mapOf("type" to "PReLU", "negative_slopes" to listOf(0.1, 0.2)))
        }
        assertTrue(failure.message!!.contains("per-channel"), failure.message!!)
    }
}
