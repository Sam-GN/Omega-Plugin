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

/**
 * Everything the GPL requires a running copy to say, in one place.
 *
 * One place on purpose. These lines have to agree with `LICENSE`, with the README's third-party
 * table, and with each other, and the way that stops being true is by living in three files that get
 * edited separately. About and the Licences dialog both read from here.
 *
 * What the GPL actually asks for, and where each part is answered:
 *
 * - **§4 — a copyright notice and the notices of absence of warranty, published conspicuously on
 *   each copy; §5(d) — an interactive program displays them itself.** [COPYRIGHT] and [WARRANTY],
 *   in a menu item one click from the title bar.
 * - **§4 — give recipients a copy of the License along with the Program.** `LICENSE` is now packed
 *   into the jar and into the distribution archives, so a jar handed over on its own still carries
 *   it. [WHERE_TO_FIND] says so.
 * - **§6 — the Corresponding Source, alongside the binary, by written offer, or from the place the
 *   binary is downloaded from.** [SOURCE_URL], which is the one line here that has to name a real
 *   repository before anything is published.
 *
 * And what the *dependencies* ask for, which is the half that is easy to forget: JNA is used under
 * LGPL 2.1, and §6 of that licence wants its own text shipped alongside. It already is — the fat jar
 * copies JNA's `META-INF/LGPL2.1` and `META-INF/LICENSE` in with the rest of its contents — so this
 * says where to look rather than duplicating 24 kB of licence text into a dialog.
 */
object Licences {

    /**
     * Where the source lives.
     *
     * Under GPLv3 §6 a binary has to be accompanied by its Corresponding Source — beside it, by a
     * written offer, or from the same place it is downloaded from — so
     * **this URL has to resolve, and has to carry the tag a given binary was built from.** It is
     * deliberately the only such string in the program. Publishing a release on this repository is
     * what discharges §6(d); handing a build out any other way needs the source alongside it.
     */
    const val SOURCE_URL = "https://github.com/Sam-GN/Omega-Plugin"

    const val COPYRIGHT = "Copyright © 2026 Sam Ganji <sam.ganji.n@gmail.com>"

    private val LICENCE = listOf(
        "This program is free software: you may redistribute it and/or",
        "modify it under the terms of the GNU General Public License as",
        "published by the Free Software Foundation, either version 3 of",
        "the License, or (at your option) any later version.",
    )

    /**
     * The disclaimer, in the capitals the licence itself uses.
     *
     * Shouting is the convention here rather than a style choice: §4 asks for it to be published
     * conspicuously, and every other program that ships under this licence renders it the same way.
     */
    private val WARRANTY = listOf(
        "This program comes with ABSOLUTELY NO WARRANTY; without even",
        "the implied warranty of MERCHANTABILITY or FITNESS FOR A",
        "PARTICULAR PURPOSE. See the GNU General Public License for",
        "more details.",
    )

    private val WHERE_TO_FIND = listOf(
        "The full licence text is in LICENSE, inside this program's jar",
        "and beside it in the distribution.",
    )

    /**
     * The third-party table, and why each entry is allowed to be here.
     *
     * The reasons are included rather than just the names, because a compliance reader wants to
     * see that each licence was looked at — three of the four carry Apache 2.0 in some form, and
     * JNA is dual-licensed, so an election had to be made and recorded — and a bare list of names
     * does not show that anybody did.
     *
     * The list is of what is **in the jar**, not of what `build.gradle.kts` declares. Those are not
     * the same set: JNA and the Kotlin stdlib are asked for by name, and `org.jetbrains:annotations`
     * arrives underneath the stdlib without ever being mentioned in the build. It was missing here
     * for that reason. The check that keeps this honest is `gradlew dependencies --configuration
     * runtimeClasspath`, not a reading of the dependency block.
     */
    private val THIRD_PARTY = listOf(
        "Kotlin standard library — Apache License 2.0, which is",
        "    GPLv3-compatible. The Apache 2.0 text is inside this",
        "    jar at META-INF/AL2.0.",
        "JetBrains annotations 13.0 — Apache License 2.0. Arrives",
        "    transitively with the Kotlin standard library and is inside",
        "    this jar; GPLv3-compatible, the same as the stdlib.",
        "JNA 5.19.1 — dual-licensed Apache 2.0 or LGPL 2.1, both",
        "    GPLv3-compatible. This program takes it under LGPL 2.1.",
        "    Its licence texts are in META-INF/ inside the jar.",
        "Java runtime — GPL v2 with the Classpath Exception, which is",
        "    what permits linking against it. The bundles ship an Eclipse",
        "    Temurin build and name the exact one: READ-ME-FIRST.txt",
        "    beside the Windows bundle, RUNTIME-NOTICE.txt inside the",
        "    Linux AppImage, each next to the runtime's own legal/ folder.",
        "    Its source is published at https://github.com/adoptium/jdk21u.",
        "AppImage runtime — MIT licence. The launcher stub appimagetool",
        "    prepends to the Linux AppImage; not part of the program.",
        "",
        "The Steinberg ASIO SDK is deliberately not used and not",
        "included. The ASIO backend declares the IASIO COM interface",
        "from its published layout and contains no Steinberg code.",
    )

    /** The Licences dialog: everything, in the order someone checking compliance would want it. */
    fun full(): List<String> = buildList {
        add(COPYRIGHT)
        add("")
        addAll(LICENCE)
        add("")
        addAll(WARRANTY)
        add("")
        addAll(WHERE_TO_FIND)
        add("Source code: $SOURCE_URL")
        add("")
        add("Third-party components")
        addAll(THIRD_PARTY)
    }

    /**
     * About: the short form.
     *
     * Short because About answers "what is this and which version", and burying that under four
     * paragraphs of licence would make it worse at its own job. It still carries the copyright and
     * the disclaimer — §1 wants those on the program, not merely somewhere in it — and points at
     * the dialog that has the rest.
     */
    fun about(version: String?): List<String> = buildList {
        version?.let { add("Version $it") }
        add("A realtime guitar effects processor.")
        add("")
        add(COPYRIGHT)
        add("GNU General Public License, version 3 or later.")
        add("This program comes with ABSOLUTELY NO WARRANTY.")
        add("See Licences for the full notice.")
        add("")
        add("Java ${System.getProperty("java.version")}")
    }
}
