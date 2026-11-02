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
package org.example.util

import java.nio.file.Files
import java.nio.file.Path

/**
 * Names for files the program creates, so that creating one never replaces one.
 *
 * Every file this program writes on its own account — a recording, a preset, a download it could not
 * install — takes a name nobody is using. A name typed by the user, or carried in a preset somebody
 * sent them, is made into one part of a file name first: no folder separators and no `..` can steer a
 * file out of the folder it was meant for.
 */
object FileNames {

    /** [text] as one part of a file name: letters, digits and a few marks; anything else becomes `_`. */
    fun part(text: String): String =
        text.map { if (it.isLetterOrDigit() || it in " -_()&+.,'") it else '_' }.joinToString("").trim()

    /**
     * [base] if every name [files] makes from it is free in [folder], otherwise `base (2)`, `base (3)`
     * and so on: the first for which all of them are. One base can stand for several files, as a
     * recording and its drums copy do.
     */
    fun freeBase(folder: Path, base: String, files: (String) -> List<String>): String {
        var candidate = base
        var number = 2
        while (files(candidate).any { Files.exists(folder.resolve(it)) }) {
            candidate = "$base ($number)"
            number++
        }
        return candidate
    }

    /** [name] in [folder] if it is free, otherwise the same name with ` (2)`, ` (3)` before the extension. */
    fun free(folder: Path, name: String): Path {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val extension = name.substring(dot)
        return folder.resolve(freeBase(folder, name.substring(0, dot)) { listOf(it + extension) } + extension)
    }
}
