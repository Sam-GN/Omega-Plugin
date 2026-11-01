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
import java.nio.file.StandardCopyOption

/**
 * A failure the user is told about, in its [message] exactly.
 *
 * [reachedServer] separates "GitHub said something we did not like" from "nothing answered". The
 * headless `--check-update` passes on the first and fails on the second, which is how CI proves a
 * bundle can complete a TLS handshake without depending on what GitHub happens to say that day.
 */
class UpdateException(
    message: String,
    val reachedServer: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Where releases come from: two requests and one download. [GitHubSource] is the real one and the
 * only type in the program that opens a socket; [FileSource] stands in for it in development.
 */
interface UpdateSource {

    /** Fetches a small text document. Throws [UpdateException] with the words to show. */
    fun fetch(url: String): String

    /**
     * Streams [url] to [to], calling [progress] with bytes so far and the total (or -1 if the
     * server did not say) as it goes. Throws [UpdateException]; the partial file is the caller's to
     * delete.
     */
    fun download(url: String, to: Path, progress: (done: Long, total: Long) -> Unit)
}

/**
 * A folder of canned answers, for driving the whole update path — download, checksum, install,
 * relaunch — against a release that does not exist yet. Selected by the system property
 * [PROPERTY]; nothing in a shipped launcher sets it.
 *
 * The releases request is answered by `releases.json` in the folder; every other URL by the file
 * whose name is the URL's last segment, so a canned `releases.json` can point its assets anywhere
 * as long as the names match. Never touches the network, which is the point.
 */
class FileSource(private val folder: Path) : UpdateSource {

    private fun fileFor(url: String): Path {
        val name = if (url == GitHubSource.RELEASES_URL) "releases.json" else url.substringAfterLast('/').substringBefore('?')
        val file = folder.resolve(name)
        if (!Files.isRegularFile(file)) {
            throw UpdateException("No canned answer for $url: expected $file.", reachedServer = false)
        }
        return file
    }

    override fun fetch(url: String): String = Files.readString(fileFor(url))

    override fun download(url: String, to: Path, progress: (Long, Long) -> Unit) {
        val from = fileFor(url)
        val total = Files.size(from)
        progress(0, total)
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        progress(total, total)
    }

    companion object {
        const val PROPERTY = "omega.update.source"
    }
}
