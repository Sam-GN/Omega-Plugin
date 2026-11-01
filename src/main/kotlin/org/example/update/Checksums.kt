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
import java.security.MessageDigest

/**
 * `SHA256SUMS.txt`, the file the release workflow writes beside the bundles with `sha256sum`.
 *
 * HTTPS to GitHub is what says the bytes came from GitHub; this is what says they are the bytes CI
 * built. The format is `sha256sum`'s own — one line per file, the hex digest, two spaces (or a
 * space and `*` for binary mode), the name — so a user can check a download by hand with the same
 * tool.
 */
object Checksums {

    const val FILE_NAME = "SHA256SUMS.txt"

    /** Digest by file name, lowercase hex. Lines that are not in the format are ignored. */
    fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val split = line.indexOf(' ')
            if (split != 64) continue
            val digest = line.substring(0, 64).lowercase()
            if (!digest.all { it in '0'..'9' || it in 'a'..'f' }) continue
            val name = line.substring(split).trimStart(' ', '*').substringAfterLast('/')
            if (name.isNotEmpty()) out[name] = digest
        }
        return out
    }

    /** SHA-256 of a file, lowercase hex, streamed through one 64 KB buffer. */
    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        Files.newInputStream(file).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Null when [file] is the file the sums name as [assetName]; otherwise the reason it is not,
     * in the words the dialog shows.
     */
    fun verify(file: Path, assetName: String, sums: Map<String, String>): String? {
        val expected = sums[assetName] ?: return "The release's checksum list does not name $assetName."
        val actual = sha256(file)
        return if (actual == expected) null else MISMATCH
    }

    const val MISMATCH = "The download did not match what the build produced, so it was deleted."
}
