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
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deleting is the dangerous half of this, so most of these are about what must survive. */
class LeftoversTest {

    private val temp: Path = Files.createTempDirectory("omega-leftovers")
    private val hash = "0123456789abcdef0123456789abcdef"
    private val now = 1_000_000_000_000L
    private val old = now - Leftovers.PARTIAL_AGE_MILLIS - 1

    @AfterTest
    fun clean() {
        Files.walk(temp).use { paths ->
            paths.forEach { it.toFile().setWritable(true, true) }
        }
        Files.walk(temp).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } }
    }

    private fun file(name: String, modified: Long = old): Path {
        val path = temp.resolve(name)
        Files.writeString(path, "x")
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified))
        return path
    }

    private fun copy(hashed: String = hash, jar: String = "OmegaPlugin.jar", desktop: String = "omega-plugin.desktop"): Path {
        val folder = temp.resolve("appimage_extracted_$hashed")
        Files.createDirectories(folder.resolve("lib"))
        Files.writeString(folder.resolve("lib").resolve(jar), "jar")
        Files.writeString(folder.resolve(desktop), "entry")
        return folder
    }

    @Test
    fun `an abandoned download and a staged image are removed`() {
        val part = file("Omega-Plugin-Linux.AppImage.part")
        val staged = file(".Omega-Plugin-Linux.AppImage.new")
        val zip = file("Omega-Plugin-2.0.0-beta-Windows.zip.part")
        assertEquals(3, Leftovers.sweepPartials(listOf(temp), now))
        assertFalse(Files.exists(part) || Files.exists(staged) || Files.exists(zip))
    }

    @Test
    fun `a download still being written is left for the copy writing it`() {
        val part = file("Omega-Plugin-Linux.AppImage.part", now - 1000)
        assertEquals(0, Leftovers.sweepPartials(listOf(temp), now))
        assertTrue(Files.exists(part))
    }

    @Test
    fun `the program and anything else in its folder are left alone`() {
        val keep = listOf(
            "Omega-Plugin-Linux.AppImage", "Omega-Plugin-2.0.0-beta-Windows.zip", "notes.part", "other-app.new",
            "Omega-Plugin-settings.txt", "Omega.part",
        ).map { file(it) }
        assertEquals(0, Leftovers.sweepPartials(listOf(temp), now))
        assertTrue(keep.all { Files.exists(it) })
    }

    @Test
    fun `a folder that does not exist costs nothing`() {
        assertEquals(0, Leftovers.sweepPartials(listOf(temp.resolve("gone")), now))
        assertEquals(0, Leftovers.sweepExtracted(temp.resolve("gone"), null) { false })
    }

    @Test
    fun `an old extracted copy of this program is removed, read-only folders and all`() {
        val folder = copy()
        val legal = Files.createDirectories(folder.resolve("runtime/legal/java.base"))
        Files.writeString(legal.resolve("LICENSE"), "text")
        legal.toFile().setWritable(false, false)
        assertEquals(1, Leftovers.sweepExtracted(temp, null) { false })
        assertFalse(Files.exists(folder))
    }

    @Test
    fun `a copy from before the jar lost its version is recognised`() {
        val folder = copy(jar = "OmegaPlugin-1.9.3-beta-all.jar")
        assertEquals(1, Leftovers.sweepExtracted(temp, null) { false })
        assertFalse(Files.exists(folder))
    }

    @Test
    fun `the copy this program is running from stays`() {
        val folder = copy()
        assertEquals(0, Leftovers.sweepExtracted(temp, folder) { false })
        assertTrue(Files.exists(folder))
    }

    @Test
    fun `a copy another running program is using stays`() {
        val folder = copy()
        assertEquals(0, Leftovers.sweepExtracted(temp, null) { true })
        assertTrue(Files.exists(folder))
    }

    @Test
    fun `another program's extracted AppImage is never touched`() {
        val other = copy(jar = "SomethingElse.jar")
        val other2 = copy("fedcba9876543210fedcba9876543210", desktop = "other-app.desktop")
        assertEquals(0, Leftovers.sweepExtracted(temp, null) { false })
        assertTrue(Files.exists(other) && Files.exists(other2))
    }

    @Test
    fun `a name that only resembles the runtime's is not ours`() {
        val folder = copy("not-a-hash")
        assertFalse(Leftovers.isOurs(folder))
    }

    @Test
    fun `a link in a copy is removed without following it`() {
        val outside = file("outside.txt")
        val folder = copy()
        Files.createSymbolicLink(folder.resolve("link"), outside)
        assertEquals(1, Leftovers.sweepExtracted(temp, null) { false })
        assertTrue(Files.exists(outside))
    }
}
