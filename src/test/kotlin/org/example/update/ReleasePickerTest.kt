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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Against `update/releases.json`: a list shaped like GitHub's, with the traps in it — a hot-fix
 * on an older line listed first, a tag that is not a version, and a draft.
 */
class ReleasePickerTest {

    private val json: String =
        checkNotNull(javaClass.getResourceAsStream("/update/releases.json")) { "canned releases missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val releases = ReleasePicker.parse(json)

    @Test
    fun `parses the published, versioned entries and nothing else`() {
        assertEquals(listOf("v1.7.9-beta", "v1.8.1-beta", "v1.8.0-beta"), releases.map { it.tag })
        assertTrue(releases.none { it.draft })
    }

    @Test
    fun `reads the assets with their sizes and urls`() {
        val release = releases.first { it.tag == "v1.8.1-beta" }
        assertEquals(3, release.assets.size)
        val zip = release.assets.first { it.name.endsWith("-Windows.zip") }
        assertEquals(61_500_000L, zip.size)
        assertTrue(zip.url.startsWith("https://github.com/Sam-GN/Omega-Plugin/releases/download/v1.8.1-beta/"))
        assertNotNull(release.checksums)
        assertEquals(Checksums.FILE_NAME, release.checksums!!.name)
    }

    @Test
    fun `the newest is chosen by version, not by position`() {
        assertEquals("v1.8.1-beta", ReleasePicker.newest(releases)?.tag)
    }

    @Test
    fun `an older running version is offered the newest, with this platform's asset`() {
        val running = Versions.parse("1.8.0-beta")!!
        val windows = assertIs<Verdict.Available>(ReleasePicker.pick(releases, running, Platform.WINDOWS))
        assertEquals("v1.8.1-beta", windows.release.tag)
        assertEquals("Omega-Plugin-1.8.1-beta-Windows.zip", windows.asset?.name)
        val linux = assertIs<Verdict.Available>(ReleasePicker.pick(releases, running, Platform.LINUX))
        assertEquals("Omega-Plugin-1.8.1-beta-Linux.AppImage", linux.asset?.name)
    }

    @Test
    fun `a release without this platform's asset is reported, not offered`() {
        val only = listOf(releases.first { it.tag == "v1.7.9-beta" })
        val verdict = assertIs<Verdict.Available>(ReleasePicker.pick(only, Versions.parse("1.7.8-beta"), Platform.LINUX))
        assertNull(verdict.asset)
        assertEquals("1.7.9-beta is available, but not for Linux yet.", Updates.headline(verdict, Platform.LINUX))
    }

    @Test
    fun `the running version being the newest is up to date`() {
        val verdict = ReleasePicker.pick(releases, Versions.parse("v1.8.1-beta"), Platform.WINDOWS)
        assertIs<Verdict.UpToDate>(verdict)
        assertEquals("You have the newest version, 1.8.1-beta.", Updates.headline(verdict, Platform.WINDOWS))
    }

    @Test
    fun `a dev build ahead of main is reported as ahead`() {
        val verdict = assertIs<Verdict.Ahead>(ReleasePicker.pick(releases, Versions.parse("1.9.0-beta"), Platform.WINDOWS))
        assertEquals("v1.8.1-beta", verdict.latest.tag)
    }

    @Test
    fun `no running version means nothing to compare`() {
        val verdict = assertIs<Verdict.VersionUnknown>(ReleasePicker.pick(releases, null, Platform.WINDOWS))
        assertEquals("v1.8.1-beta", verdict.latest?.tag)
    }

    @Test
    fun `an empty list is no releases`() {
        assertEquals(Verdict.NoReleases, ReleasePicker.pick(ReleasePicker.parse("[]"), Versions.parse("1.0.0"), Platform.WINDOWS))
    }

    @Test
    fun `something that is not a release list is a named failure that reached the server`() {
        val html = assertFailsWith<UpdateException> { ReleasePicker.parse("<html>rate limited</html>") }
        assertTrue(html.reachedServer)
        assertEquals(ReleasePicker.NOT_A_RELEASE_LIST, html.message)
        val obj = assertFailsWith<UpdateException> { ReleasePicker.parse("""{"message": "Not Found"}""") }
        assertEquals(ReleasePicker.NOT_A_RELEASE_LIST, obj.message)
    }

    @Test
    fun `asset names are matched by prefix and platform suffix`() {
        assertTrue(Platform.WINDOWS.matches(Asset("Omega-Plugin-1.8.1-beta-Windows.zip", 1, "u")))
        assertTrue(Platform.LINUX.matches(Asset("Omega-Plugin-1.8.1-beta-Linux.AppImage", 1, "u")))
        assertTrue(!Platform.WINDOWS.matches(Asset("Omega-Plugin-1.8.1-beta-Linux.AppImage", 1, "u")))
        assertTrue(!Platform.WINDOWS.matches(Asset("something-else-Windows.zip", 1, "u")))
        assertTrue(!Platform.WINDOWS.matches(Asset("SHA256SUMS.txt", 1, "u")))
    }
}
