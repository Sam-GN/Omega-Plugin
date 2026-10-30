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

import org.example.util.Config
import org.example.util.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session: where you left off, put back exactly.
 *
 * Two properties matter more than the rest and both are asserted below. It must survive the trip
 * through [Json] unchanged — a session that comes back subtly different is worse than one that does
 * not come back at all, because nothing announces it. And it must **never throw**, whatever is in
 * the file: this is read on the way to showing a window, so a session that cannot be understood has
 * to cost the session and not the launch.
 */
class SessionTest {

    private val folder: Path = Files.createTempDirectory("omega-session")

    @AfterTest
    fun cleanUp() {
        Files.walk(folder).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun sound(name: String = "Crunch") = Preset(
        name = name,
        order = listOf("Gain", "Overdrive", "NAM"),
        enabled = mapOf("Gain" to true, "Overdrive" to false),
        values = mapOf("Gain.Gain" to 6.5f),
        files = mapOf("NAM" to Preset.FileRef("GOAT.nam", """D:\Amps\GOAT.nam""")),
    )

    private fun full() = Session(
        sound = sound(),
        edited = true,
        backend = "ASIO",
        input = Session.DeviceRef("asio:Sonicake", "Sonicake ASIO"),
        output = Session.DeviceRef("asio:Sonicake", "Sonicake ASIO"),
        sampleRate = 44_100,
        bufferFrames = 64,
    )

    /** Through the real writer and the real parser, not just the two conversions back to back. */
    private fun roundTrip(session: Session): Session? =
        Session.fromJsonValue(Json.parse(Json.write(session.toJsonValue())))

    @Test
    fun `every field survives the trip through JSON`() {
        val restored = assertNotNull(roundTrip(full()))

        assertEquals(full(), restored)
        // Spelled out, because assertEquals on the whole object would pass just as happily if
        // toJsonValue and fromJsonValue agreed on the *wrong* key.
        assertEquals("Crunch", restored.presetName)
        assertTrue(restored.edited)
        assertEquals("ASIO", restored.backend)
        assertEquals("asio:Sonicake", restored.input?.id)
        assertEquals(44_100, restored.sampleRate)
        assertEquals(64, restored.bufferFrames)
        assertEquals(listOf("Gain", "Overdrive", "NAM"), restored.sound.order)
        assertEquals(6.5f, restored.sound.values["Gain.Gain"])
        assertEquals("""D:\Amps\GOAT.nam""", restored.sound.files["NAM"]?.path)
    }

    /**
     * A frame count must come back as the number that was asked for.
     *
     * JSON has one number type, so 64 goes out and 64.0 comes back. Reading it as a Double and
     * comparing it against the combo's Ints would silently match nothing, and the saved buffer size
     * would be quietly replaced by the driver's default on every launch.
     */
    @Test
    fun `rate and buffer come back as whole numbers`() {
        val parsed = Json.parse(Json.write(full().toJsonValue())) as Map<*, *>
        assertEquals(64.0, parsed["bufferFrames"], "JSON should hold it as a number, not a string")

        val restored = assertNotNull(Session.fromJsonValue(parsed))
        assertEquals(64, restored.bufferFrames)
        assertEquals(44_100, restored.sampleRate)
    }

    /**
     * A sound that was never saved has no preset name, and that has to be distinguishable from one
     * that has.
     *
     * It is what tells the window whether to put a name in the title bar — and whether "Save" would
     * be overwriting something or inventing it.
     */
    @Test
    fun `a sound that came from no preset has no name`() {
        val restored = assertNotNull(roundTrip(Session(sound(name = ""), edited = true)))

        assertNull(restored.presetName)
        assertEquals(6.5f, restored.sound.values["Gain.Gain"], "the sound is still all there")
    }

    @Test
    fun `a session with no devices still restores the sound`() {
        val restored = assertNotNull(roundTrip(Session(sound(), edited = false)))

        assertEquals("Crunch", restored.presetName)
        assertFalse(restored.edited)
        assertNull(restored.backend)
        assertNull(restored.input)
        assertNull(restored.sampleRate)
    }

    /** Junk of every shape reads as "no session", never as an exception. */
    @Test
    fun `anything it cannot understand reads as no session`() {
        assertNull(Session.fromJsonValue(null))
        assertNull(Session.fromJsonValue("a string"))
        assertNull(Session.fromJsonValue(listOf(1, 2, 3)))
        assertNull(Session.fromJsonValue(emptyMap<String, Any?>()), "no sound, so no session")
        assertNull(Session.fromJsonValue(mapOf("sound" to "not an object")))
        assertNull(Session.fromJsonValue(mapOf("sound" to mapOf("order" to listOf("Gain")))))
    }

    /**
     * Wrong types where the right ones were expected must degrade field by field, not fail whole.
     *
     * Hand-edited files and older versions both produce this, and losing the knobs because a device
     * id arrived as a number would be an absurd trade.
     */
    @Test
    fun `fields of the wrong type are dropped, and the rest is kept`() {
        val restored = assertNotNull(
            Session.fromJsonValue(
                mapOf(
                    "sound" to sound().toJsonValue(),
                    "edited" to "yes",
                    "backend" to 7.0,
                    "input" to listOf("Sonicake"),
                    "sampleRate" to "44100",
                    "bufferFrames" to 128.0,
                )
            )
        )

        assertEquals("Crunch", restored.presetName)
        assertFalse(restored.edited, "an unreadable flag reads as not edited")
        assertNull(restored.backend)
        assertNull(restored.input)
        assertNull(restored.sampleRate)
        assertEquals(128, restored.bufferFrames, "the one good field is still good")
    }

    /** A device that saved without a name is still matchable by id, which is the real key. */
    @Test
    fun `a device ref with no name falls back to its id`() {
        val restored = assertNotNull(
            Session.fromJsonValue(
                mapOf("sound" to sound().toJsonValue(), "input" to mapOf("id" to "wasapi:{guid}"))
            )
        )

        assertEquals("wasapi:{guid}", restored.input?.id)
        assertEquals("wasapi:{guid}", restored.input?.name)
    }

    /** Through the file the application actually writes, settings and session side by side. */
    @Test
    fun `it survives a round trip through the settings file`() {
        Config.at(folder).apply {
            recordingFolder = """D:\Takes"""
            session = full().toJsonValue()
        }

        val reloaded = Config.at(folder)

        assertEquals("""D:\Takes""", reloaded.recordingFolder, "the session must not evict settings")
        assertEquals(full(), Session.fromJsonValue(reloaded.session))
    }

    /** And a settings file written before sessions existed simply has none. */
    @Test
    fun `a settings file with no session reads as no session`() {
        Config.at(folder).recordingFolder = """D:\Takes"""

        assertNull(Session.fromJsonValue(Config.at(folder).session))
    }

    /** The loop board's switch rides along; a session from before it existed reads as off. */
    @Test
    fun `the loop board's switch survives the trip, and an older session reads as off`() {
        val on = Session(sound = sound(), edited = false, loop = true)
        val back = Session.fromJsonValue(Json.parse(Json.write(on.toJsonValue())))
        assertNotNull(back)
        assertTrue(back.loop, "loop on should come back on")

        val older = Session(sound = sound(), edited = false)
        val json = Json.write(older.toJsonValue())
        assertFalse(json.contains("\"loop\""), "a session with the loop off writes nothing about it")
        assertFalse(Session.fromJsonValue(Json.parse(json))!!.loop)
    }
}
