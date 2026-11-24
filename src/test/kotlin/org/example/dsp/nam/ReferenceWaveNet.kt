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

import java.util.Arrays

/*
 * The network as it ran until 2.3.2: sample by sample, every sum written out. Kept because it is the
 * form that can be checked by reading, and [WaveNet] is held to its output bit for bit.
 */

class ReferenceWaveNet(
    private val arrays: Array<ReferenceLayerArray>,
    private val netHead: ReferenceNetHead?,
    private val headScale: Float,
) {

    /** Multiply-adds per sample. The honest measure of what this model costs. */
    val operationsPerSample: Int =
        arrays.sumOf { it.operationsPerSample } + (netHead?.operationsPerSample ?: 0)

    /** Scratch for the one-channel input to the first array. */
    private val single = FloatArray(arrays[0].inputSize)

    /** Head output after [headScale], only needed when a network head follows it. */
    private val scaled = FloatArray(arrays[arrays.size - 1].headChannels)

    fun process(buffer: FloatArray, count: Int) {
        val stack = arrays
        val head = netHead
        val scale = headScale
        for (i in 0 until count) {
            val x = buffer[i]
            single[0] = x

            var signal = single
            var accumulated: FloatArray? = null
            for (a in stack.indices) {
                val array = stack[a]
                array.process(signal, x, accumulated)
                signal = array.signalOut
                accumulated = array.headOut
            }

            val out = accumulated!!
            buffer[i] = if (head == null) {
                scale * out[0]
            } else {
                for (c in out.indices) scaled[c] = scale * out[c]
                head.apply(scaled)[0]
            }
        }
    }

    fun reset() {
        for (i in arrays.indices) arrays[i].reset()
        netHead?.reset()
    }

}

/**
 * One stack of dilated layers, with its own input and head re-channelling.
 *
 * Two signals run through it. The **residual** carries the audio, is [LayerArrayConfig.channels]
 * wide, and each layer adds to it in place. The **head** is an accumulator that every layer adds its
 * activation to; it is re-channelled at the end of the array and handed to the next one, and the
 * last array's is what becomes the output.
 */
class ReferenceLayerArray(config: LayerArrayConfig, unpacker: Unpacker) {

    val inputSize = config.inputSize
    val headChannels = config.headChannels

    private val headWidth = config.headWidth

    private val rechannel = ReferenceConv1x1(config.inputSize, config.channels, bias = false, unpacker = unpacker)
    private val layers = Array(config.dilations.size) { ReferenceLayer(config, it, unpacker) }

    // Not a 1x1 in 0.7.0: this convolution has a kernel of its own (16 on the models this was
    // written against), so it needs history like any other dilated convolution.
    private val headRechannel = ReferenceDilatedConv(
        headWidth, config.headChannels, config.headKernelSize, 1, config.headBias, unpacker,
    )

    /** Residual output, reused each sample so the audio path allocates nothing. */
    val signalOut = FloatArray(config.channels)

    /** Head output, likewise. */
    val headOut = FloatArray(config.headChannels)

    private val accumulator = FloatArray(headWidth)

    val operationsPerSample: Int =
        rechannel.operations + layers.sumOf { it.operations } + headRechannel.operations

    fun process(input: FloatArray, condition: Float, incoming: FloatArray?) {
        rechannel.apply(input, signalOut)

        // The first array starts the head at zero; a later one continues the previous array's.
        if (incoming == null) Arrays.fill(accumulator, 0f)
        else System.arraycopy(incoming, 0, accumulator, 0, headWidth)

        val stack = layers
        for (i in stack.indices) stack[i].process(signalOut, condition, accumulator)

        headRechannel.apply(accumulator, headOut)
    }

    fun reset() {
        for (i in layers.indices) layers[i].reset()
        headRechannel.reset()
        Arrays.fill(signalOut, 0f)
        Arrays.fill(headOut, 0f)
    }
}

/**
 * A dilated convolution, a condition mix-in, an activation, and a residual.
 *
 * The layer writes its result back into the residual in place, which is what lets the whole network
 * run without allocating.
 */
