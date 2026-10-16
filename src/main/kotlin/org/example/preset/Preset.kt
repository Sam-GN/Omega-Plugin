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
package org.example.preset

import org.example.dsp.AudioProcessor
import org.example.dsp.FxSlot

/**
 * A saved sound: pedal order, every knob, every switch.
 *
 * Deliberately **not** the audio setup — no driver, device, rate or buffer. A preset describes a
 * sound, so it has to load correctly on a machine that does not have the interface it was made on.
 *
 * Effects are referenced by name rather than by index so a preset survives a new effect being added
 * to the chain: anything the preset does not mention is left alone, and anything it mentions that no
 * longer exists is ignored.
 */
data class Preset(
    val name: String,
    /** Pedal names in signal order. Excludes the master, which is pinned at the head. */
    val order: List<String>,
    /** Effect name to bypass state. */
    val enabled: Map<String, Boolean>,
    /** `"Effect.Parameter"` to value. */
    val values: Map<String, Float>,
    /**
     * Effect name to the file it had loaded — a NAM capture, an impulse response.
     *
     * Keyed by effect name for the same reason [enabled] and [values] are: a module that gains a
     * file later needs no new field, and one that goes away is simply not looked up.
     *
     * A path is the one machine-specific thing a preset carries, and it is carried anyway because
     * the alternative is worse: restoring a module's knobs but not its file is not the sound that
     * was saved. Stored per preset, so two presets naming the same file can point at different ones.
     */
    val files: Map<String, FileRef> = emptyMap(),
    /**
     * The FX slots on the board, in slot order — what each one is, and every type's knobs.
     *
     * A **list**, not a map keyed by name, and that is the point of it. Slots are numbered by
     * position rather than named, so deleting FX 2 renumbers FX 3 down into its place; a map keyed
     * by "FX 3" would then be pointing at a slot that no longer exists, next to a slot that has
     * inherited a key it never wrote. Position is the identity, and a list is what position is.
     *
     * It also carries what the fixed maps cannot: how *many* slots there are. [applyTo] can match
     * modules by name but it cannot bring one into existence, so the board is made to match this
     * list before the rest of the preset is applied.
     */
    val fx: List<Fx> = emptyList(),
) {

    /**
     * One slot: which effect it is, and the knobs of every effect it could be.
     *
     * All of them rather than only the selected one, so switching type to hear something and
     * switching back returns the sound that was dialled in rather than a fresh one. Keys are
     * `Type.Knob` — see [org.example.dsp.FxSlot.captureValues], which builds them, because `Rate`
     * means two unrelated things two types apart.
     */
    data class Fx(val type: String, val values: Map<String, Float>) {
        fun toJsonValue(): Map<String, Any?> = linkedMapOf(
            "type" to type,
            "values" to values.mapValues { it.value.toDouble() },
        )
    }

    /**
     * A file a module was using.
     *
     * The [name] is kept beside the [path] rather than derived from it, so a preset whose file has
     * moved can still say *which* file it wants — which is the whole of what the player needs in
     * order to go and find it.
     */
    data class FileRef(val name: String, val path: String)

    fun toJsonValue(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "order" to order,
        "enabled" to enabled,
        "values" to values.mapValues { it.value.toDouble() },
    ).apply {
        // Written only when there is something to write, so a preset whose modules load no files
        // reads exactly as it always did.
        if (files.isNotEmpty()) {
            this["files"] = files.mapValues { (_, file) ->
                linkedMapOf<String, Any?>("name" to file.name, "path" to file.path)
            }
        }
        // Likewise absent when the board has no slots, so a sound built entirely from the fixed
        // modules reads exactly as it did before slots existed.
        if (fx.isNotEmpty()) this["fx"] = fx.map { it.toJsonValue() }
    }

    /**
     * Applies the preset and returns the pedal order it asks for.
     *
     * **Anything the preset does not mention is switched off and returned to its defaults.** That is
     * what keeps old presets working as new modules are added: a preset saved before the compressor
     * existed says nothing about it, and the only safe reading of that silence is "there was no
     * compressor in this sound". Leaving it as it was would mean the same preset sounded different
     * depending on what you happened to be playing with beforehand.
     *
     * The one exception is an effect whose switch mutes rather than bypasses — the master. Off there
     * means silence, so an unmentioned master defaults to *on*; a preset that fails to mention it
     * should not load as a silent app.
     *
     * Names it does not recognise are skipped, and effects it does not mention keep their position
     * at the end of the chain.
     */
    fun applyTo(all: List<AudioProcessor>): List<AudioProcessor> {
        val byName = all.associateBy { it.name }

        for (effect in all) {
            effect.enabled = enabled[effect.name] ?: effect.mutesWhenDisabled
            for (parameter in effect.parameters) {
                parameter.value = values["${effect.name}.${parameter.name}"] ?: parameter.default
            }
        }

        val requested = order.mapNotNull { byName[it] }
        return requested + all.filter { it !in requested }
    }

    companion object {

        /** Snapshots the current sound. [order] is the pedals; [all] is everything including master. */
        fun capture(
            name: String,
            order: List<AudioProcessor>,
            all: List<AudioProcessor>,
            files: Map<String, FileRef> = emptyMap(),
        ): Preset {
            val values = LinkedHashMap<String, Float>()
            val enabled = LinkedHashMap<String, Boolean>()
            for (effect in all) {
                enabled[effect.name] = effect.enabled
                for (parameter in effect.parameters) {
                    values["${effect.name}.${parameter.name}"] = parameter.value
                }
            }
            // From [order] rather than [all], because the slots' numbering is their position on the
            // board and [order] is what that position means. The master is not on the board and can
            // never be a slot.
            val fx = order.filterIsInstance<FxSlot>().map { Fx(it.typeName, it.captureValues()) }
            return Preset(name, order.map { it.name }, enabled, values, files, fx)
        }

        /** Returns null rather than throwing: one bad entry must not cost the whole file. */
        fun fromJsonValue(value: Any?): Preset? {
            val map = value as? Map<*, *> ?: return null
            val name = map["name"] as? String ?: return null
            val order = (map["order"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val enabled = (map["enabled"] as? Map<*, *>)
                ?.mapNotNull { (k, v) -> (k as? String)?.let { key -> (v as? Boolean)?.let { key to it } } }
                ?.toMap()
                ?: emptyMap()
            val values = (map["values"] as? Map<*, *>)
                ?.mapNotNull { (k, v) ->
                    (k as? String)?.let { key -> (v as? Double)?.let { key to it.toFloat() } }
                }
                ?.toMap()
                ?: emptyMap()
            val files = LinkedHashMap<String, FileRef>()
            (map["files"] as? Map<*, *>)?.forEach { (key, value) ->
                val effect = key as? String ?: return@forEach
                fileRef(value)?.let { files[effect] = it }
            }
            // 1.0.17 wrote the NAM capture under its own key before the map existed. Read rather
            // than migrate: a preset saved by that build must keep working, and it will pick up the
            // current shape the next time it is saved.
            if ("NAM" !in files) fileRef(map["nam"])?.let { files["NAM"] = it }

            // A preset written before slots existed has no "fx" at all, and an empty list is the
            // correct reading of that: no slots on the board. Entries without a type are dropped
            // rather than defaulted, because a slot whose identity is unknown is not a slot.
            val fx = (map["fx"] as? List<*>)?.mapNotNull { entry ->
                val slot = entry as? Map<*, *> ?: return@mapNotNull null
                val type = slot["type"] as? String ?: return@mapNotNull null
                val slotValues = (slot["values"] as? Map<*, *>)
                    ?.mapNotNull { (k, v) ->
                        (k as? String)?.let { key -> (v as? Double)?.let { key to it.toFloat() } }
                    }
                    ?.toMap()
                    ?: emptyMap()
                Fx(type, slotValues)
            } ?: emptyList()

            return Preset(name, order, enabled, values, files, fx)
        }

        private fun fileRef(value: Any?): FileRef? {
            val map = value as? Map<*, *> ?: return null
            val path = map["path"] as? String ?: return null
            val name = map["name"] as? String ?: path.substringAfterLast('\\').substringAfterLast('/')
            return FileRef(name, path)
        }
    }
}
