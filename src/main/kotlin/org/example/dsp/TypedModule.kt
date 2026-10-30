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

/** One thing a [TypedModule] can be: its dropdown label, and how to build it. */
class ModuleType(val label: String, val create: () -> AudioProcessor)

/**
 * A pedal that is one of several things, chosen from a dropdown on its header.
 *
 * Every other module in the app is one effect. This one holds an instance of every type it offers,
 * runs exactly one of them, and switches between them while the stream runs. [FxSlot] is one — an
 * empty space the player fills — and [Overdrive] is another: a pedal with a fixed place and name
 * whose dropdown picks which kind of drive it is. The knobs on the pedal are whichever the selected
 * type has, so a two-knob fuzz and a four-knob tube stage share one pedal.
 *
 * ### Why every type is built up front
 *
 * All of them are constructed at creation, prepared together in [prepare], and warmed together
 * through [alternates]. That is not eagerness for its own sake — it is the only arrangement the
 * threading contract permits. Selecting a type happens while the stream is running, and constructing
 * an effect on that path would allocate, load classes and run a cold interpreter against a 1.45 ms
 * deadline. A few idle objects cost some memory and one branch; the alternative costs a dropout
 * every time the dropdown is touched.
 *
 * It also makes the *second* instance cheap. Warm-up exists to force class loading and JIT
 * compilation, both of which happen per class rather than per instance, so the first Chorus
 * anywhere on the board pays for every Chorus after it.
 *
 * ### Why the switch fades
 *
 * Swapping effects between two blocks cuts the outgoing one's tail mid-waveform and starts the
 * incoming one from zeroed state — a step discontinuity, which is a click. So the module ramps its
 * output down across a few blocks, swaps at silence, and ramps back up. Roughly [FADE_SECONDS] each
 * way: fast enough to feel immediate, slow enough that nothing steps.
 *
 * The swap point is also where the incoming effect is cleared, which is a `fill(0f)` over a delay
 * line — bounded, allocation-free, and paid once per deliberate user action rather than per block.
 *
 * ### The rule the preset format depends on
 *
 * **A type list is append-only.** A preset stores the selection twice — by name, which is what is
 * actually read back, and incidentally as [Parameter.choice]'s index in the values map. Reordering
 * a list would leave the second one pointing at the wrong effect, and the two disagreeing is worse
 * than either being wrong alone.
 */
abstract class TypedModule(private val types: List<ModuleType>) : AudioProcessor {

    @Volatile
    override var enabled = false

    val type = Parameter.choice("Type", types.map { it.label })

    /**
     * One of everything, in type order.
     *
     * Separate instances from anything else on the board — an FX slot's Delay shares no line with
     * the fixed Delay pedal, so the two can sit at different points in the chain without being one.
     */
    private val instances: Array<AudioProcessor> = Array(types.size) { types[it].create() }

    override val alternates: List<AudioProcessor> get() = instances.asList()

    /** The names a dropdown offers, in order. */
    val typeNames: List<String> get() = types.map { it.label }

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

    /** The type actually being processed. Lags [type] by the length of a fade; see [process]. */
    private var current = 0

    private var fade = 1f
    private var fadeStep = stepFor(DEFAULT_SAMPLE_RATE)

    /** The type currently selected, by name — what a preset stores. */
    val typeName: String get() = types[type.index].label

    /** The instance behind one type, for a subclass that exposes its knobs directly. */
    protected fun instance(index: Int): AudioProcessor = instances[index]

    /**
     * Selects by name, ignoring a name this build does not have.
     *
     * By name rather than by index because the index is positional and a preset outlives the list it
     * was written against. A module whose type has been removed keeps its default rather than
     * silently becoming whatever now occupies that position.
     */
    fun selectType(name: String) {
        val index = types.indexOfFirst { it.label.equals(name, ignoreCase = true) }
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
            val prefix = types[index].label
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
            val prefix = types[index].label
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

    private companion object {
        /** Each way of the switch. Long enough not to step, short enough to feel like a switch. */
        const val FADE_SECONDS = 0.012f

        fun stepFor(sampleRate: Float): Float =
            1f / (FADE_SECONDS * sampleRate).coerceAtLeast(1f)
    }
}
