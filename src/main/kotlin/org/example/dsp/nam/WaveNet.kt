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

/** Why a `.nam` file could not be used. The message completes "\"<file>\" …". */
class NamException(message: String) : Exception(message)

/**
 * One stack of dilated layers, as it appears in a `.nam` file's `config.layers`.
 *
 * This is the *normalised* form: [NamFile] flattens both the 0.5.x and the 0.7.0 spellings into it,
 * so nothing below this point has to know which version the file was written by. The fields that
 * only 0.7.0 can vary — per-layer kernel sizes, a bottleneck narrower than the residual, a head
 * re-channel with a kernel, an optional 1x1 on either path — are simply constant-valued when a
 * 0.5.x file is read.
 */
class LayerArrayConfig(
    val inputSize: Int,
    val conditionSize: Int,
    /** Width of the residual path. */
    val channels: Int,
    /** Width after the activation. Equal to [channels] in 0.5.x; may be narrower in 0.7.0. */
    val bottleneck: Int,
    val kernelSizes: IntArray,
    val dilations: IntArray,
    val primary: Array<Activation>,
    val gating: IntArray,
    val secondary: Array<Activation?>,
    val layer1x1: Boolean,
    val head1x1: Boolean,
    val head1x1Channels: Int,
    /** Output width of this array's head re-channel — the head width the *next* array must expect. */
    val headChannels: Int,
    val headKernelSize: Int,
    val headBias: Boolean,
) {
    /** Width of the head accumulator: what each layer contributes and the re-channel consumes. */
    val headWidth: Int get() = if (head1x1) head1x1Channels else bottleneck
}

/** The optional whole-network output head: repeated (activation, convolution) blocks. */
class HeadConfig(
    val channels: Int,
    val outChannels: Int,
    val kernelSizes: IntArray,
    val activation: Activation,
)

/**
 * WaveNet inference, a block at a time.
 *
 * This is Neural Amp Modeler's architecture reimplemented rather than wrapped — the reference is
 * C++ and there is no binding for it here. Until 2.3.2 it ran sample by sample, which is the form
 * that can be checked by reading, and took half of every period on a full capture: the inner loops
 * were sums of eight products, which the JIT may not vectorise because that reorders the additions.
 * Here each stage runs over up to [BLOCK] frames before the next, so every inner loop is over time,
 * adds in the same order per sample, and vectorises. **The output is the same to the bit**, held to
 * the old code, which lives on in the tests as `ReferenceWaveNet`.
 *
 * Signals are channel-major: channel `c`, frame `j` is at `c * BLOCK + j`.
 *
 * **Weight order is the whole game.** A `.nam` file is one flat array of floats, and getting the
 * unpacking order wrong does not fail — it produces a model that runs and sounds like nothing in
 * particular. The order is fixed by the reference's `export_weights`, which concatenates, per layer
 * array: the input re-channel, then each layer (dilated convolution and its bias, condition mixer,
 * residual 1x1, head 1x1), then the head re-channel; then the network head if there is one; then
 * `head_scale` as the very last float. Every convolution contributes `weight` flattened in PyTorch's
 * `(out, in, kernel)` order followed by `bias`.
 *
 * That claim is checked rather than trusted: [weightCount] predicts the total from the config alone,
 * and [build] refuses a file whose array is a different length. On the models this was written
 * against the prediction is exact — 1 871 and 12 146 — which is a much stronger signal than reading
 * the reference and believing it.
 *
 * **Nothing here allocates once built.** Every buffer is sized in the constructor and the per-sample
 * loops index arrays rather than iterating collections, because `for (x in list)` allocates an
 * iterator and at 48 kHz that is tens of thousands of objects a second — the same mistake that made
 * `Reverb` audible before it was fixed.
 */
