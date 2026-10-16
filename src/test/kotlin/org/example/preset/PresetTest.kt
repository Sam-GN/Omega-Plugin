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

import org.example.dsp.Compressor
import org.example.dsp.Delay
import org.example.util.Json
import org.example.dsp.Gain
import org.example.dsp.Gate
import org.example.dsp.MasterVolume
import org.example.dsp.Overdrive
import org.example.dsp.Reverb
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun `round-trips an object of every supported type`() {
        val original = linkedMapOf<String, Any?>(
            "name" to "Crunch",
            "order" to listOf("Gain", "Overdrive"),
            "enabled" to linkedMapOf("Gain" to true, "Delay" to false),
            "values" to linkedMapOf("Gain.Gain" to 6.5),
            "nothing" to null,
        )
        val parsed = Json.parse(Json.write(original)) as Map<*, *>

        assertEquals("Crunch", parsed["name"])
        assertEquals(listOf("Gain", "Overdrive"), parsed["order"])
        assertEquals(true, (parsed["enabled"] as Map<*, *>)["Gain"])
        assertEquals(6.5, (parsed["values"] as Map<*, *>)["Gain.Gain"])
        assertNull(parsed["nothing"])
    }

    @Test
    fun `escapes and restores the characters a preset name can legally contain`() {
        val awkward = "quote\" backslash\\ newline\n tab\t brace} bracket]"
        val parsed = Json.parse(Json.write(mapOf("n" to awkward))) as Map<*, *>
        assertEquals(awkward, parsed["n"])
    }

    @Test
    fun `writes numbers independently of the platform locale`() {
        // A machine set to a comma decimal separator would otherwise write "6,5" and fail to read
        // its own file back.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val text = Json.write(mapOf("v" to 6.5))
            assertTrue(text.contains("6.5"), "used a dot decimal separator, got: $text")
            assertEquals(6.5, (Json.parse(text) as Map<*, *>)["v"])
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `empty containers survive the round trip`() {
        val parsed = Json.parse(Json.write(mapOf("a" to emptyList<Any>(), "o" to emptyMap<String, Any>())))
        val map = parsed as Map<*, *>
        assertEquals(emptyList<Any>(), map["a"])
        assertEquals(emptyMap<String, Any>(), map["o"])
    }

    @Test
    fun `malformed input fails loudly rather than returning nonsense`() {
        assertFailsWith<Json.MalformedException> { Json.parse("{\"a\": }") }
        assertFailsWith<Json.MalformedException> { Json.parse("{\"a\": 1") }
        assertFailsWith<Json.MalformedException> { Json.parse("[1, 2") }
        assertFailsWith<Json.MalformedException> { Json.parse("\"unterminated") }
        assertFailsWith<Json.MalformedException> { Json.parse("{} trailing") }
    }

    /**
     * A byte-order mark at the front is skipped, not rejected.
     *
     * Nothing here writes one, but these files are hand-editable and both Notepad and PowerShell's
     * `Set-Content -Encoding utf8` add one. JSON's grammar has no room for it, so the parse used to
     * fail at offset 0 complaining about a character that is invisible in every editor that could
     * have produced it — and the whole file, preset or session, was lost to it. Found the hard way.
     */
    @Test
    fun `a leading byte-order mark does not cost the file`() {
        val parsed = Json.parse("\uFEFF{\"name\": \"Crunch\"}") as Map<*, *>

        assertEquals("Crunch", parsed["name"])
        // Only at the front, and only one: anywhere else it is genuinely malformed and saying so is
        // more use than quietly accepting a file nothing else will read.
        assertFailsWith<Json.MalformedException> { Json.parse("{\"a\": \uFEFF1}") }
    }
}

class PresetCaptureTest {

    private fun chain() = listOf(MasterVolume(), Gain(), Overdrive(), Delay(), Reverb())

    @Test
    fun `capture then apply restores knobs, switches and order`() {
        val source = chain()
        val (master, gain, overdrive, delay, reverb) = source
        gain.parameters[0].value = 7.5f
        overdrive.enabled = true
        delay.parameters[0].value = 500f
        master.parameters[0].value = 42f
        reverb.enabled = true

        val pedals = listOf(reverb, delay, overdrive, gain)
        val preset = Preset.capture("Test", pedals, listOf(master) + pedals)

        // A fresh chain at defaults, to prove the values really come from the preset.
        val target = chain()
        val order = preset.applyTo(target)

        // Master is not part of a preset's order — the window pins it at the head of the chain — so
        // it comes back among the unmentioned effects and its position here is irrelevant.
        assertEquals(
            listOf("Reverb", "Delay", "Overdrive", "Gain"),
            order.filter { it.name != "Master" }.map { it.name },
        )
        assertTrue(order.any { it.name == "Master" }, "master is still in the returned chain")
        assertEquals(7.5f, target.first { it.name == "Gain" }.parameters[0].value)
        assertEquals(500f, target.first { it.name == "Delay" }.parameters[0].value)
        assertEquals(42f, target.first { it.name == "Master" }.parameters[0].value)
        assertTrue(target.first { it.name == "Overdrive" }.enabled)
        assertTrue(target.first { it.name == "Reverb" }.enabled)
    }

