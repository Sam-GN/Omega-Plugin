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
package org.example.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseNotesTest {

    @Test
    fun `a heading shows as its words`() {
        assertEquals("Fixed - a thing", ReleaseNotes.plain("### Fixed - a thing"))
    }

    @Test
    fun `a sentence wrapped by the changelog is one line again`() {
        assertEquals("The first half and the second half.", ReleaseNotes.plain("The first half\nand the second half."))
    }

    @Test
    fun `a paragraph break stays a paragraph break`() {
        assertEquals("One.\n\nTwo.", ReleaseNotes.plain("One.\n\n\n\nTwo."))
    }

    @Test
    fun `a bullet is kept, with its wrapped lines joined to it`() {
        val notes = "- **First.** It goes on\n  to here.\n- Second."
        assertEquals("• First. It goes on to here.\n• Second.", ReleaseNotes.plain(notes))
    }

    @Test
    fun `a line after a heading starts its own entry`() {
        assertEquals("Added\nText.", ReleaseNotes.plain("### Added\nText."))
    }

    @Test
    fun `marks are removed, even where one opens on a line and closes on the next`() {
        assertEquals("a bold phrase here", ReleaseNotes.plain("a **bold\nphrase** here"))
        assertEquals("the first it can update to is", ReleaseNotes.plain("the first it can update *to* is"))
        assertEquals("the --version flag", ReleaseNotes.plain("the `--version` flag"))
        assertEquals("see the manual now", ReleaseNotes.plain("see [the manual](docs/manual.md) now"))
    }

    @Test
    fun `what is between backticks is left as written`() {
        assertEquals("delete app\\*.jar and **this**", ReleaseNotes.plain("delete `app\\*.jar` and `**this**`"))
    }

    @Test
    fun `an unpaired star is not emphasis`() {
        assertEquals("2 * 3 is 6", ReleaseNotes.plain("2 * 3 is 6"))
    }

    @Test
    fun `windows line ends, a rule and trailing space leave nothing behind`() {
        assertEquals("One.\n\nTwo.", ReleaseNotes.plain("One.\r\n\r\n---\r\n\r\nTwo.\r\n\r\n"))
        assertEquals("", ReleaseNotes.plain("  \n"))
    }

    @Test
    fun `every section of the real changelog comes out clean`() {
        val sections = Files.readString(Path.of("CHANGELOG.md")).split(Regex("""(?m)^## \["""))
            .drop(1).map { it.substringAfter('\n').substringBefore("\n---\n") }
        assertTrue(sections.size > 5)
        for (section in sections) {
            val plain = ReleaseNotes.plain(section)
            val outsideCode = section.replace(Regex("`[^`]*`"), "")
            if (outsideCode.contains("**")) assertTrue(plain.length < section.length)
            assertFalse(plain.contains("`"), "a backtick in: ${plain.take(60)}")
            assertFalse(plain.lines().any { it.startsWith("#") }, "a heading mark in: ${plain.take(60)}")
            assertFalse(plain.lines().any { it.startsWith("- ") }, "an unconverted bullet in: ${plain.take(60)}")
        }
        assertTrue(sections.any { ReleaseNotes.plain(it).contains("• Linux: the file is") }, "bullets survive")
    }
}