class WaveNet(
    private val arrays: Array<LayerArray>,
    private val netHead: NetHead?,
    private val headScale: Float,
) {

    /** Multiply-adds per sample. The honest measure of what this model costs. */
    val operationsPerSample: Int =
        arrays.sumOf { it.operationsPerSample } + (netHead?.operationsPerSample ?: 0)

    /** The one-channel input to the first array, and the condition every layer mixes in. */
    private val single = FloatArray(BLOCK)

    /** Head output after [headScale], only needed when a network head follows it. */
    private val scaled = FloatArray(arrays[arrays.size - 1].headChannels * BLOCK)

    fun process(buffer: FloatArray, count: Int) {
        var at = 0
        while (at < count) {
            val n = minOf(BLOCK, count - at)
            processBlock(buffer, at, n)
            at += n
        }
    }

    private fun processBlock(buffer: FloatArray, at: Int, n: Int) {
        System.arraycopy(buffer, at, single, 0, n)
        val stack = arrays
        var signal = single
        var accumulated: FloatArray? = null
        for (a in stack.indices) {
            val array = stack[a]
            array.process(signal, single, accumulated, n)
            signal = array.signalOut
            accumulated = array.headOut
        }
        val out = accumulated!!
        val scale = headScale
        val head = netHead
        if (head == null) {
            for (j in 0 until n) buffer[at + j] = scale * out[j]
        } else {
            for (i in out.indices) scaled[i] = scale * out[i]
            System.arraycopy(head.apply(scaled, n), 0, buffer, at, n)
        }
    }

    fun reset() {
        for (i in arrays.indices) arrays[i].reset()
        netHead?.reset()
    }

    companion object {

        /** Frames a stage runs before the next. Long enough to vectorise, short enough for L1. */
        const val BLOCK = 128

        /**
         * How many weights an architecture requires, including the trailing `head_scale`.
         *
         * Worked out from the config rather than by counting what was read, so that a mismatch is a
         * statement about the file rather than a restatement of the reader's own assumptions.
         */
        fun weightCount(arrays: List<LayerArrayConfig>, head: HeadConfig?): Int {
            var total = 0
            for (config in arrays) {
                total += config.channels * config.inputSize
                for (i in config.dilations.indices) {
                    val mid =
                        if (config.gating[i] == Gating.NONE) config.bottleneck else 2 * config.bottleneck
                    total += mid * config.channels * config.kernelSizes[i] + mid
                    total += mid * config.conditionSize
                    if (config.layer1x1) total += config.channels * config.bottleneck + config.channels
                    if (config.head1x1) {
                        total += config.head1x1Channels * config.bottleneck + config.head1x1Channels
                    }
                }
                total += config.headChannels * config.headWidth * config.headKernelSize
                if (config.headBias) total += config.headChannels
            }
            if (head != null) {
                var into = arrays[arrays.size - 1].headChannels
                for (i in head.kernelSizes.indices) {
                    val out = if (i == head.kernelSizes.size - 1) head.outChannels else head.channels
                    total += out * into * head.kernelSizes[i] + out
                    into = head.channels
                }
            }
            return total + 1
        }

        /**
         * Builds the network and fills it from the flat weight array.
         *
         * Every shape relationship the reference relies on implicitly is asserted here, because each
         * one is a way for a misread file to run and sound wrong instead of failing.
         */
        fun build(
            configs: List<LayerArrayConfig>,
            head: HeadConfig?,
            weights: FloatArray,
        ): WaveNet {
            if (configs.isEmpty()) throw NamException("has no layers")

            for ((index, config) in configs.withIndex()) {
                if (config.conditionSize != 1) {
                    throw NamException(
                        "conditions layer $index on ${config.conditionSize} signals; this build " +
                            "drives a model from one mono input"
                    )
                }
                if (!config.layer1x1 && config.bottleneck != config.channels) {
                    throw NamException(
                        "has layer array $index with a ${config.bottleneck}-wide bottleneck feeding " +
                            "a ${config.channels}-wide residual and no 1x1 to re-channel it"
                    )
                }
                if (index == 0) {
                    if (config.inputSize != 1) {
                        throw NamException(
                            "takes ${config.inputSize} input channels; this build feeds it one"
                        )
                    }
                } else {
                    val previous = configs[index - 1]
                    if (previous.channels != config.inputSize) {
                        throw NamException(
                            "has layer array ${index - 1} producing ${previous.channels} channels " +
                                "into layer array $index which expects ${config.inputSize}"
                        )
                    }
                    if (previous.headChannels != config.headWidth) {
                        throw NamException(
                            "has layer array ${index - 1} producing ${previous.headChannels} head " +
                                "channels into layer array $index which accumulates ${config.headWidth}"
                        )
                    }
                }
            }

            val outputs = head?.outChannels ?: configs[configs.size - 1].headChannels
            if (outputs != 1) {
                throw NamException("produces $outputs output channels; this build plays back one")
            }

            val expected = weightCount(configs, head)
            if (weights.size != expected) {
                throw NamException(
                    "carries ${weights.size} weights where its own configuration needs $expected — " +
                        "the architecture was read differently from how it was written"
                )
            }

            val unpacker = Unpacker(weights)
            val arrays = Array(configs.size) { LayerArray(configs[it], unpacker) }
            val netHead = head?.let {
                NetHead(configs[configs.size - 1].headChannels, it, unpacker)
            }
            val headScale = unpacker.next()
            if (unpacker.remaining() != 0) {
                throw NamException(
                    "has ${unpacker.remaining()} unused weights — the architecture was read " +
                        "differently from how it was written"
                )
            }
            return WaveNet(arrays, netHead, headScale)
        }
    }
}

