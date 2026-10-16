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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * One of the activation functions a `.nam` file can name, with its parameters bound.
 *
 * This is a class holding an `Int` tag and four floats rather than a sealed hierarchy of one-method
 * objects, and that is deliberate: it is called once per channel per layer per sample — a hundred
 * thousand times a second and more — so it must not allocate and must stay inlineable. The `when`
 * over an `Int` compiles to a table switch.
 *
 * File format 0.5.x writes a bare string (`"Tanh"`). 0.7.0 writes an object carrying the parameters
 * too (`{"type": "LeakyReLU", "negative_slope": 0.01}`), which is the only way a parameterised
 * activation can survive the round trip. Both are accepted; see [of].
 */
class Activation private constructor(
    private val kind: Int,
    private val p0: Float,
    private val p1: Float,
    private val p2: Float,
    private val p3: Float,
    /** As written in the file, so a message about it can quote it back. */
    val name: String,
) {

    fun apply(x: Float): Float = when (kind) {
        TANH -> tanh(x)
        RELU -> if (x > 0f) x else 0f
        LEAKY_RELU -> if (x >= 0f) x else p0 * x
        SIGMOID -> 1f / (1f + exp(-x))
        HARDTANH -> if (x < p0) p0 else if (x > p1) p1 else x
        LEAKY_HARDTANH -> when {
            x < p0 -> (x - p0) * p2 + p0
            x > p1 -> (x - p1) * p3 + p1
            else -> x
        }
        SOFTSIGN -> x / (1f + abs(x))
        SOFTSIGMOID -> 0.5f * (1f + x / (1f + abs(x)))
        else -> x
    }

    companion object {
        private const val TANH = 0
        private const val RELU = 1
        private const val LEAKY_RELU = 2
        private const val SIGMOID = 3
        private const val HARDTANH = 4
        private const val LEAKY_HARDTANH = 5
        private const val SOFTSIGN = 6
        private const val SOFTSIGMOID = 7
        private const val IDENTITY = 8

        /** The gate half of a 0.5.x `"gated": true` layer, which the old format left implicit. */
        val SIGMOID_GATE = Activation(SIGMOID, 0f, 0f, 0f, 0f, "Sigmoid")

        /**
         * Reads an activation from either spelling.
         *
         * @param spec a `String` (0.5.x) or a `Map` with `"type"` (0.7.0; `"name"` is also accepted
         *   because that is the key the trainer's own config files use).
         */
        fun of(spec: Any?): Activation {
            val map = spec as? Map<*, *>
            val name = when {
                spec is String -> spec
                map != null -> (map["type"] ?: map["name"]) as? String
                    ?: throw NamException("has an activation with no \"type\"")
                else -> throw NamException("has an activation that is neither a name nor an object")
            }
            fun number(key: String, fallback: Float): Float =
                (map?.get(key) as? Double)?.toFloat() ?: fallback

            return when (name.lowercase()) {
                "tanh", "fasttanh" -> Activation(TANH, 0f, 0f, 0f, 0f, name)
                "relu" -> Activation(RELU, 0f, 0f, 0f, 0f, name)
                // PReLU with a single learned slope exports exactly like LeakyReLU. With a slope per
                // channel it exports "negative_slopes" instead, which this does not read — so it is
                // refused below rather than silently run with a default slope.
                "leakyrelu", "prelu" -> {
                    if (map?.get("negative_slopes") != null) {
                        throw NamException(
                            "uses a per-channel PReLU, which this build does not implement"
                        )
                    }
                    Activation(LEAKY_RELU, number("negative_slope", 0.01f), 0f, 0f, 0f, name)
                }
                "sigmoid" -> Activation(SIGMOID, 0f, 0f, 0f, 0f, name)
                "hardtanh" -> Activation(
                    HARDTANH, number("min_val", -1f), number("max_val", 1f), 0f, 0f, name,
                )
                "leakyhardtanh" -> Activation(
                    LEAKY_HARDTANH,
                    number("min_val", -1f),
                    number("max_val", 1f),
                    number("min_slope", 0.01f),
                    number("max_slope", 0.01f),
                    name,
                )
                "softsign" -> Activation(SOFTSIGN, 0f, 0f, 0f, 0f, name)
                "softsigmoid" -> Activation(SOFTSIGMOID, 0f, 0f, 0f, 0f, name)
                "identity" -> Activation(IDENTITY, 0f, 0f, 0f, 0f, name)
                else -> throw NamException("uses an activation this build does not know: \"$name\"")
            }
        }
    }
}

/** How a layer's convolution output is turned into its activation output. */
object Gating {
    /** `out = primary(z)`, and the convolution produces one set of channels. */
    const val NONE = 0

    /** `out = primary(z1) * secondary(z2)`, the classic WaveNet gate; convolution is doubled. */
    const val MULTIPLY = 1

    /** `out = b * primary(z1) + (1 - b) * z1` where `b = secondary(z2)`; convolution is doubled. */
    const val BLEND = 2

    fun of(name: String?): Int = when (name?.lowercase()) {
        null, "none" -> NONE
        "gated", "pairmultiply" -> MULTIPLY
        "blended", "pairblend" -> BLEND
        else -> throw NamException("uses a gating mode this build does not know: \"$name\"")
    }
}
