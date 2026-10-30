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

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One knob-backed value.
 *
 * [value] is written from the UI thread and read from the audio thread every block, hence volatile.
 * [skew] warps the knob travel: `value = min + range * position^skew`, so a skew above 1 gives finer
 * control near the bottom of the range (useful for delay time and drive, which are perceptually
 * logarithmic).
 */
class Parameter(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val skew: Float = 1f,
    /**
     * Options, when this parameter names a *choice* rather than measures a quantity — which module
     * an FX slot is, which shape an LFO draws.
     *
     * Still one `Float` underneath, and that is the point of doing it this way: a preset stores a
     * `Map<String, Float>`, so a choice expressed as an index needs no new storage, no new JSON
     * shape and no migration. The cost is that the number in the file is positional, so **options
     * may be appended but never reordered or removed** — anything that has to survive a list change
     * must be written by name somewhere else.
     */
    val choices: List<String> = emptyList(),
    /**
     * Words for the two ends of a knob whose number means little — a cabinet's MIC reads OFF-AXIS
     * and ON-AXIS rather than 0.00 and 1.00, and a percentage in between.
     */
    val ends: Pair<String, String>? = null,
) {

    val isChoice: Boolean get() = choices.isNotEmpty()

    @Volatile
    var value: Float = default.coerceIn(min, max)
        set(v) {
            field = v.coerceIn(min, max)
        }

    /**
     * The selected option, for a parameter that has any.
     *
     * Read on the audio thread every block by [FxSlot], so it rounds and clamps rather than trusting
     * the stored float — a preset written by a later version can name an option this one does not
     * have, and the answer to that is the nearest one that exists, not an exception in the callback.
     */
    var index: Int
        get() = value.roundToInt().coerceIn(0, (choices.size - 1).coerceAtLeast(0))
        set(v) {
            value = v.toFloat()
        }

    /** Knob position in 0..1. */
    var position: Float
        get() = ((value - min) / (max - min)).coerceIn(0f, 1f).pow(1f / skew)
        set(p) {
            value = min + (max - min) * p.coerceIn(0f, 1f).pow(skew)
        }

    fun reset() {
        value = default
    }

    fun formatted(): String {
        if (isChoice) return choices.getOrElse(index) { "" }
        ends?.let { (low, high) ->
            val where = position
            return when {
                where <= 0.005f -> low
                where >= 0.995f -> high
                else -> "${(where * 100f).roundToInt()} %"
            }
        }
        val decimals = when {
            max - min >= 100f -> 0
            max - min >= 10f -> 1
            else -> 2
        }
        val text = String.format(Locale.US, "%.${decimals}f", value)
        return if (unit.isEmpty()) text else "$text $unit"
    }

    companion object {
        /**
         * A parameter that picks one of [choices]. The range is the index range, so the knob travel
         * and the stored value are the same thing and nothing else has to know it is different.
         */
        fun choice(name: String, choices: List<String>, default: Int = 0): Parameter = Parameter(
            name = name,
            min = 0f,
            max = (choices.size - 1).coerceAtLeast(0).toFloat(),
            default = default.toFloat(),
            choices = choices,
        )
    }
}