/** Walks the flat weight array, and says so clearly when it runs dry. */
class Unpacker(private val weights: FloatArray) {
    private var at = 0

    fun next(): Float {
        if (at >= weights.size) {
            throw NamException("ran out of weights — it declares more layers than it carries data for")
        }
        return weights[at++]
    }

    fun remaining(): Int = weights.size - at
}

/**
 * One stack of dilated layers, with its own input and head re-channelling.
 *
 * Two signals run through it. The **residual** carries the audio, is [LayerArrayConfig.channels]
 * wide, and each layer adds to it in place. The **head** is an accumulator that every layer adds its
 * activation to; it is re-channelled at the end of the array and handed to the next one, and the
 * last array's is what becomes the output.
 */
class LayerArray(config: LayerArrayConfig, unpacker: Unpacker) {

    val inputSize = config.inputSize
    val headChannels = config.headChannels

    private val headWidth = config.headWidth

    private val rechannel = Conv1x1(config.inputSize, config.channels, bias = false, unpacker = unpacker)
    private val layers = Array(config.dilations.size) { Layer(config, it, unpacker) }

    // Not a 1x1 in 0.7.0: this convolution has a kernel of its own (16 on the models this was
    // written against), so it needs history like any other dilated convolution.
    private val headRechannel = DilatedConv(
        headWidth, config.headChannels, config.headKernelSize, 1, config.headBias, unpacker,
    )

    /** Residual output, reused each block so the audio path allocates nothing. */
    val signalOut = FloatArray(config.channels * WaveNet.BLOCK)

    /** Head output, likewise. */
    val headOut = FloatArray(config.headChannels * WaveNet.BLOCK)

    private val accumulator = FloatArray(headWidth * WaveNet.BLOCK)

    val operationsPerSample: Int =
        rechannel.operations + layers.sumOf { it.operations } + headRechannel.operations

