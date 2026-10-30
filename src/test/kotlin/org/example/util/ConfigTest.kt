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
package org.example.util

import java.nio.charset.StandardCharsets
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
 * The settings file, which has one job beyond storing a value: **never cost anyone a session**.
 *
 * A preference that fails to load is an inconvenience; an application that will not start because
 * of one is a fault. So every case below that could reasonably throw must instead fall back to the
 * defaults and carry on.
 */
class ConfigTest {

    private val folder: Path = Files.createTempDirectory("omega-config")
    private val file: Path = folder.resolve("config.json")

    @AfterTest
    fun cleanUp() {
        Files.walk(folder).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    /**
     * Settings go in a `config` subfolder, not beside the presets.
     *
     * They used to sit next to them, and the preset store lists every `*.json` in its folder — so
     * `config.json` was read as a sound, failed to parse, and was reported as a preset that would
     * not load. Keeping the two in separate folders is what makes that impossible rather than
     * merely unlikely.
     */
    @Test
    fun `settings live in their own folder, away from the presets`() {
        val config = Config.at(folder)
        config.recordingFolder = """D:\Takes"""

        assertTrue(
            Files.isRegularFile(folder.resolve("config").resolve("config.json")),
            "settings should be written inside a config folder",
        )
        assertTrue(
            Files.list(folder).use { stream ->
                stream.noneMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
            },
            "nothing that looks like a preset should be left in the presets folder",
        )
        assertEquals("""D:\Takes""", Config.at(folder).recordingFolder)
    }

    @Test
    fun `a setting survives being written and read back`() {
        Config(file).apply { recordingFolder = """D:\Takes""" }

        val reloaded = Config(file).apply { load() }

        assertEquals("""D:\Takes""", reloaded.recordingFolder)
    }

    @Test
    fun `setting it to null clears it, so the default applies again`() {
        val config = Config(file)
        config.recordingFolder = """D:\Takes"""
        config.recordingFolder = null

        assertNull(Config(file).apply { load() }.recordingFolder)
    }

    /**
     * A key this version has never heard of has to survive a load-and-save.
     *
     * Otherwise a setting added later is silently deleted the first time an older build writes the
     * file — the sort of data loss that is only noticed long after the cause.
     */
    @Test
    fun `keys it does not recognise are kept`() {
        Files.write(
            file,
            """{"recordingFolder":"D:\\Takes","somethingLater":42.0,"aFlag":true}"""
                .toByteArray(StandardCharsets.UTF_8),
        )

        // Load, change one thing, write the whole file back.
        Config(file).apply {
            load()
            recordingFolder = """D:\Other"""
        }

        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assertTrue(text.contains("somethingLater"), text)
        assertTrue(text.contains("aFlag"), text)
        assertTrue(text.contains("""D:\\Other"""), text)
    }

    @Test
    fun `a missing file is not an error`() {
        val config = Config(folder.resolve("nothing-here.json"))
        config.load()
        assertNull(config.recordingFolder)
    }

    @Test
    fun `a corrupt file falls back to defaults rather than failing`() {
        Files.write(file, "{ this is not json".toByteArray(StandardCharsets.UTF_8))

        val config = Config(file)
        config.load()

        assertNull(config.recordingFolder, "a broken settings file must read as no settings")
        // And it must still be usable afterwards — the bad file is simply overwritten.
        config.recordingFolder = """D:\Takes"""
        assertEquals("""D:\Takes""", Config(file).apply { load() }.recordingFolder)
    }

    @Test
    fun `a value of the wrong type reads as absent`() {
        Files.write(file, """{"recordingFolder":123.0}""".toByteArray(StandardCharsets.UTF_8))

        assertNull(Config(file).apply { load() }.recordingFolder)
    }

    /**
     * The logging switch has to survive a restart, in both directions.
     *
     * That is the entire point of storing it: the faults worth a log are the intermittent ones, so
     * "turn it on and use the thing for a few days" must be possible. A switch that reset each
     * launch would collect nothing but first-minute logs — and one that could not be turned back
     * off would keep writing to a disk after the user had finished with it.
     */
    @Test
    fun `the logging switch survives a restart in both directions`() {
        assertFalse(
            Config(file).apply { load() }.loggingEnabled,
            "logging must default to off — it is a diagnostic, not a feature",
        )

        Config(file).apply { load() }.loggingEnabled = true
        assertTrue(Config(file).apply { load() }.loggingEnabled, "on should have persisted")

        Config(file).apply { load() }.loggingEnabled = false
        assertFalse(Config(file).apply { load() }.loggingEnabled, "off should have persisted too")
    }

    /**
     * The default matters more than the round trip here.
     *
     * A board that opened with pedals missing, on an installation whose owner had forgotten the
     * setting existed, reads as pedals that have been lost rather than as a view preference.
     */
    @Test
    fun `hiding switched-off pedals survives a restart, and defaults to showing them`() {
        assertFalse(
            Config(file).apply { load() }.hideDisabledModules,
            "a fresh installation must show the whole board",
        )

        Config(file).apply { load() }.hideDisabledModules = true
        assertTrue(Config(file).apply { load() }.hideDisabledModules, "hiding should have persisted")

        Config(file).apply { load() }.hideDisabledModules = false
        assertFalse(
            Config(file).apply { load() }.hideDisabledModules,
            "and so should turning it back off",
        )
    }

    /** Two boards, two eyes: closing one must not close the other, in either direction. */
    @Test
    fun `each board's eye is remembered on its own`() {
        assertFalse(Config(file).apply { load() }.hideDisabledModulesSecond, "the second board shows everything too")

        Config(file).apply { load() }.hideDisabledModules = true
        val afterFirst = Config(file).apply { load() }
        assertTrue(afterFirst.hideDisabledModules)
        assertFalse(afterFirst.hideDisabledModulesSecond, "closing the first eye must leave the second open")

        Config(file).apply { load() }.hideDisabledModulesSecond = true
        Config(file).apply { load() }.hideDisabledModules = false
        val afterSecond = Config(file).apply { load() }
        assertTrue(afterSecond.hideDisabledModulesSecond, "the second eye should have persisted")
        assertFalse(afterSecond.hideDisabledModules, "and opening the first must leave it closed")
    }

    /**
     * A fresh installation must leave a file on disk before anything has been changed.
     *
     * It used to be written lazily, by the first setting somebody touched, and that defeats the one
     * job it has for a user whose application will not start: logging is off by default and its
     * switch is inside a window, so the only way to capture a failed launch is to edit this file by
     * hand — and there was no file to edit. The key has to be in it, too, spelled the way the
     * program spells it. Nobody can be expected to guess a name out of an empty `{}`.
     */
    @Test
    fun `a fresh installation gets a file on disk with the logging key already in it`() {
        val config = Config.at(folder)
        val written = folder.resolve("config").resolve("config.json")

        assertTrue(Files.isRegularFile(written), "first load should have created the file")
        assertTrue(
            String(Files.readAllBytes(written), StandardCharsets.UTF_8).contains("loggingEnabled"),
            "the key a user is sent to change has to already be in the file",
        )
        assertFalse(config.loggingEnabled, "and it has to be created switched off")
    }

    /**
     * The notice fires on the run that creates the file, and on no other.
     *
     * Both halves matter. Without it, the path is never told to the one person who needs it; printed
     * every launch, it becomes noise and is unread by the time it counts.
     */
    @Test
    fun `the first-run notice fires only on the run that creates the file`() {
        val first = Config.at(folder)
        val notice = first.firstRunNotice

        assertNotNull(notice, "the run that creates the settings file should say so")
        // It has to be openable from what it says: a full path, and the key to change once there.
        assertTrue(
            notice.contains(folder.resolve("config").resolve("config.json").toAbsolutePath().toString()),
            notice,
        )
        assertTrue(notice.contains("loggingEnabled"), notice)

        assertNull(
            Config.at(folder).firstRunNotice,
            "a second launch has nothing new to say and must stay quiet",
        )
    }

    @Test
    fun `the folder is created if it does not exist`() {
        val nested = folder.resolve("a").resolve("b").resolve("config.json")

        Config(nested).recordingFolder = """D:\Takes"""

        assertTrue(Files.isRegularFile(nested), "save should have created the folders it needed")
    }
}
