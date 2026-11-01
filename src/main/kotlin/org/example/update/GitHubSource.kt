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

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLException

/**
 * The real [UpdateSource]: GitHub's REST API over `HttpsURLConnection`.
 *
 * **This is the only class in the program that opens a socket**, and `NoNetworkOutsideUpdaterTest`
 * keeps it that way. It runs only inside [Updater], which runs only on a press of the Check or
 * Update button — never at startup, never on a timer. That promise is on the About dialog and in
 * README.md, and this file is where it is kept.
 *
 * `HttpsURLConnection` rather than `java.net.http.HttpClient`, deliberately: the client is a module
 * of its own, `java.net.http`, and would be a second addition to the bundled runtime for no gain on
 * two requests and one download. What the bundle *does* need is `jdk.crypto.ec` — see
 * `portableModules` in `build.gradle.kts` — because GitHub's TLS needs elliptic curves and on Java
 * 21 those are a separate module; without it every request from a bundle fails in the handshake and
 * reads like a network fault. [handshakeFailed] is the message for exactly that.
 *
 * What the request carries about the user: nothing but the user agent, which GitHub requires and
 * which is the program's name and version. No identifier, no settings, no machine details.
 */
class GitHubSource(private val runningVersion: String?) : UpdateSource {

    private val userAgent = "Omega-Plugin/${runningVersion ?: "dev"}"

    override fun fetch(url: String): String {
        val connection = open(url, readTimeoutMillis = 20_000)
        try {
            val status = statusOf(connection)
            checkStatus(connection, status)
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    override fun download(url: String, to: Path, progress: (Long, Long) -> Unit) {
        val connection = open(url, readTimeoutMillis = 60_000)
        try {
            val status = statusOf(connection)
            checkStatus(connection, status)
            val total = connection.contentLengthLong
            progress(0, total)
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            try {
                connection.inputStream.use { input ->
                    Files.newOutputStream(to).use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            progress(done, total)
                        }
                    }
                }
            } catch (e: IOException) {
                throw UpdateException(connectionLost(e), reachedServer = true, cause = e)
            }
            if (total >= 0 && done != total) {
                throw UpdateException("The download stopped at $done of $total bytes.", reachedServer = true)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, readTimeoutMillis: Int): HttpURLConnection {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw UpdateException("Not a usable address: $url", reachedServer = false, cause = e)
        }
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
        return connection
    }

    /** Connects. Everything that can go wrong before a status line is "could not reach". */
    private fun statusOf(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (e: SSLException) {
        throw UpdateException(handshakeFailed(e), reachedServer = false, cause = e)
    } catch (e: IOException) {
        throw UpdateException(COULD_NOT_REACH, reachedServer = false, cause = e)
    }

    private fun checkStatus(connection: HttpURLConnection, status: Int) {
        if (status in 200..299) return
        val message = when {
            status == 403 || status == 429 -> BUSY
            status == 404 -> ReleasePicker.NOT_A_RELEASE_LIST
            else -> "GitHub answered with HTTP $status."
        }
        connection.errorStream?.close()
        throw UpdateException(message, reachedServer = true)
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/Sam-GN/Omega-Plugin/releases?per_page=10"
        const val API_VERSION = "2022-11-28"

        const val COULD_NOT_REACH = "Could not reach GitHub. Check the connection and press again."
        const val BUSY = "GitHub is busy. Try again in an hour."

        fun handshakeFailed(e: Throwable): String =
            "Could not make a secure connection to GitHub: ${e.message ?: e.javaClass.simpleName}."

        fun connectionLost(e: Throwable): String =
            "The connection to GitHub was lost: ${e.message ?: e.javaClass.simpleName}."
    }
}
