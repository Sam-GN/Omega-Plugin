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
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a user keeps carries no version, because a shortcut needs a path that survives an update and
 * a name that survives an update cannot truthfully state one. Read from the build itself, since a
 * name only ever goes wrong there.
 */
class ShippedNamesTest {

    private val build = Files.readString(Path.of("build.gradle.kts"))
    private val workflows = listOf("build.yml", "release.yml")
        .map { Files.readString(Path.of(".github/workflows", it)) }

    @Test
    fun `the AppImage is one fixed file`() {
        assertContains(build, "\"distributions/Omega-Plugin-Linux.AppImage\"")
        assertFalse(Regex("""Omega-Plugin-\$\{[^}]*}-Linux""").containsMatchIn(build))
    }

    @Test
    fun `the Windows folder and the bundled jar are fixed names`() {
        assertContains(build, "val bundleFolder = \"OmegaPlugin\"")
        assertContains(build, "val bundleJar = \"OmegaPlugin.jar\"")
        assertContains(build, "into(bundleFolder)")
        assertContains(build, "\"--main-jar\", bundleJar")
        assertFalse(build.contains("into(\"OmegaPlugin-\$"), "a versioned folder inside the zip")
    }

    @Test
    fun `every bundle takes the jar under the fixed name`() {
        val taken = Regex("""from\(fatJar\)( \{ rename \{ bundleJar } })?""").findAll(build).toList()
        assertTrue(taken.isNotEmpty())
        assertTrue(taken.all { it.groupValues[1].isNotEmpty() }, "a bundle took the versioned jar")
    }

    @Test
    fun `the workflows look for the file the build makes`() {
        for (workflow in workflows) {
            assertContains(workflow, "Omega-Plugin-Linux.AppImage")
            assertFalse(workflow.contains("Omega-Plugin-*-Linux.AppImage"), "a glob the name no longer matches")
        }
    }
}
