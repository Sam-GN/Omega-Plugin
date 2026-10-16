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

import org.example.util.Json

/**
 * Reads a Neural Amp Modeler `.nam` file.
 *
 * A `.nam` is JSON: an architecture name, a config describing the layers, and one flat array of
 * weights. Two generations of the format are read here, because both are in circulation:
 *
 * - **0.5.x**, what the format looked like for years: one `kernel_size` for a whole layer array, a
 *   `head_size`, an activation named by a bare string, and `"gated": true` for the classic
 *   tanh-times-sigmoid layer.
 * - **0.7.0**, written by trainer 0.13 and later: `kernel_sizes` per layer, a `head` object with a
 *   kernel of its own, activations as objects so parameterised ones survive the round trip, a
 *   `bottleneck` that can be narrower than the residual, and optional 1x1s on both paths.
 *
 * Both are normalised into [LayerArrayConfig] so nothing downstream has to care which it was.
 *
 * On top of that, 0.7.0 can wrap several WaveNets in a **`SlimmableContainer`**: the same amp
 * captured at several widths, each with its own weights, so a host can trade CPU for accuracy
 * without loading a different file. Every model is returned; choosing between them is
 * [org.example.dsp.Nam]'s job.
 *
 * Takes the file's *text*, not a path, so the DSP layer stays free of file I/O.
 */
object NamFile {

    /**
     * One network, the largest size setting it is the right choice for, and how loud it measured.
     *
     * @param loudness the capture's own measured output level in dB, from `metadata.loudness`, or
     *   null when the file does not say. Captures vary over a range far wider than a trim control
     *   can cover — the models this was built against measure around -43 dB — which is exactly why
     *   NAM records the figure and why hosts are expected to normalise with it rather than leave it
     *   to the player. See [org.example.dsp.Nam.TARGET_LOUDNESS].
     */
    class Submodel(val maxValue: Float, val network: WaveNet, val loudness: Float?)

    /**
     * @param submodels ordered by ascending [Submodel.maxValue]; never empty. A plain WaveNet is a
     *   list of one, so callers need no special case.
     */
    class Loaded(val submodels: List<Submodel>, val sampleRate: Float, val name: String) {
        /** The full-size network: the last one, per the reference's own default. */
        val network: WaveNet get() = submodels[submodels.size - 1].network
    }

    /** The rate NAM captures at unless a file says otherwise. */
    const val DEFAULT_RATE = 48_000f

    fun parse(text: String, name: String): Loaded {
        val root = try {
            Json.parse(text)
        } catch (e: Json.MalformedException) {
            throw NamException("is not valid JSON — ${e.message}")
        } as? Map<*, *> ?: throw NamException("is not a NAM model (the file is not a JSON object)")

        val rate = (root["sample_rate"] as? Double)?.toFloat() ?: DEFAULT_RATE
        return Loaded(model(root, nested = false, fallbackLoudness = loudness(root)), rate, name)
    }

    private fun loudness(root: Map<*, *>): Float? =
        ((root["metadata"] as? Map<*, *>)?.get("loudness") as? Double)?.toFloat()

    /**
     * Reads one model dict — the file's root, or a container submodel, which have the same shape.
     *
     * @param nested true when this is already inside a container, which is how nesting is refused
     *   rather than recursed into for ever.
     */
    private fun model(root: Map<*, *>, nested: Boolean, fallbackLoudness: Float?): List<Submodel> {
        val architecture = root["architecture"] as? String
            ?: throw NamException("is not a NAM model (no \"architecture\")")
        val config = root["config"] as? Map<*, *>
            ?: throw NamException("has no \"config\" section")

        if (architecture.equals("SlimmableContainer", ignoreCase = true)) {
            if (nested) throw NamException("nests one container inside another, which is not a thing")
            return container(config, fallbackLoudness)
        }
        if (!architecture.equals("WaveNet", ignoreCase = true)) {
            throw NamException(
                "is a \"$architecture\" model. This build runs WaveNet models, in file format " +
                    "0.5.x or 0.7.0, including slimmable containers of them — which is what NAM's " +
                    "capture tools produce. LSTM and ConvNet captures are not implemented"
            )
        }
        return listOf(Submodel(1f, waveNet(config, root), loudness(root) ?: fallbackLoudness))
    }