class ReferenceLayer(config: LayerArrayConfig, index: Int, unpacker: Unpacker) {

    private val channels = config.channels
    private val bottleneck = config.bottleneck
    private val gating = config.gating[index]
    private val mid = if (gating == Gating.NONE) bottleneck else 2 * bottleneck
    private val primary = config.primary[index]

    // Only read when gating is on. 0.5.x left the gate half implicit, and it was always a sigmoid.
    private val secondary = config.secondary[index] ?: Activation.SIGMOID_GATE

    private val conv = ReferenceDilatedConv(
        channels, mid, config.kernelSizes[index], config.dilations[index], true, unpacker,
    )
    private val mixin = ReferenceConv1x1(config.conditionSize, mid, bias = false, unpacker = unpacker)
    private val oneByOne =
        if (config.layer1x1) ReferenceConv1x1(bottleneck, channels, bias = true, unpacker = unpacker) else null
    private val headOneByOne =
        if (config.head1x1) {
            ReferenceConv1x1(bottleneck, config.head1x1Channels, bias = true, unpacker = unpacker)
        } else {
            null
        }

    private val z = FloatArray(mid)
    private val mixed = FloatArray(mid)
    private val post = FloatArray(bottleneck)
    private val conditionBuffer = FloatArray(config.conditionSize)
    private val residual = FloatArray(channels)
    private val headTerm = FloatArray(if (config.head1x1) config.head1x1Channels else 0)

    val operations: Int =
        conv.operations + mixin.operations + (oneByOne?.operations ?: 0) +
            (headOneByOne?.operations ?: 0)

    fun process(signal: FloatArray, conditionValue: Float, accumulator: FloatArray) {
        conv.apply(signal, z)

        conditionBuffer[0] = conditionValue
        mixin.apply(conditionBuffer, mixed)
        for (i in z.indices) z[i] += mixed[i]

        when (gating) {
            Gating.NONE -> for (i in 0 until bottleneck) post[i] = primary.apply(z[i])
            Gating.MULTIPLY -> for (i in 0 until bottleneck) {
                post[i] = primary.apply(z[i]) * secondary.apply(z[i + bottleneck])
            }
            else -> for (i in 0 until bottleneck) {
                val blend = secondary.apply(z[i + bottleneck])
                post[i] = blend * primary.apply(z[i]) + (1f - blend) * z[i]
            }
        }

        val toHead = headOneByOne
        if (toHead == null) {
            for (i in 0 until bottleneck) accumulator[i] += post[i]
        } else {
            toHead.apply(post, headTerm)
            for (i in headTerm.indices) accumulator[i] += headTerm[i]
        }

        val toResidual = oneByOne
        if (toResidual == null) {
            for (i in 0 until channels) signal[i] += post[i]
        } else {
            toResidual.apply(post, residual)
            for (i in 0 until channels) signal[i] += residual[i]
        }
    }

    fun reset() = conv.reset()
}

/**
 * The whole-network output head: `kernelSizes.size` blocks of activation then convolution.
 *
 * Absent from every model seen so far — `"head": null` — but cheap to support, and supporting it is
 * the difference between loading a file and telling its owner their model is unsupported.
 */
class ReferenceNetHead(inChannels: Int, config: HeadConfig, unpacker: Unpacker) {

    private val activation = config.activation
    private val convs: Array<ReferenceDilatedConv>
    private val activated: Array<FloatArray>
    private val outputs: Array<FloatArray>

    init {
        val count = config.kernelSizes.size
        if (count == 0) throw NamException("has a head with no layers")
        val widths = IntArray(count + 1)
        widths[0] = inChannels
        for (i in 0 until count) {
            widths[i + 1] = if (i == count - 1) config.outChannels else config.channels
        }
        convs = Array(count) { i ->
            ReferenceDilatedConv(widths[i], widths[i + 1], config.kernelSizes[i], 1, true, unpacker)
        }
        activated = Array(count) { i -> FloatArray(widths[i]) }
        outputs = Array(count) { i -> FloatArray(widths[i + 1]) }
    }

