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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileNamesTest {

    private val folder: Path = Files.createTempDirectory("omega-names")

    @AfterTest
    fun clean() {
        Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } }
    }

    @Test
    fun `a name cannot steer a file into another folder`() {
        for (hostile in listOf("x/../../victim", "..\\..\\Desktop\\song", "/etc/passwd", "C:\\Users\\me\\a", "a:b")) {
            val part = FileNames.part(hostile)
            assertFalse(part.contains('/') || part.contains('\\') || part.contains(':'), "$hostile became $part")
            assertEquals(folder, folder.resolve("$part.wav").parent, "$hostile stays in the folder")
        }
    }

    @Test
    fun `an ordinary preset name is kept as it reads`() {
        assertEquals("Clean & Crunch (2), Bob's", FileNames.part("Clean & Crunch (2), Bob's"))
        assertEquals("Ätherisch Blues", FileNames.part("Ätherisch Blues"))
    }

    @Test
    fun `a free name is the name itself, then numbered, and never one in use`() {
        assertEquals(folder.resolve("a.AppImage"), FileNames.free(folder, "a.AppImage"))
        Files.writeString(folder.resolve("a.AppImage"), "kept")
        assertEquals(folder.resolve("a (2).AppImage"), FileNames.free(folder, "a.AppImage"))
        Files.writeString(folder.resolve("a (2).AppImage"), "kept")
        assertEquals(folder.resolve("a (3).AppImage"), FileNames.free(folder, "a.AppImage"))
    }

    @Test
    fun `a base standing for two files is free only when both are`() {
        Files.writeString(folder.resolve("take - drums.wav"), "kept")
        assertEquals("take (2)", FileNames.freeBase(folder, "take") { listOf("$it.wav", "$it - drums.wav") })
    }
}