    @Test
    fun `a preset survives the json round trip`() {
        val source = chain()
        source[1].parameters[0].value = -12f
        val preset = Preset.capture("Round", source.drop(1), source)

        val text = Json.write(preset.toJsonValue())
        val restored = Preset.fromJsonValue(Json.parse(text))

        assertEquals(preset, restored)
    }

    /**
     * The capture a sound was made with travels in the preset.
     *
     * A path is the one machine-specific thing a preset carries. It is carried because the
     * alternative is worse: restoring the amp's knobs but not the amp is not the sound that was
     * saved. Per preset, so two presets naming the same capture can point at different files.
     */
    @Test
    fun `the files a sound was made with survive the json round trip`() {
        val source = chain()
        val preset = Preset.capture(
            "With amp",
            source.drop(1),
            source,
            files = mapOf(
                "NAM" to Preset.FileRef("GOAT.nam", """C:\Users\someone\Music\NAM\GOAT.nam"""),
                "IR" to Preset.FileRef("4x12.wav", """C:\Users\someone\Music\IR\4x12.wav"""),
            ),
        )

        val restored = Preset.fromJsonValue(Json.parse(Json.write(preset.toJsonValue())))

        assertEquals(preset, restored)
        assertEquals("GOAT.nam", restored!!.files["NAM"]?.name)
        assertEquals("""C:\Users\someone\Music\IR\4x12.wav""", restored.files["IR"]?.path)
    }

    @Test
    fun `two presets can name the same file at different paths`() {
        val source = chain()
        val live = mapOf("NAM" to Preset.FileRef("GOAT.nam", "/amps/live/GOAT.nam"))
        val studio = mapOf("NAM" to Preset.FileRef("GOAT.nam", "/amps/studio/GOAT.nam"))
        val one = Preset.capture("A", source.drop(1), source, live)
        val two = Preset.capture("B", source.drop(1), source, studio)

        assertEquals(
            "/amps/live/GOAT.nam",
            Preset.fromJsonValue(Json.parse(Json.write(one.toJsonValue())))!!.files["NAM"]?.path,
        )
        assertEquals(
            "/amps/studio/GOAT.nam",
            Preset.fromJsonValue(Json.parse(Json.write(two.toJsonValue())))!!.files["NAM"]?.path,
        )
    }

    @Test
    fun `a preset whose modules load no files writes nothing about them`() {
        val source = chain()
        val preset = Preset.capture("No amp", source.drop(1), source)

        val text = Json.write(preset.toJsonValue())

        // Absent rather than empty: a preset with no files has to read exactly as it always did, or
        // every file written before this existed becomes one with a new key missing from it.
        assertTrue(!text.contains("\"files\""), text)
        assertEquals(emptyMap(), Preset.fromJsonValue(Json.parse(text))!!.files)
    }

    @Test
    fun `a preset saved before files were recorded still loads`() {
        val older = """
            {"name":"Older","order":["Gain"],"enabled":{"Gain":true},"values":{"Gain.Gain":3.0}}
        """.trimIndent()

        val restored = Preset.fromJsonValue(Json.parse(older))!!

        assertEquals("Older", restored.name)
        assertEquals(emptyMap(), restored.files, "no files recorded, and that is not an error")
        assertEquals(3f, restored.values["Gain.Gain"])
    }

    /**
     * 1.0.17 wrote the NAM capture under its own `nam` key, before the per-effect map existed.
     * Presets written by that build have to keep working — they are read, not migrated, and pick up
     * the current shape the next time they are saved.
     */
    @Test
    fun `a preset from the build that stored only the nam capture still loads it`() {
        val previous = """
            {"name":"Older","order":["Gain"],"enabled":{},"values":{},
             "nam":{"name":"GOAT.nam","path":"/amps/GOAT.nam"}}
        """.trimIndent()

        val restored = Preset.fromJsonValue(Json.parse(previous))!!

        assertEquals("GOAT.nam", restored.files["NAM"]?.name)
        assertEquals("/amps/GOAT.nam", restored.files["NAM"]?.path)
    }

