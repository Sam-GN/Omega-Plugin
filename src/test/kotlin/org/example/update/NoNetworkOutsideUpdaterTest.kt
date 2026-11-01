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
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The promise on the About dialog, checked mechanically: nothing outside `org.example.update` opens
 * a socket. Crude — it greps source for the names of the JDK's network types — and that is the
 * point. Nobody adds a quiet fetch without this turning red and a reviewer asking why.
 *
 * `java.net.URI` is allowed everywhere: parsing an address opens nothing, and the About dialog's
 * link hands one to the desktop's browser.
 */
class NoNetworkOutsideUpdaterTest {

    private val forbidden = listOf(
        "java.net.http",
        "HttpURLConnection",
        "HttpsURLConnection",
        "URLConnection",
        "openConnection",
        "openStream(",
        "java.net.Socket",
        "ServerSocket",
        "SocketChannel",
        "DatagramSocket",
    )

    @Test
    fun `only the update package names a network type`() {
        val root = Path.of("src/main/kotlin")
        assertTrue(Files.isDirectory(root), "run from the project directory: $root")
        val offenders = Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .filter { !it.toString().replace('\\', '/').contains("/org/example/update/") }
                .flatMap { file ->
                    val text = file.readText()
                    forbidden.filter { text.contains(it) }.map { "$file mentions $it" }
                }
                .toList()
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `inside the package, only GitHubSource opens one`() {
        val folder = Path.of("src/main/kotlin/org/example/update")
        val offenders = Files.list(folder).use { paths ->
            paths.asSequence()
                .filter { it.fileName.toString() != "GitHubSource.kt" && it.toString().endsWith(".kt") }
                .filter { file -> val text = file.readText(); forbidden.any { text.contains(it) } }
                .map { it.toString() }
                .toList()
        }
        assertEquals(emptyList(), offenders)
    }
}
