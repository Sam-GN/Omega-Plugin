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
package org.example.ui

import java.io.File
import java.util.Properties
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The third-party table, checked against what actually ships rather than against what the build
 * file says.
 *
 * This exists because the table was wrong: `org.jetbrains:annotations` is inside the fat jar and was
 * named nowhere in the notice. It got there transitively under the Kotlin standard library, so every
 * way of checking that starts from `build.gradle.kts`'s `dependencies` block — which is how a human
 * checks — would have missed it, and did, for every version up to 1.0.47-alpha.
 *
 * So the assertion is made against the **resolved runtime classpath**, handed in by Gradle as
 * `omega.runtimeClasspath`. Each jar on it is asked what it is by reading its own Maven coordinates
 * out of `META-INF/maven/.../pom.properties`; the test then requires the artifact to be named in
 * [Licences.full]. A new transitive dependency therefore fails the build until somebody has looked
 * at its licence, which is the only point at which that decision can be made honestly.
 *
 * It deliberately does **not** hardcode the current list. A test that says "expect exactly Kotlin,
 * annotations, JNA and jna-platform" passes forever and catches nothing; this one is driven by the
 * classpath, so it changes its own expectations when the classpath changes.
 */
class LicencesTest {

    /** How an artifact is named in the notice: by artifactId, ignoring case and `-`/`.` styling. */
    private fun mentions(text: String, artifactId: String): Boolean {
        val needle = artifactId.replace('-', ' ').replace('.', ' ').lowercase()
        return text.replace('-', ' ').replace('.', ' ').lowercase().contains(needle)
    }

    /**
     * Reads a jar's own Maven coordinates. Returns null for a jar without them — the JDK's own
     * modules and anything built locally have no `pom.properties`, and they are not third-party.
     */
    private fun coordinates(jar: File): Pair<String, String>? {
        if (!jar.isFile || !jar.name.endsWith(".jar")) return null
        JarFile(jar).use { open ->
            val entry = open.entries().asSequence().firstOrNull {
                it.name.startsWith("META-INF/maven/") && it.name.endsWith("/pom.properties")
            } ?: return null
            val props = Properties().apply { open.getInputStream(entry).use { load(it) } }
            val group = props.getProperty("groupId") ?: return null
            val artifact = props.getProperty("artifactId") ?: return null
            return group to artifact
        }
    }

    private fun runtimeJars(): List<File> =
        System.getProperty("omega.runtimeClasspath")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)

    @Test
    fun `every shipped third-party artifact is named in the notice`() {
        val jars = runtimeJars()
        // If this fires, the Gradle wiring that supplies the property has been removed and the test
        // below would silently pass on an empty list — the exact failure mode it exists to prevent.
        assertTrue(jars.isNotEmpty(), "omega.runtimeClasspath was not supplied by the build")

        val notice = Licences.full().joinToString("\n")
        val missing = jars.mapNotNull { coordinates(it) }
            .filterNot { (_, artifact) -> mentions(notice, artifact) }

        assertTrue(
            missing.isEmpty(),
            "These ship inside the jar but are not named in Licences.THIRD_PARTY: " +
                missing.joinToString { (g, a) -> "$g:$a" } +
                ". Add each one with its licence — and check that licence is GPLv3-or-later " +
                "compatible before you do.",
        )
    }

    /**
     * The stdlib pulls in `annotations` and nothing declares it. Pinned separately from the sweep
     * above so that the specific regression has a specific failure message, and so that removing
     * the entry by hand cannot be mistaken for the dependency having gone away.
     */
    @Test
    fun `the transitive annotations dependency is still present and still named`() {
        val coords = runtimeJars().mapNotNull { coordinates(it) }
        assertContains(
            coords,
            "org.jetbrains" to "annotations",
            "org.jetbrains:annotations is no longer on the runtime classpath. If the Kotlin " +
                "stdlib genuinely stopped pulling it in, delete its entry from Licences and " +
                "README; do not delete this test.",
        )
        assertTrue(mentions(Licences.full().joinToString("\n"), "annotations"))
    }

    /**
     * The one string distribution depends on, asserted so it cannot regress quietly.
     *
     * GPLv3 §6 wants the Corresponding Source, and a URL that does not resolve offers none.
     * This was a placeholder for the whole of the program's life before 1.0.60-alpha — known issue
     * §6, now closed. The shape is checked rather than the exact repository, so that moving the
     * repository is one edit and not two; what cannot come back is a placeholder.
     */
    @Test
    fun `the source URL is a real repository and not a placeholder`() {
        val url = Licences.SOURCE_URL
        assertTrue(
            !url.contains('<') && !url.contains('>'),
            "SOURCE_URL is a placeholder again. GPLv3 §6 is not satisfied by a URL that cannot be " +
                "opened; it must name the repository the binary's source is published on.",
        )
        assertTrue(url.startsWith("https://github.com/"), "SOURCE_URL must be a resolvable https URL")
        assertTrue(
            url.removePrefix("https://github.com/").split('/').filter { it.isNotBlank() }.size == 2,
            "SOURCE_URL must name an account and a repository, not just a host: was \"$url\"",
        )
    }

    /** The election and the Steinberg statement are the lines a later cleanup is likeliest to drop. */
    @Test
    fun `the JNA licence election is stated`() {
        val notice = Licences.full().joinToString("\n")
        assertTrue(notice.contains("LGPL 2.1"), "the LGPL 2.1 election must be stated verbatim")
        assertTrue(
            notice.contains("Steinberg"),
            "the notice must record that the Steinberg ASIO SDK is not used",
        )
    }

    /** About is the short form, but §4 still wants the copyright and the disclaimer on it. */
    @Test
    fun `about carries the copyright and the warranty disclaimer`() {
        val about = Licences.about("1.0.0-test").joinToString("\n")
        assertContains(about, "Version 1.0.0-test")
        assertContains(about, Licences.COPYRIGHT)
        assertContains(about, "NO WARRANTY")
        assertEquals(true, Licences.about(null).none { it.startsWith("Version") })
    }
}
