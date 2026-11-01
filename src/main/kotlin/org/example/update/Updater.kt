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

import org.example.util.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The two things the About dialog can ask for, each on a worker thread, each reporting back
 * through [post] — `SwingUtilities.invokeLater` in the program, a direct call in a test.
 *
 * Nothing here runs unless [check] or [update] is called, and nothing calls them but a button.
 */
class Updater(
    private val source: UpdateSource,
    private val installer: Installer,
    private val running: Version?,
    private val platform: Platform,
    private val listener: Listener,
    private val post: (Runnable) -> Unit,
) {

    interface Listener {
        fun checked(verdict: Verdict)
        fun failed(message: String)
        fun progress(text: String)

        /** Downloaded and verified, but this copy cannot replace itself; [reason] says why. */
        fun downloaded(file: Path, reason: String)

        /** Staged. The window saves everything, runs [launch], and exits. */
        fun readyToRestart(launch: () -> Unit)
    }

    /** One HTTPS GET, then a comparison. */
    fun check() = work("omega-update-check") {
        val json = source.fetch(GitHubSource.RELEASES_URL)
        val verdict = ReleasePicker.pick(ReleasePicker.parse(json), running, platform)
        Log.info("update", "Checked: ${Updates.headline(verdict, platform)}")
        post { listener.checked(verdict) }
    }

    /** Download, verify against the release's checksum list, and stage. */
    fun update(available: Verdict.Available) = work("omega-update-download") {
        val asset = available.asset
            ?: throw UpdateException(Updates.notForPlatform(available.release, platform), reachedServer = true)
        val reason = installer.cannotApply()
        val folder = if (reason == null) installer.downloadFolder() else Updates.downloadsFolder()
        Files.createDirectories(folder)
        val part = folder.resolve(asset.name + ".part")
        try {
            source.download(asset.url, part) { done, total ->
                post { listener.progress(Updates.downloading(done, total)) }
            }
            post { listener.progress("Checking…") }
            val sums = available.release.checksums
                ?: throw UpdateException(NO_CHECKSUMS, reachedServer = true)
            val problem = Checksums.verify(part, asset.name, Checksums.parse(source.fetch(sums.url)))
            if (problem != null) throw UpdateException(problem, reachedServer = true)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(part) }
            throw t
        }
        val file = folder.resolve(asset.name)
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
        Log.info("update", "Downloaded and verified $file")
        if (reason != null) {
            post { listener.downloaded(file, reason) }
            return@work
        }
        val launch = installer.stage(file) { text -> post { listener.progress(text) } }
        post { listener.readyToRestart(launch) }
    }

    private fun work(name: String, body: () -> Unit) {
        Thread({
            try {
                body()
            } catch (e: UpdateException) {
                Log.warn("update", "${e.message}" + (e.cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
                post { listener.failed(e.message ?: "Update failed.") }
            } catch (t: Throwable) {
                Log.error("update", "Unexpected failure.", t)
                post { listener.failed("Update failed: ${t.message ?: t.javaClass.simpleName}") }
            }
        }, name).apply { isDaemon = true }.start()
    }

    companion object {
        const val NO_CHECKSUMS =
            "This release has no checksum list, so the download could not be verified and was deleted."
    }
}
