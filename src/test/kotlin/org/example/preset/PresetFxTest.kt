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
import org.example.dsp.Gate
import org.example.dsp.MasterVolume
import org.example.dsp.Reverb
import org.example.util.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetFxTest {

    /** Through the text, not merely through the data classes — the file is the thing that has to work. */
    private fun roundTrip(preset: Preset): Preset =
        Preset.fromJsonValue(Json.parse(Json.write(preset.toJsonValue())))!!

    @Test
    fun `a preset written before slots existed reads as a board with none`() {
        val old = """
            { "name": "Crunch",
              "order": ["Gate", "Overdrive"],
              "enabled": { "Overdrive": true },
              "values": { "Overdrive.Drive": 12.0 } }
        """.trimIndent()

        val preset = Preset.fromJsonValue(Json.parse(old))!!
        assertEquals("Crunch", preset.name)
        assertEquals(12f, preset.values["Overdrive.Drive"])
        // Silence about slots means no slots, which is the only reading that keeps an old sound
        // sounding the way it did.
        assertTrue(preset.fx.isEmpty())
    }

    @Test
    fun `a sound with no slots writes no fx key at all`() {
        val json = Preset("Clean", listOf("Gate"), emptyMap(), emptyMap()).toJsonValue()
        assertTrue("fx" !in json, "an empty list should not appear in the file")
    }

    @Test
    fun `slots survive the round trip in order, with every type's knobs`() {
        val preset = Preset(
            name = "Nylon",
            order = listOf("EQ", "FX 1", "FX 2", "Reverb"),
            enabled = mapOf("FX 1" to true, "FX 2" to false),
            values = mapOf("FX 1.Attack" to -6f),
            fx = listOf(
                Preset.Fx("Transient", mapOf("Transient.Attack" to -6f, "Chorus.Rate" to 0.8f)),
                Preset.Fx("De-Ess", mapOf("De-Ess.Freq" to 6500f)),
            ),
        )

        val back = roundTrip(preset)

        assertEquals(2, back.fx.size)
        assertEquals("Transient", back.fx[0].type)
        assertEquals("De-Ess", back.fx[1].type)
        // Including the type that is not selected — that is what makes switching away and back
        // return the sound you had rather than a fresh one.
        assertEquals(0.8f, back.fx[0].values["Chorus.Rate"])
        assertEquals(6500f, back.fx[1].values["De-Ess.Freq"])
    }

    @Test
    fun `position is the identity, so the same type twice is not ambiguous`() {
        val preset = Preset(
            name = "Two the same",
            order = listOf("FX 1", "FX 2"),
            enabled = emptyMap(),
            values = emptyMap(),
            fx = listOf(
                Preset.Fx("Tremolo", mapOf("Tremolo.Rate" to 2f)),
                Preset.Fx("Tremolo", mapOf("Tremolo.Rate" to 9f)),
            ),
        )

        val back = roundTrip(preset)
        // Two tremolos are told apart by where they are and by nothing else. A map keyed by type,
        // or by name, would have lost one of these.
        assertEquals(2f, back.fx[0].values["Tremolo.Rate"])
        assertEquals(9f, back.fx[1].values["Tremolo.Rate"])
    }

    @Test
    fun `an entry with no type is dropped rather than defaulted`() {
        val json = """
            { "name": "Broken",
              "order": ["FX 1", "FX 2"],
              "enabled": {}, "values": {},
              "fx": [ { "values": { "Chorus.Rate": 1.0 } },
                      { "type": "Chorus", "values": {} } ] }
        """.trimIndent()

        val preset = Preset.fromJsonValue(Json.parse(json))!!
        // A slot whose identity is unknown is not a slot. One bad entry must not cost the file.
        assertEquals(1, preset.fx.size)
        assertEquals("Chorus", preset.fx[0].type)
    }

    @Test
    fun `capture reads the slots off the board, in board order`() {
        val master = MasterVolume()
        val gate = Gate()
        val first = FxSlot(1).apply {
            selectType("Chorus")
            applyValues(mapOf("Chorus.Rate" to 1.25f, "Tremolo.Depth" to 0.9f))
        }
        val second = FxSlot(2).apply { selectType("Delay") }
        val reverb = Reverb()

        val order: List<AudioProcessor> = listOf(gate, first, second, reverb)
        val captured = Preset.capture("Board", order, listOf(master) + order)

        assertEquals(listOf("Gate", "FX 1", "FX 2", "Reverb"), captured.order)
        assertEquals(listOf("Chorus", "Delay"), captured.fx.map { it.type })
        assertEquals(1.25f, captured.fx[0].values["Chorus.Rate"])
        // The unselected type came along too.
        assertEquals(0.9f, captured.fx[0].values["Tremolo.Depth"])
    }

    @Test
    fun `a captured board comes back the same after a round trip through the file`() {
        val master = MasterVolume()
        val slot = FxSlot(1).apply {
            selectType("De-Ess")
            applyValues(mapOf("De-Ess.Range" to 15f))
            enabled = true
        }
        val order: List<AudioProcessor> = listOf(slot)

        val back = roundTrip(Preset.capture("Session", order, listOf(master) + order))

        val restored = FxSlot(1)
        restored.selectType(back.fx[0].type)
        restored.applyValues(back.fx[0].values)

        assertEquals("De-Ess", restored.typeName)
        assertEquals(15f, restored.captureValues()["De-Ess.Range"])
        assertEquals(true, back.enabled["FX 1"])
    }

    @Test
    fun `applying a preset returns slots it does not mention to their defaults`() {
        val master = MasterVolume()
        val slot = FxSlot(1).apply {
            selectType("Tremolo")
            enabled = true
        }
        val all: List<AudioProcessor> = listOf(master, slot)

        Preset("Nothing here", listOf("FX 1"), emptyMap(), emptyMap()).applyTo(all)

        // Same rule as every other module: silence means "there was none of this in the sound", not
        // "leave whatever was there from the last thing you played".
        assertEquals(false, slot.enabled)
        assertEquals(FxSlot.typeNames.first(), slot.typeName)
    }
}