    @Test
    fun `an older preset still loads after a new effect is added`() {
        // Saved before "Reverb" existed: it mentions four effects, the chain now has five.
        val old = Preset(
            name = "Legacy",
            order = listOf("Delay", "Overdrive", "Gain"),
            enabled = mapOf("Gain" to true),
            values = mapOf("Gain.Gain" to 3f),
        )
        val target = chain()
        val order = old.applyTo(target)

        assertEquals(target.size, order.size, "nothing was dropped")
        assertEquals(listOf("Delay", "Overdrive", "Gain"), order.take(3).map { it.name })
        assertTrue(order.any { it.name == "Reverb" }, "the unmentioned effect is still present")
        assertEquals(3f, target.first { it.name == "Gain" }.parameters[0].value)
    }

    @Test
    fun `an effect the preset never heard of is switched off`() {
        // This is what keeps old presets honest as modules are added. A compressor that was on
        // before the load must not survive a preset that says nothing about compressors.
        val target = chain() + listOf(Compressor().apply { enabled = true }, Gate().apply { enabled = true })
        val old = Preset("Legacy", listOf("Gain"), mapOf("Gain" to true), emptyMap())

        old.applyTo(target)

        assertTrue(!target.first { it.name == "Compressor" }.enabled, "compressor off")
        assertTrue(!target.first { it.name == "Gate" }.enabled, "gate off")
        assertTrue(target.first { it.name == "Gain" }.enabled, "the mentioned one is still on")
    }

    @Test
    fun `an unmentioned master stays on, because off there means silence`() {
        val target = chain()
        val master = target.first { it.name == "Master" }
        master.enabled = true

        Preset("NoMaster", listOf("Gain"), mapOf("Gain" to true), emptyMap()).applyTo(target)

        assertTrue(master.enabled, "a preset that forgot the master must not load as a muted app")
    }

    @Test
    fun `parameters the preset omits go back to their defaults`() {
        // Otherwise loading preset A then B leaves B carrying A's settings for anything B is silent
        // about, and the same preset sounds different depending on what came before it.
        val target = chain()
        val delay = target.first { it.name == "Delay" }
        val time = delay.parameters.first { it.name == "Time" }
        time.value = 900f

        Preset("Quiet", listOf("Delay"), mapOf("Delay" to true), emptyMap()).applyTo(target)

        assertEquals(time.default, time.value, "reset rather than left as it was")
    }

    @Test
    fun `unknown names in a preset are ignored rather than fatal`() {
        val preset = Preset("Ghost", listOf("Chorus", "Gain"), mapOf("Chorus" to true), mapOf("Chorus.Mix" to 1f))
        val target = chain()
        val order = preset.applyTo(target)
        assertEquals(target.size, order.size)
        assertEquals("Gain", order.first().name)
    }
}

class PresetStoreTest {

    private val directory: Path = Files.createTempDirectory("omega-presets")
    private val store = PresetStore(directory)

    private fun preset(name: String) =
        Preset(name, listOf("Gain"), mapOf("Gain" to true), mapOf("Gain.Gain" to 1f))

