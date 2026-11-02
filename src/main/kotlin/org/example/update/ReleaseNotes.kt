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

/**
 * A release's notes, from the markdown GitHub shows to the plain text the dialog shows.
 *
 * The body is the version's CHANGELOG section, written for the release page. The dialog is a plain
 * text box, so the marks would be read as literal characters, and the changelog's hard wrap at 100
 * columns would break sentences in the middle of a box half that wide. Pure, so it is tested against
 * the real changelog. Only what the changelog uses is handled; this is not a markdown parser.
 */
object ReleaseNotes {

    private val BULLET = Regex("""^[-*+]\s+(.*)$""")
    private val RULE = Regex("""^(-{3,}|\*{3,})$""")
    private val LINK = Regex("""\[([^]]*)]\([^)]*\)""")
    private val EMPHASIS = Regex("""(?<![\w*\\])\*(?=\S)(.+?)(?<=\S)\*(?![\w*])""")

    fun plain(markdown: String): String {
        val entries = mutableListOf<String>()
        // Whether the next line carries on the entry before it rather than starting one.
        var open = false
        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            val bullet = BULLET.find(line)
            when {
                line.isEmpty() || RULE.matches(line) -> {
                    if (entries.isNotEmpty() && entries.last().isNotEmpty()) entries += ""
                    open = false
                }
                line.startsWith("#") -> {
                    entries += line.trimStart('#', ' ')
                    open = false
                }
                bullet != null -> {
                    entries += "• " + bullet.groupValues[1]
                    open = true
                }
                open -> entries[entries.lastIndex] += " $line"
                else -> {
                    entries += line
                    open = true
                }
            }
        }
        return entries.dropLastWhile { it.isEmpty() }.joinToString("\n") { inline(it) }
    }

    /**
     * After the lines are joined, since a mark can open on one line and close on the next. Whatever
     * sits between backticks is left exactly as written: `app\*.jar` has to survive.
     */
    private fun inline(entry: String): String =
        entry.split('`').mapIndexed { index, part ->
            if (index % 2 == 1) part else part.replace("**", "").let { LINK.replace(it, "$1") }.let { EMPHASIS.replace(it, "$1") }
        }.joinToString("")
}