    fun process(input: FloatArray, condition: FloatArray, incoming: FloatArray?, n: Int) {
        rechannel.apply(input, signalOut, n)

        // The first array starts the head at zero; a later one continues the previous array's.
        if (incoming == null) Arrays.fill(accumulator, 0f)
        else System.arraycopy(incoming, 0, accumulator, 0, accumulator.size)

        val stack = layers
        for (i in stack.indices) stack[i].process(signalOut, condition, accumulator, n)

        headRechannel.apply(accumulator, headOut, n)
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
class Layer(config: LayerArrayConfig, index: Int, unpacker: Unpacker) {

    private val channels = config.channels
    private val bottleneck = config.bottleneck
    private val gating = config.gating[index]
    private val mid = if (gating == Gating.NONE) bottleneck else 2 * bottleneck
    private val primary = config.primary[index]

    // Only read when gating is on. 0.5.x left the gate half implicit, and it was always a sigmoid.
    private val secondary = config.secondary[index] ?: Activation.SIGMOID_GATE

    private val conv = DilatedConv(
        channels, mid, config.kernelSizes[index], config.dilations[index], true, unpacker,
    )
    private val mixin = Conv1x1(config.conditionSize, mid, bias = false, unpacker = unpacker)
    private val oneByOne =
        if (config.layer1x1) Conv1x1(bottleneck, channels, bias = true, unpacker = unpacker) else null
    private val headOneByOne =
        if (config.head1x1) {
            Conv1x1(bottleneck, config.head1x1Channels, bias = true, unpacker = unpacker)
        } else {
            null
        }

    private val z = FloatArray(mid * WaveNet.BLOCK)
    private val mixed = FloatArray(mid * WaveNet.BLOCK)
    private val post = FloatArray(bottleneck * WaveNet.BLOCK)
    private val residual = FloatArray(channels * WaveNet.BLOCK)
    private val headTerm = FloatArray(if (config.head1x1) config.head1x1Channels * WaveNet.BLOCK else 0)

    val operations: Int =
        conv.operations + mixin.operations + (oneByOne?.operations ?: 0) +
            (headOneByOne?.operations ?: 0)

    fun process(signal: FloatArray, condition: FloatArray, accumulator: FloatArray, n: Int) {
        conv.apply(signal, z, n)
        mixin.apply(condition, mixed, n)
        addInto(z, mixed, mid, n)

        for (b in 0 until bottleneck) {
            val at = b * WaveNet.BLOCK
            if (gating == Gating.NONE) {
                primary.apply(z, at, post, at, n)
                continue
            }
            val gate = (b + bottleneck) * WaveNet.BLOCK
            for (j in 0 until n) {
                val v = z[at + j]
                val g = secondary.apply(z[gate + j])
                post[at + j] = if (gating == Gating.MULTIPLY) primary.apply(v) * g
                else g * primary.apply(v) + (1f - g) * v
            }
        }

        val toHead = headOneByOne
        if (toHead == null) {
            addInto(accumulator, post, bottleneck, n)
        } else {
            toHead.apply(post, headTerm, n)
            addInto(accumulator, headTerm, headTerm.size / WaveNet.BLOCK, n)
        }

        val toResidual = oneByOne
        if (toResidual == null) {
            addInto(signal, post, channels, n)
        } else {
            toResidual.apply(post, residual, n)
            addInto(signal, residual, channels, n)
        }
    }

    private fun addInto(to: FloatArray, from: FloatArray, width: Int, n: Int) {
        for (c in 0 until width) Kernel.add(to, from, c * WaveNet.BLOCK, n)
    }

    fun reset() = conv.reset()
}

/**
 * The whole-network output head: `kernelSizes.size` blocks of activation then convolution.
 *
 * Absent from every model seen so far — `"head": null` — but cheap to support, and supporting it is
 * the difference between loading a file and telling its owner their model is unsupported.
 */
class NetHead(inChannels: Int, config: HeadConfig, unpacker: Unpacker) {

    private val activation = config.activation
    private val convs: Array<DilatedConv>
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
            DilatedConv(widths[i], widths[i + 1], config.kernelSizes[i], 1, true, unpacker)
        }
        activated = Array(count) { i -> FloatArray(widths[i] * WaveNet.BLOCK) }
        outputs = Array(count) { i -> FloatArray(widths[i + 1] * WaveNet.BLOCK) }
    }

    val operationsPerSample: Int = convs.sumOf { it.operations }

    fun apply(input: FloatArray, n: Int): FloatArray {
        var current = input
        for (i in convs.indices) {
            val scratch = activated[i]
            var at = 0
            while (at < scratch.size) {
                activation.apply(current, at, scratch, at, n)
                at += WaveNet.BLOCK
            }
            convs[i].apply(scratch, outputs[i], n)
            current = outputs[i]
        }
        return current
    }

    fun reset() {
        for (i in convs.indices) convs[i].reset()
    }
}

/** The loops everything above spends its time in: over frames, so the JIT vectorises them. */
internal object Kernel {

