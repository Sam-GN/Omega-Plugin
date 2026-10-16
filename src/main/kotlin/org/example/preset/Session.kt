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

/**
 * Where you left off: the sound on screen, and the audio setup it was running through.
 *
 * A [Preset] deliberately holds no devices, because a sound has to load on a machine that has never
 * seen the interface it was made on. A session is the opposite half of that split — it belongs to
 * **this machine**, and it is the answer to "put it back exactly as I left it". So it carries a
 * preset *inside* it rather than being one, and the device selection alongside.
 *
 * The [sound] is the truth about the knobs, whether or not a preset was loaded. That is the whole
 * point: a preset that was loaded and then modified must come back modified, and a sound that was
 * never saved at all must come back too. [sound]'s own name doubles as the preset it came from, and
 * is blank when it came from nowhere — see [presetName].
 *
 * Every field but the sound is optional and every reader is tolerant, because this file is written
 * by whatever version happened to close last.
 */
data class Session(
    val sound: Preset,
    /** True when the sound had moved away from the preset it was loaded from. Restores the • dot. */
    val edited: Boolean,
    /** [org.example.audio.BackendId] by name, kept as a string so this package stays free of `audio/`. */
    val backend: String? = null,
    val input: DeviceRef? = null,
    val output: DeviceRef? = null,
    val sampleRate: Int? = null,
    val bufferFrames: Int? = null,
    /**
     * The metronome, which belongs to the machine rather than to the sound.
     *
     * In a session and not in a [Preset] because a preset is a *tone* — it has to load unchanged on
     * someone else's rig — and 132 BPM is not part of a tone. It is where you left the practice room.
     */
    val rhythm: Rhythm? = null,
) {

    /**
     * Held as plain strings and numbers rather than as the enums and objects it came from.
     *
     * This package is deliberately free of `audio/`, and for exactly the same reason it stays free
     * of `dsp/`: a session is a file format, and a file format that names live classes breaks the
     * moment one of them is renamed. [feel] is matched back by name against the catalogue for the
     * signature, and a name that no longer exists falls back to the plain click rather than failing.
     */
    data class Rhythm(
        val tempoBpm: Double,
        /** A [org.example.dsp.rhythm.TimeSignature]'s display string, "4/4". */
        val signature: String,
        /** A pattern's name within that signature, "Click + 8ths". */
        val feel: String,
        val level: Float,
        val clickOverDrums: Boolean,
        val recordTwin: Boolean,
        val countIn: Boolean,
        val countInBars: Int,
    ) {
        fun toJsonValue(): Map<String, Any?> = linkedMapOf(
            "tempoBpm" to tempoBpm,
            "signature" to signature,
            "feel" to feel,
            "level" to level,
            "clickOverDrums" to clickOverDrums,
            "recordTwin" to recordTwin,
            "countIn" to countIn,
            "countInBars" to countInBars,
        )
    }

    /**
     * A device as it was chosen.
     *
     * The [id] is the driver's own stable string — see [org.example.audio.BackendDevice] — and is
     * what a match is made on. The [name] is kept beside it for two reasons: it is a second chance
     * at matching when a driver update changes the id, and it is the only way to say *which* device
     * has gone when the answer is that none of them is it.
     */
    data class DeviceRef(val id: String, val name: String) {
        fun toJsonValue(): Map<String, Any?> = linkedMapOf("id" to id, "name" to name)
    }

    /** The preset this sound came from, or null when it came from nowhere in particular. */
    val presetName: String? get() = sound.name.ifBlank { null }

    fun toJsonValue(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "sound" to sound.toJsonValue(),
        "edited" to edited,
    ).apply {
        // Absent rather than null when there is nothing to say, so a session written before a device
        // was ever chosen reads as "no preference" rather than as a device that could not be found.
        backend?.let { this["backend"] = it }
        input?.let { this["input"] = it.toJsonValue() }
        output?.let { this["output"] = it.toJsonValue() }
        sampleRate?.let { this["sampleRate"] = it }
        bufferFrames?.let { this["bufferFrames"] = it }
        rhythm?.let { this["rhythm"] = it.toJsonValue() }
    }

    companion object {

        /**
         * Returns null rather than throwing, on anything at all.
         *
         * This is read on the way to a window being shown, and a session file that cannot be
         * understood must cost the session and not the launch. Everything below the sound is
         * optional: a session missing its devices still restores the knobs.
         */
        fun fromJsonValue(value: Any?): Session? {
            val map = value as? Map<*, *> ?: return null
            val sound = Preset.fromJsonValue(map["sound"]) ?: return null
            return Session(
                sound = sound,
                edited = map["edited"] as? Boolean ?: false,
                backend = (map["backend"] as? String)?.takeIf { it.isNotBlank() },
                input = deviceRef(map["input"]),
                output = deviceRef(map["output"]),
                sampleRate = whole(map["sampleRate"]),
                bufferFrames = whole(map["bufferFrames"]),
                rhythm = rhythm(map["rhythm"]),
            )
        }

        /**
         * Absent, malformed or partial all read as "no preference", which is what lets a session
         * written before this feature existed restore everything else it does carry.
         */
        private fun rhythm(value: Any?): Rhythm? {
            val map = value as? Map<*, *> ?: return null
            val tempo = (map["tempoBpm"] as? Double) ?: whole(map["tempoBpm"])?.toDouble() ?: return null
            return Rhythm(
                tempoBpm = tempo,
                signature = map["signature"] as? String ?: return null,
                feel = map["feel"] as? String ?: return null,
                level = ((map["level"] as? Double) ?: 0.5).toFloat(),
                clickOverDrums = map["clickOverDrums"] as? Boolean ?: true,
                recordTwin = map["recordTwin"] as? Boolean ?: false,
                countIn = map["countIn"] as? Boolean ?: false,
                countInBars = whole(map["countInBars"])?.coerceIn(1, 2) ?: 1,
            )
        }

        private fun deviceRef(value: Any?): DeviceRef? {
            val map = value as? Map<*, *> ?: return null
            val id = map["id"] as? String ?: return null
            return DeviceRef(id, map["name"] as? String ?: id)
        }

        /** JSON has one number type, so a frame count arrives as a Double when it was parsed. */
        private fun whole(value: Any?): Int? = when (value) {
            is Double -> value.toInt()
            is Int -> value
            else -> null
        }
    }
}