    /**
     * A container of the same amp at several widths.
     *
     * The ordering rules are the reference's, checked here for the same reason it checks them: the
     * selection walk in [org.example.dsp.Nam] is only correct on a sorted list, and a container
     * whose last entry stops below 1.0 has no full-size model to fall back to.
     */
    private fun container(config: Map<*, *>, fallbackLoudness: Float?): List<Submodel> {
        val entries = config["submodels"] as? List<*>
            ?: throw NamException("is a container with no \"submodels\" array")
        if (entries.isEmpty()) throw NamException("is a container holding no models")

        val submodels = entries.mapIndexed { index, entry ->
            val map = entry as? Map<*, *>
                ?: throw NamException("has a malformed submodel at position $index")
            val maxValue = (map["max_value"] as? Double)?.toFloat()
                ?: throw NamException("has a submodel at position $index with no \"max_value\"")
            val inner = map["model"] as? Map<*, *>
                ?: throw NamException("has a submodel at position $index with no \"model\"")
            val nets = model(inner, nested = true, fallbackLoudness = fallbackLoudness)
            Submodel(maxValue, nets[0].network, nets[0].loudness)
        }

        for (i in 1 until submodels.size) {
            if (submodels[i].maxValue <= submodels[i - 1].maxValue) {
                throw NamException("has submodels that are not ordered by increasing size")
            }
        }
        if (submodels[submodels.size - 1].maxValue < 1f) {
            throw NamException("has no full-size submodel (its largest covers only up to ${submodels[submodels.size - 1].maxValue})")
        }
        return submodels
    }

    private fun waveNet(config: Map<*, *>, root: Map<*, *>): WaveNet {
        // The key has been spelled both ways across NAM versions.
        val rawLayers = (config["layers"] ?: config["layers_configs"]) as? List<*>
            ?: throw NamException("has no layer configuration")
        if (rawLayers.isEmpty()) throw NamException("has an empty layer configuration")

        val arrays = rawLayers.mapIndexed { index, entry ->
            layerArray(
                entry as? Map<*, *> ?: throw NamException("has a malformed layer at position $index"),
                index,
            )
        }
        val head = (config["head"] as? Map<*, *>)?.let { head(it) }

        val rawWeights = root["weights"] as? List<*>
            ?: throw NamException("carries no weights")
        val weights = FloatArray(rawWeights.size) { index ->
            (rawWeights[index] as? Double)?.toFloat()
                ?: throw NamException("has a non-numeric weight at position $index")
        }

        return WaveNet.build(arrays, head, weights)
    }

    private fun layerArray(layer: Map<*, *>, index: Int): LayerArrayConfig {
        val channels = layer.int("channels", index)
        val bottleneck = layer.intOrNull("bottleneck") ?: channels
        val dilations = layer.intArray("dilations", index)
        val count = dilations.size
        if (count == 0) throw NamException("has layer array $index with no dilations")

        val kernelSizes = when {
            layer["kernel_sizes"] != null -> layer.intArray("kernel_sizes", index)
            layer["kernel_size"] != null -> {
                val single = layer.int("kernel_size", index)
                IntArray(count) { single }
            }
            else -> throw NamException("has layer array $index with no kernel size")
        }
        if (kernelSizes.size != count) {
            throw NamException(
                "has layer array $index with ${kernelSizes.size} kernel sizes for $count layers"
            )
        }

        // 0.7.0 writes a head object per layer array; 0.5.x wrote a bare head_size and head_bias.
        val headMap = layer["head"] as? Map<*, *>
        val headChannels: Int
        val headKernelSize: Int
        val headBias: Boolean
        if (headMap != null) {
            headChannels = headMap.int("out_channels", index)
            headKernelSize = headMap.int("kernel_size", index)
            headBias = headMap["bias"] as? Boolean ?: false
        } else {
            headChannels = layer.int("head_size", index)
            headKernelSize = 1
            headBias = layer["head_bias"] as? Boolean ?: false
        }

        val primaries =
            perLayer(layer["activation"], count, index, "activation") { Activation.of(it) }

        val secondaryRaw = layer["secondary_activation"]
        val secondaries: List<Activation?> = if (secondaryRaw == null) {
            List(count) { null }
        } else {
            perLayer(secondaryRaw, count, index, "secondary_activation") {
                if (it == null) null else Activation.of(it)
            }
        }

        val gatedFlag = layer["gated"] as? Boolean ?: false
        val gatingRaw = layer["gating_mode"]
        val gating = if (gatingRaw == null) {
            IntArray(count) { if (gatedFlag) Gating.MULTIPLY else Gating.NONE }
        } else {
            val modes = perLayer(gatingRaw, count, index, "gating_mode") { it as? String }
            IntArray(count) { Gating.of(modes[it]) }
        }

        val layer1x1 = layer["layer1x1"] as? Map<*, *>
        val head1x1 = layer["head1x1"] as? Map<*, *>

        // Everything below is a shape this reader does not implement. Each is refused by name,
        // because a model quietly run with the wrong shape sounds like a bad capture rather than
        // like a bug, and that is a very long way to walk before finding the cause.
        layer.requireUngrouped("groups_input", index)
        layer.requireUngrouped("groups_input_mixin", index)
        layer1x1?.requireUngrouped("groups", index, "layer1x1")
        head1x1?.requireUngrouped("groups", index, "head1x1")
        for (film in FILM_KEYS) {
            if ((layer[film] as? Map<*, *>)?.get("active") == true) {
                throw NamException(
                    "uses FiLM conditioning ($film on layer array $index), which this build does " +
                        "not implement"
                )
            }
        }

        return LayerArrayConfig(
            inputSize = layer.int("input_size", index),
            conditionSize = layer.int("condition_size", index),
            channels = channels,
            bottleneck = bottleneck,
            kernelSizes = kernelSizes,
            dilations = dilations,
            primary = Array(count) { primaries[it] },
            gating = gating,
            secondary = Array(count) { secondaries[it] },
            // 0.5.x had no key and always had the 1x1.
            layer1x1 = layer1x1?.get("active") as? Boolean ?: true,
            head1x1 = head1x1?.get("active") as? Boolean ?: false,
            head1x1Channels = head1x1?.intOrNull("out_channels") ?: 1,
            headChannels = headChannels,
            headKernelSize = headKernelSize,
            headBias = headBias,
        )
    }