    @AfterTest
    fun cleanUp() {
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    /**
     * A settings file beside the presets is not a broken preset.
     *
     * This is a regression test for a real one: `config.json` was written into the presets folder,
     * read as a sound, and reported to the user as a preset that would not load.
     */
    @Test
    fun `a stray config file is not mistaken for a preset`() {
        store.save(preset("Real"))
        Files.write(
            directory.resolve("config.json"),
            """{"recordingFolder":"D:\\Takes"}""".toByteArray(),
        )

        val listing = store.list()

        assertEquals(listOf("Real"), listing.presets.map { it.name })
        assertTrue(listing.failures.isEmpty(), "config.json was reported as a failure: ${listing.failures}")
    }

    @Test
    fun `each preset gets its own file`() {
        store.save(preset("One"))
        store.save(preset("Two"))

        val files = Files.list(directory).use { it.toList() }.map { it.fileName.toString() }.sorted()
        assertEquals(listOf("One.json", "Two.json"), files)
        assertEquals(listOf("One", "Two"), store.list().presets.map { it.name })
    }

    @Test
    fun `saving the same name again overwrites its file rather than adding one`() {
        store.save(preset("Same"))
        store.save(preset("Same").copy(values = mapOf("Gain.Gain" to 9f)))

        assertEquals(1, Files.list(directory).use { it.toList() }.size)
        assertEquals(9f, store.list().presets.single().values["Gain.Gain"])
    }

    @Test
    fun `names a filesystem would reject are cleaned, and collisions are numbered`() {
        store.save(preset("A/B"))
        store.save(preset("A:B"))

        val files = Files.list(directory).use { it.toList() }.map { it.fileName.toString() }.sorted()
        assertEquals(listOf("A_B (2).json", "A_B.json"), files)
        // Both keep their real names inside; only the file name was cleaned.
        assertEquals(listOf("A/B", "A:B"), store.list().presets.map { it.name }.sorted())
    }

    @Test
    fun `delete removes only that preset's file`() {
        store.save(preset("Keep"))
        store.save(preset("Drop"))
        val listing = store.list()

        store.delete(listing.presets.first { it.name == "Drop" })

        assertEquals(listOf("Keep"), store.list().presets.map { it.name })
    }

    @Test
    fun `one broken file costs one preset, not all of them`() {
        store.save(preset("Good"))
        Files.write(directory.resolve("broken.json"), "{ not json".toByteArray())

        val listing = store.list()

        assertEquals(listOf("Good"), listing.presets.map { it.name })
        assertEquals(1, listing.failures.size)
        assertEquals("broken.json", listing.failures.single().file.fileName.toString())
    }

    @Test
    fun `failures explain themselves rather than just failing`() {
        Files.write(directory.resolve("bad-json.json"), "{ \"name\": }".toByteArray())
        Files.write(directory.resolve("not-a-preset.json"), "{ \"unrelated\": 1 }".toByteArray())

        val reasons = store.list().failures.associate { it.file.fileName.toString() to it.reason }

        assertTrue(
            reasons.getValue("bad-json.json").contains("not valid JSON"),
            "said what was wrong: ${reasons["bad-json.json"]}",
        )
        assertTrue(
            reasons.getValue("bad-json.json").contains("offset"),
            "said where: ${reasons["bad-json.json"]}",
        )
        assertTrue(
            reasons.getValue("not-a-preset.json").contains("not a preset"),
            "distinguished valid JSON from a preset: ${reasons["not-a-preset.json"]}",
        )
    }

    @Test
    fun `readAll accepts the shapes an imported file might arrive in`() {
        val one = """{"name":"Solo","order":["Gain"],"enabled":{},"values":{"Gain.Gain":4.0}}"""

        val single = directory.resolve("single.json")
        Files.write(single, one.toByteArray())
        assertEquals(listOf("Solo"), PresetStore.readAll(single).map { it.name })
        assertEquals(4f, PresetStore.readAll(single).first().values["Gain.Gain"])

        // A bare array, for sending several at once.
        val array = directory.resolve("array.json")
        Files.write(array, "[$one]".toByteArray())
        assertEquals(listOf("Solo"), PresetStore.readAll(array).map { it.name })

        // The old single-file collection, so an existing file still imports.
        val wrapped = directory.resolve("wrapped.json")
        Files.write(wrapped, """{"format":1,"presets":[$one]}""".toByteArray())
        assertEquals(listOf("Solo"), PresetStore.readAll(wrapped).map { it.name })
    }

    @Test
    fun `readAll throws with a usable reason`() {
        val garbage = directory.resolve("garbage.json")
        Files.write(garbage, "not json at all".toByteArray())
        val malformed = assertFailsWith<PresetException> { PresetStore.readAll(garbage) }
        assertTrue(malformed.message!!.contains("not valid JSON"), malformed.message!!)

        val other = directory.resolve("other.json")
        Files.write(other, """{"format":1,"unrelated":[1,2,3]}""".toByteArray())
        val wrongShape = assertFailsWith<PresetException> { PresetStore.readAll(other) }
        assertTrue(wrongShape.message!!.contains("not a preset"), wrongShape.message!!)

        val absent = assertFailsWith<PresetException> { PresetStore.readAll(directory.resolve("nope.json")) }
        assertTrue(absent.message!!.contains("could not be read"), absent.message!!)
    }

    @Test
    fun `an older single-file collection is split into one file each`() {
        Files.write(
            directory.resolve("presets.json"),
            """{"format":1,"presets":[
                 {"name":"Alpha","order":["Gain"],"enabled":{},"values":{}},
                 {"name":"Beta","order":["Delay"],"enabled":{},"values":{}}
               ]}""".toByteArray(),
        )

        val listing = store.list()

        assertEquals(listOf("Alpha", "Beta"), listing.presets.map { it.name })
        assertTrue(Files.exists(directory.resolve("Alpha.json")))
        assertTrue(Files.exists(directory.resolve("Beta.json")))
        // The original is kept, renamed, rather than deleted.
        assertTrue(Files.exists(directory.resolve("presets.json.migrated")))
        assertTrue(!Files.exists(directory.resolve("presets.json")))
    }
}