    val operationsPerSample: Int = convs.sumOf { it.operations }

    fun apply(input: FloatArray): FloatArray {
        var current = input
        for (i in convs.indices) {
            val scratch = activated[i]
            for (c in scratch.indices) scratch[c] = activation.apply(current[c])
            convs[i].apply(scratch, outputs[i])
            current = outputs[i]
        }
        return current
    }

    fun reset() {
        for (i in convs.indices) convs[i].reset()
    }
}

/**
 * Dilated causal convolution over a ring of past inputs.
 *
 * Taps sit at offsets `-(k-1)*d … -d, 0`, so the ring only has to be as long as the furthest tap
 * plus one. Weights arrive as `[out][in][k]` — PyTorch's flattening order — and are stored as
 * `[k][out][in]` so the innermost loop walks memory in order.
 */
class ReferenceDilatedConv(
    private val inChannels: Int,
    private val outChannels: Int,
    private val kernelSize: Int,
    private val dilation: Int,
    hasBias: Boolean,
    unpacker: Unpacker,
) {

    private val weight = FloatArray(kernelSize * outChannels * inChannels)
    private val bias = FloatArray(outChannels)

    private val historyLength = dilation * (kernelSize - 1) + 1
    private val history = FloatArray(historyLength * inChannels)
    private var write = 0

    val operations: Int = kernelSize * outChannels * inChannels

    init {
        for (out in 0 until outChannels) {
            for (into in 0 until inChannels) {
                for (k in 0 until kernelSize) {
                    weight[(k * outChannels + out) * inChannels + into] = unpacker.next()
                }
            }
        }
        if (hasBias) for (out in 0 until outChannels) bias[out] = unpacker.next()
    }

    fun apply(input: FloatArray, output: FloatArray) {
        val base = write * inChannels
        for (c in 0 until inChannels) history[base + c] = input[c]

        for (out in 0 until outChannels) output[out] = bias[out]

        for (k in 0 until kernelSize) {
            // Tap k reads (kernelSize - 1 - k) * dilation samples back.
            var slot = write - (kernelSize - 1 - k) * dilation
            if (slot < 0) slot += historyLength
            val historyBase = slot * inChannels
            val weightBase = k * outChannels * inChannels
            for (out in 0 until outChannels) {
                var sum = output[out]
                val row = weightBase + out * inChannels
                for (into in 0 until inChannels) sum += weight[row + into] * history[historyBase + into]
                output[out] = sum
            }
        }

        write++
        if (write >= historyLength) write = 0
    }

    fun reset() {
        history.fill(0f)
        write = 0
    }
}

/** A plain matrix multiply with optional bias. Weights arrive row-major, `[out][in]`. */
class ReferenceConv1x1(
    private val inChannels: Int,
    private val outChannels: Int,
    bias: Boolean,
    unpacker: Unpacker,
) {

    private val weight = FloatArray(outChannels * inChannels)
    private val offset = FloatArray(outChannels)

    val operations: Int = outChannels * inChannels

    init {
        for (i in weight.indices) weight[i] = unpacker.next()
        if (bias) for (i in offset.indices) offset[i] = unpacker.next()
    }

    fun apply(input: FloatArray, output: FloatArray) {
        for (out in 0 until outChannels) {
            var sum = offset[out]
            val row = out * inChannels
            for (into in 0 until inChannels) sum += weight[row + into] * input[into]
            output[out] = sum
        }
    }
}

/** Built as [WaveNet.build] builds its own, without the checks: the tests' weights are already right. */
fun referenceNet(configs: List<LayerArrayConfig>, head: HeadConfig?, weights: FloatArray): ReferenceWaveNet {
    val unpacker = Unpacker(weights)
    val arrays = Array(configs.size) { ReferenceLayerArray(configs[it], unpacker) }
    val netHead = head?.let { ReferenceNetHead(configs.last().headChannels, it, unpacker) }
    return ReferenceWaveNet(arrays, netHead, unpacker.next())
}
