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

/**
 * An empty space on the board that the player decides the contents of.
 *
 * A [TypedModule] — one instance of every type it offers, running whichever the dropdown selects,
 * switching with a fade — that a board can carry as many of as the player adds. They are numbered
 * rather than named; see [label].
 */
class FxSlot(number: Int = 1) : TypedModule(TYPES) {

    /**
     * The board's label for this slot, and its identity in a preset.
     *
     * Positional on purpose. Naming a slot after what it holds falls over the moment someone picks
     * the same type twice — there is no useful answer to what the second tremolo is called — so the
     * slots are numbered and the *type* is a setting inside them. Deleting one closes the gap, which
     * means this changes; hence a `val` backed by something mutable rather than a constant, and
     * hence [org.example.preset.Preset] recording slot types positionally rather than by this name.
     */
    var label: String = labelFor(number)

    override val name: String get() = label

    companion object {

        /**
         * What a slot can hold.
         *
         * **Append only.** A preset stores the selection twice — by name in its `fx` list, which is
         * what is actually read back, and incidentally as [Parameter.choice]'s index in the values
         * map. Reordering this list would leave the second one pointing at the wrong effect, and the
         * two disagreeing is worse than either being wrong alone.
         */
        private val TYPES = listOf(
            ModuleType("Chorus") { Chorus() },
            ModuleType("Tremolo") { Tremolo() },
            ModuleType("Transient") { TransientShaper() },
            ModuleType("De-Ess") { DeEsser() },
            ModuleType("Delay") { Delay() },
            ModuleType("Reverb") { Reverb() },
            ModuleType("Octave") { Octave() },
        )

        val typeNames: List<String> get() = TYPES.map { it.label }

        fun labelFor(number: Int): String = "FX $number"

        /** True for the labels this class hands out, so the board can pick its slots out of a list. */
        fun isSlotName(name: String): Boolean =
            name.startsWith("FX ") && name.removePrefix("FX ").toIntOrNull() != null
    }
}
