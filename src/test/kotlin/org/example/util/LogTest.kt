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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The troubleshooting log.
 *
 * Two properties matter more than the formatting, and both are asserted here rather than assumed:
 * **off writes nothing at all** (a diagnostic with a cost when idle is one people leave off), and
 * **turning off flushes** — the log is collected immediately after the switch is moved, so a
 * buffered tail lost at that exact moment would lose the part worth reading.
 *
 * [Log] is an object, so these tests share one instance and each must leave it off. The `AfterTest`
 * does that unconditionally rather than at the end of each test body, where an assertion failure
 * would skip it and take the rest of the suite with it.
 */
class LogTest {

    private val folder: Path = Files.createTempDirectory("omega-log")
    private val logs: Path = folder.resolve("logs")
    private val file: Path = logs.resolve("omega.txt")

    @AfterTest
    fun cleanUp() {
        Log.setEnabled(false)
        Files.walk(folder).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
    }

    private fun text(): String = String(Files.readAllBytes(file), StandardCharsets.UTF_8)

    @Test
    fun `off writes nothing, not even a folder`() {
        Log.installIn(folder)
        Log.info("test", "this must not be written")
        Log.error("test", "nor this", RuntimeException("nor its trace"))

        assertFalse(Log.enabled, "logging must default to off")
        assertFalse(
            Files.exists(logs),
            "a switched-off log must not create its folder — off means nothing on disk",
        )
    }

    @Test
    fun `on writes the line, and turning off flushes it`() {
        Log.installIn(folder)
        Log.setEnabled(true)
        Log.info("test", "a distinctive line")
        // Not a sleep: setEnabled(false) drains the queue and joins the writer, which is the
        // guarantee being tested. If it were asynchronous the assertion below would be a race.
        Log.setEnabled(false)

        assertTrue(Files.isRegularFile(file), "omega.txt should have been written")
        assertContains(text(), "a distinctive line")
    }

    @Test
    fun `the banner records the machine, because a user cannot be asked to`() {
        Log.installIn(folder)
        Log.setEnabled(true)
        Log.setEnabled(false)

        val written = text()
        // Not the exact strings — those are the host's. What is asserted is that the three facts
        // every audio-fault report turns on are present without anyone having been asked for them.
        assertContains(written, System.getProperty("os.name"))
        assertContains(written, System.getProperty("java.version"))
        assertContains(written, "collector:")
    }

    @Test
    fun `an exception brings its stack trace`() {
        Log.installIn(folder)
        Log.setEnabled(true)
        Log.error("test", "something failed", IllegalStateException("the cause"))
        Log.setEnabled(false)

        val written = text()
        assertContains(written, "something failed")
        assertContains(written, "IllegalStateException")
        // The frame, not just the class name: a driver failure's message is routinely a hex code and
        // the trace is the only part that says where it came from.
        assertContains(written, "at org.example.util.LogTest")
    }

    @Test
    fun `switching off and on again appends rather than truncating`() {
        Log.installIn(folder)
        Log.setEnabled(true)
        Log.info("test", "from the first session")
        Log.setEnabled(false)

        Log.setEnabled(true)
        Log.info("test", "from the second session")
        Log.setEnabled(false)

        val written = text()
        // The setting survives a restart, so the file has to as well. A user who turns logging on,
        // reproduces a fault over three days and then sends the file must not find only day three.
        assertContains(written, "from the first session")
        assertContains(written, "from the second session")
    }

    @Test
    fun `writing before installIn is discarded rather than improvised`() {
        // Log is an object, so an earlier test in this class has already installed a folder on it.
        // Startup order should never decide where a file lands: a call arriving before the folder is
        // known is dropped, not written somewhere nobody will look.
        Log.resetForTests()
        Log.setEnabled(true)
        Log.info("test", "nowhere to go")

        assertFalse(Log.enabled, "setEnabled must not take effect without a folder")
        assertEquals(null, Log.file, "there is no file until a folder has been named")
    }

    @Test
    fun `the level and tag are on the line, so it can be read and filtered`() {
        Log.installIn(folder)
        Log.setEnabled(true)
        Log.warn("audio", "a warning")
        Log.setEnabled(false)

        val line = text().lineSequence().first { it.contains("a warning") }
        assertContains(line, "WARN")
        assertContains(line, "audio")
        // Timestamped to the millisecond, which is what makes "it crackled at 14:32" line up with
        // the log at all.
        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s""").containsMatchIn(line),
            "every line should start with a millisecond timestamp, got: $line",
        )
    }
}