    /** `y += a * x` over [n] frames. */
    fun axpy(y: FloatArray, yAt: Int, x: FloatArray, xAt: Int, a: Float, n: Int) {
        for (j in 0 until n) y[yAt + j] += a * x[xAt + j]
    }

    /** Four of them in one pass, left to right, so the sums come out the same to the bit. */
    fun axpy4(
        y: FloatArray, yAt: Int, x: FloatArray, x0: Int, x1: Int, x2: Int, x3: Int,
        a0: Float, a1: Float, a2: Float, a3: Float, n: Int,
    ) {
        for (j in 0 until n) {
            y[yAt + j] = y[yAt + j] + a0 * x[x0 + j] + a1 * x[x1 + j] + a2 * x[x2 + j] + a3 * x[x3 + j]
        }
    }

    fun add(to: FloatArray, from: FloatArray, at: Int, n: Int) {
        for (j in 0 until n) to[at + j] += from[at + j]
    }

    /** One output channel: each of [width] inputs, [stride] apart from [xAt], by its weight. */
    fun row(
        y: FloatArray, yAt: Int, x: FloatArray, xAt: Int, stride: Int,
        weight: FloatArray, weightAt: Int, width: Int, n: Int,
    ) {
        var i = 0
        while (i + 4 <= width) {
            val x0 = xAt + i * stride
            val w = weightAt + i
            axpy4(
                y, yAt, x, x0, x0 + stride, x0 + 2 * stride, x0 + 3 * stride,
                weight[w], weight[w + 1], weight[w + 2], weight[w + 3], n,
            )
            i += 4
        }
        while (i < width) {
            axpy(y, yAt, x, xAt + i * stride, weight[weightAt + i], n)
            i++
        }
    }
}

/**
 * Dilated causal convolution over a window of past inputs.
 *
 * Taps sit at offsets `-(k-1)*d … -d, 0`. Each channel's past is contiguous, so a tap is a plain
 * slice of it; new input is appended, and when a channel's room is used up the part still needed is
 * moved back to the start. Weights arrive as `[out][in][k]` — PyTorch's flattening order — and are
 * stored as `[k][out][in]` so a row of inputs is contiguous.
 */
class DilatedConv(
    private val inChannels: Int,
    private val outChannels: Int,
    private val kernelSize: Int,
    private val dilation: Int,
    hasBias: Boolean,
    unpacker: Unpacker,
) {

    private val weight = FloatArray(kernelSize * outChannels * inChannels)
    private val bias = FloatArray(outChannels)

    private val past = dilation * (kernelSize - 1)

    // Room to append before moving: as much again, so moving costs no more than writing did.
    private val span = past + maxOf(past, 2 * WaveNet.BLOCK) + WaveNet.BLOCK
    private val history = FloatArray(span * inChannels)
    private var write = past

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

    fun apply(input: FloatArray, output: FloatArray, n: Int) {
        if (write + n > span) {
            for (c in 0 until inChannels) System.arraycopy(history, c * span + write - past, history, c * span, past)
            write = past
        }
        for (c in 0 until inChannels) System.arraycopy(input, c * WaveNet.BLOCK, history, c * span + write, n)

        for (out in 0 until outChannels) {
            val at = out * WaveNet.BLOCK
            Arrays.fill(output, at, at + n, bias[out])
            for (k in 0 until kernelSize) {
                // Tap k reads (kernelSize - 1 - k) * dilation samples back.
                val from = write - (kernelSize - 1 - k) * dilation
                Kernel.row(
                    output, at, history, from, span,
                    weight, (k * outChannels + out) * inChannels, inChannels, n,
                )
            }
        }
        write += n
    }

    fun reset() {
        history.fill(0f)
        write = past
    }
}

/** A plain matrix multiply with optional bias. Weights arrive row-major, `[out][in]`. */
class Conv1x1(
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

    fun apply(input: FloatArray, output: FloatArray, n: Int) {
        for (out in 0 until outChannels) {
            val at = out * WaveNet.BLOCK
            Arrays.fill(output, at, at + n, offset[out])
            Kernel.row(output, at, input, 0, WaveNet.BLOCK, weight, out * inChannels, inChannels, n)
        }
    }
}
