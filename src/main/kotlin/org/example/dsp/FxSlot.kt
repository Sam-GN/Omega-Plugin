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
 * Every other module in the app is one effect. This one is a *slot*: it holds an instance of every
 * type it offers, runs exactly one of them, and switches between them from a dropdown on its header.
 * A board can carry as many as the player adds, and they are numbered rather than named — see
 * [label].
 *
 * ### Why every type is built up front
 *
 * All of them are constructed at slot creation, prepared together in [prepare], and warmed together
 * through [alternates]. That is not eagerness for its own sake — it is the only arrangement the
 * threading contract permits. Selecting a type happens while the stream is running, and constructing
 * an effect on that path would allocate, load classes and run a cold interpreter against a 1.45 ms
 * deadline. Six idle objects cost some memory and one branch; the alternative costs a dropout every
 * time the dropdown is touched.
 *
 * It also makes the *second* slot cheap. Warm-up exists to force class loading and JIT compilation,
 * both of which happen per class rather than per instance, so the first Chorus anywhere on the board
 * pays for every Chorus after it.
 *
 * ### Why the switch fades
 *
 * Swapping effects between two blocks cuts the outgoing one's tail mid-waveform and starts the
 * incoming one from zeroed state — a step discontinuity, which is a click. So the slot ramps its
 * output down across a few blocks, swaps at silence, and ramps back up. Roughly [FADE_SECONDS] each
 * way: fast enough to feel immediate, slow enough that nothing steps.
 *
 * The swap point is also where the incoming effect is cleared, which is a `fill(0f)` over a delay
 * line — bounded, allocation-free, and paid once per deliberate user action rather than per block.
 */
class FxSlot(number: Int = 1) : AudioProcessor {

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

    @Volatile
    override var enabled = false

    val type = Parameter.choice("Type", TYPES.map { it.label })

    /**
     * One of everything, in [TYPES] order.
     *
     * Delay and Reverb appear here as well as on the fixed board, and they are separate instances —
     * so a slot can hold a second delay at a different point in the chain without the two sharing a
     * line, which would be neither.
     */
    private val instances: Array<AudioProcessor> = Array(TYPES.size) { TYPES[it].create() }

    override val alternates: List<AudioProcessor> get() = instances.asList()

    /**
     * Type plus whatever the selected effect offers.
     *
     * Recomputed only when the selection changes. Read from the event thread — by the panel that
     * draws the knobs and by preset capture — and never from the audio thread, which needs the
     * index and nothing else.
     */
    override val parameters: List<Parameter>
        get() {
            val selected = type.index
            if (selected != shownFor) {
                shownFor = selected
                shown = ArrayList<Parameter>(instances[selected].parameters.size + 1).apply {
                    add(type)
                    addAll(instances[selected].parameters)
                }
            }
            return shown
        }

    private var shownFor = -1
    private var shown: List<Parameter> = emptyList()

    private var sampleRate = DEFAULT_SAMPLE_RATE

    /** The type actually being processed. Lags [type] by the length of a fade; see [process]. */
    private var current = 0

    private var fade = 1f
    private var fadeStep = stepFor(DEFAULT_SAMPLE_RATE)

    /** The type currently selected, by name — what a preset stores. */
    val typeName: String get() = TYPES[type.index].label

    /**
     * Selects by name, ignoring a name this build does not have.
     *
     * By name rather than by index because the index is positional and a preset outlives the list it
     * was written against. A slot whose type has been removed keeps its default rather than
     * silently becoming whatever now occupies that position.
     */
    fun selectType(name: String) {
        val index = TYPES.indexOfFirst { it.label.equals(name, ignoreCase = true) }
        if (index >= 0) type.index = index
    }

    /**
     * Every type's knob values, not just the selected one's, keyed `Type.Knob`.
     *
     * So that dialling in a chorus, switching to a tremolo to hear it, and switching back returns
     * the chorus you had rather than a fresh one. The keys are built here because only this class
     * knows they need a type prefix — `Rate` means two different things two types apart.
     */
    fun captureValues(): Map<String, Float> {
        val values = LinkedHashMap<String, Float>()
        for (index in instances.indices) {
            val prefix = TYPES[index].label
            for (parameter in instances[index].parameters) {
                values["$prefix.${parameter.name}"] = parameter.value
            }
        }
        return values
    }

    /** The inverse of [captureValues]. Anything absent goes back to its default, never to whatever
     *  the previous sound left in it. */
    fun applyValues(values: Map<String, Float>) {
        for (index in instances.indices) {
            val prefix = TYPES[index].label
            for (parameter in instances[index].parameters) {
                parameter.value = values["$prefix.${parameter.name}"] ?: parameter.default
            }
        }
    }

    /**
     * True when nothing here has been touched — used to decide whether deleting it needs to ask.
     *
     * A slot that was added a moment ago and never dialled in should not demand confirmation to go
     * away again; one with work in it should.
     */
    fun isUntouched(): Boolean =
        !enabled &&
            type.value == type.default &&
            instances.all { effect -> effect.parameters.all { it.value == it.default } }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        fadeStep = stepFor(sampleRate)
        // All of them, not just the selected one: an unprepared effect switched to mid-stream would
        // be sized for whatever rate it was constructed at, which is the bug prepare() exists for.
        for (effect in instances) effect.prepare(sampleRate, maxBlockFrames)
    }

    override fun process(buffer: FloatArray, count: Int) {
        if (count <= 0) return

        val wanted = type.index
        val switching = wanted != current

        instances[current].process(buffer, count)

        val start = fade
        val target = if (switching) 0f else 1f
        // The overwhelmingly common case: no switch pending and the fade already home. Nothing to
        // apply, and no pass over the buffer to apply it with.
        if (start == 1f && target == 1f) return

        val span = fadeStep * count
        val end = if (target > start) {
            if (start + span > 1f) 1f else start + span
        } else {
            if (start - span < 0f) 0f else start - span
        }

        val delta = (end - start) / count
        var g = start
        for (i in 0 until count) {
            buffer[i] *= g
            g += delta
        }
        fade = end

        if (switching && end <= 0f) {
            current = wanted
            // At silence, so clearing the incoming effect's state cannot be heard. Bounded and
            // allocation-free — a memset over a delay line — and paid once per switch.
            instances[current].reset()
        }
    }

    override fun reset() {
        current = type.index
        fade = 1f
        for (effect in instances) effect.reset()
    }

    /** One type the slot can be. */
    private class Type(val label: String, val create: () -> AudioProcessor)

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
            Type("Chorus") { Chorus() },
            Type("Tremolo") { Tremolo() },
            Type("Transient") { TransientShaper() },
            Type("De-Ess") { DeEsser() },
            Type("Delay") { Delay() },
            Type("Reverb") { Reverb() },
            Type("Octave") { Octave() },
        )

        val typeNames: List<String> get() = TYPES.map { it.label }

        fun labelFor(number: Int): String = "FX $number"

        /** True for the labels this class hands out, so the board can pick its slots out of a list. */
        fun isSlotName(name: String): Boolean =
            name.startsWith("FX ") && name.removePrefix("FX ").toIntOrNull() != null

        /** Each way of the switch. Long enough not to step, short enough to feel like a switch. */
        private const val FADE_SECONDS = 0.012f

        private fun stepFor(sampleRate: Float): Float =
            1f / (FADE_SECONDS * sampleRate).coerceAtLeast(1f)
    }
}