    private fun head(map: Map<*, *>): HeadConfig = HeadConfig(
        channels = map.int("channels", -1),
        outChannels = map.int("out_channels", -1),
        kernelSizes = map.intArray("kernel_sizes", -1),
        activation = Activation.of(
            map["activation"] ?: throw NamException("has a head with no activation")
        ),
    )

    /**
     * Reads a per-layer setting that may be written once for the whole array or once per layer.
     *
     * 0.7.0 writes lists; 0.5.x wrote scalars; the trainer's own config files write either.
     */
    private fun <T> perLayer(
        raw: Any?,
        count: Int,
        index: Int,
        what: String,
        read: (Any?) -> T,
    ): List<T> {
        if (raw == null) throw NamException("has layer array $index with no $what")
        if (raw !is List<*>) {
            val one = read(raw)
            return List(count) { one }
        }
        if (raw.size != count) {
            throw NamException(
                "has layer array $index with ${raw.size} $what entries for $count layers"
            )
        }
        return List(count) { read(raw[it]) }
    }

    private val FILM_KEYS = listOf(
        "conv_pre_film",
        "conv_post_film",
        "input_mixin_pre_film",
        "input_mixin_post_film",
        "activation_pre_film",
        "activation_post_film",
        "layer1x1_post_film",
        "head1x1_post_film",
    )

    private fun Map<*, *>.int(key: String, layer: Int): Int =
        (this[key] as? Double)?.toInt()
            ?: throw NamException(
                if (layer >= 0) "is missing \"$key\" on layer $layer" else "is missing \"$key\""
            )

    private fun Map<*, *>.intOrNull(key: String): Int? = (this[key] as? Double)?.toInt()

    private fun Map<*, *>.intArray(key: String, layer: Int): IntArray {
        val list = this[key] as? List<*>
            ?: throw NamException(
                if (layer >= 0) "is missing \"$key\" on layer $layer" else "is missing \"$key\""
            )
        return IntArray(list.size) { index ->
            (list[index] as? Double)?.toInt()
                ?: throw NamException("has a non-numeric \"$key\" entry on layer $layer")
        }
    }

    /** Grouped convolutions change the weight layout, so a group count above one must be refused. */
    private fun Map<*, *>.requireUngrouped(key: String, layer: Int, what: String = key) {
        val groups = (this[key] as? Double)?.toInt() ?: return
        if (groups != 1) {
            throw NamException(
                "uses grouped convolutions ($what = $groups on layer array $layer), which this " +
                    "build does not implement"
            )
        }
    }
}
