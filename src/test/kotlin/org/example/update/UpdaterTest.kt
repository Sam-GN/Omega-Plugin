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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sequence around the installer: when the network is touched, and what a fallback leaves. */
class UpdaterTest {

    private val assetName = "Omega-Plugin-1.8.1-beta-Linux.AppImage"
    private val bytes = "the bundle".toByteArray()

    private inner class Source : UpdateSource {
        var downloads = 0
        override fun fetch(url: String): String {
            val file = Files.createTempFile("omega-sum", null)
            try {
                Files.write(file, bytes)
                return "${Checksums.sha256(file)}  $assetName\n"
            } finally {
                Files.delete(file)
            }
        }
        override fun download(url: String, to: Path, progress: (Long, Long) -> Unit) {
            downloads++
            Files.write(to, bytes)
        }
    }

    private class Waiting : Updater.Listener {
        val done = CountDownLatch(1)
        var failure: String? = null
        var saved: Path? = null
        var ready = false
        override fun checked(verdict: Verdict) = Unit
        override fun progress(text: String) = Unit
        override fun failed(message: String) { failure = message; done.countDown() }
        override fun downloaded(file: Path, reason: String) { saved = file; done.countDown() }
        override fun readyToRestart(launch: () -> Unit) { ready = true; done.countDown() }
    }

    private class StubInstaller(private val folder: Path, private val reason: String?) : Installer {
        var staged: Path? = null
        override fun cannotApply() = reason
        override fun downloadFolder() = folder
        override fun stagedName(assetName: String) = ".$assetName.new"
        override fun stage(download: Path, progress: (String) -> Unit): () -> Unit {
            staged = download
            return { }
        }
    }

    private fun available(withChecksums: Boolean): Verdict.Available {
        val asset = Asset(assetName, bytes.size.toLong(), "https://example.invalid/$assetName")
        val sums = Asset(Checksums.FILE_NAME, 80, "https://example.invalid/${Checksums.FILE_NAME}")
        val release = Release(
            tag = "v1.8.1-beta",
            version = assertNotNull(Versions.parse("1.8.1-beta")),
            prerelease = true,
            draft = false,
            body = "",
            htmlUrl = "",
            assets = if (withChecksums) listOf(asset, sums) else listOf(asset),
        )
        return Verdict.Available(release, asset)
    }

    private fun run(source: Source, installer: Installer, verdict: Verdict.Available): Waiting {
        val listener = Waiting()
        Updater(source, installer, Versions.parse("1.8.0-beta"), Platform.LINUX, listener) { it.run() }.update(verdict)
        assertTrue(listener.done.await(10, TimeUnit.SECONDS), "the worker reported back")
        return listener
    }

    @Test
    fun `a release with no checksum list is refused before anything is downloaded`() {
        val folder = Files.createTempDirectory("omega-updater")
        val source = Source()
        val listener = run(source, StubInstaller(folder, null), available(withChecksums = false))
        assertEquals(Updater.NO_CHECKSUMS, listener.failure)
        assertEquals(0, source.downloads)
        Files.delete(folder)
    }

    @Test
    fun `a download to be applied reaches the installer under the staged name`() {
        val folder = Files.createTempDirectory("omega-updater")
        val installer = StubInstaller(folder, null)
        val listener = run(Source(), installer, available(withChecksums = true))
        assertTrue(listener.ready)
        assertEquals(folder.resolve(".$assetName.new"), installer.staged)
        Files.delete(installer.staged!!)
        Files.delete(folder)
    }

    @Test
    fun `a download that cannot be applied keeps its own name and is ready to run on Linux`() {
        val folder = Files.createTempDirectory("omega-updater")
        val home = Files.createTempDirectory("omega-home")
        val realHome = System.getProperty("user.home")
        // The fallback goes to the user's own home, so give it one that is not the developer's.
        System.setProperty("user.home", home.toString())
        val installer = StubInstaller(folder, "not writable")
        try {
            val listener = run(Source(), installer, available(withChecksums = true))
            val saved = assertNotNull(listener.saved)
            assertEquals(home.resolve(assetName), saved)
            assertTrue(Files.isExecutable(saved))
            assertNull(installer.staged)
            Files.delete(saved)
        } finally {
            System.setProperty("user.home", realHome)
            Files.delete(home)
            Files.delete(folder)
        }
    }

    @Test
    fun `a download that cannot be applied goes beside a file of the same name, never over it`() {
        val folder = Files.createTempDirectory("omega-updater")
        val home = Files.createTempDirectory("omega-home")
        val downloads = Files.createDirectories(home.resolve("Downloads"))
        val kept = downloads.resolve(assetName).also { Files.writeString(it, "the copy they kept") }
        val realHome = System.getProperty("user.home")
        System.setProperty("user.home", home.toString())
        try {
            val listener = run(Source(), StubInstaller(folder, "not writable"), available(withChecksums = true))
            val saved = assertNotNull(listener.saved)
            assertEquals(downloads.resolve("Omega-Plugin-1.8.1-beta-Linux (2).AppImage"), saved)
            assertEquals("the copy they kept", Files.readString(kept))
            Files.delete(saved)
            Files.delete(kept)
        } finally {
            System.setProperty("user.home", realHome)
            Files.delete(downloads)
            Files.delete(home)
            Files.delete(folder)
        }
    }
}
