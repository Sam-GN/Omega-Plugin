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

/**
 * The wiring: which source, which installer, which version is running, and the words for each
 * verdict. The dialog and `--check-update` both build from here so they cannot disagree.
 */
object Updates {

    /** The jar manifest's `Implementation-Version`; null from loose classes (`gradlew run`). */
    fun runningVersionText(): String? = Updates::class.java.`package`?.implementationVersion

    fun runningVersion(): Version? = runningVersionText()?.let(Versions::parse)

    /**
     * GitHub, unless the developer override names a folder of canned answers. Nothing shipped sets
     * the property; it exists so the whole path can be driven against a release that does not exist.
     */
    fun source(): UpdateSource =
        System.getProperty(FileSource.PROPERTY)?.let { FileSource(Path.of(it)) }
            ?: GitHubSource(runningVersionText())

    fun installer(): Installer = when (Platform.current()) {
        Platform.LINUX -> AppImageInstaller(System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let(Path::of))
        Platform.WINDOWS -> WindowsInstaller(
            launcher = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let(Path::of),
            temp = Path.of(System.getProperty("java.io.tmpdir")),
            pid = ProcessHandle.current().pid(),
        )
    }

    /** Where a download goes when it cannot be applied: the user's Downloads folder, else home. */
    fun downloadsFolder(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val downloads = home.resolve("Downloads")
        return if (Files.isDirectory(downloads)) downloads else home
    }

    /** `61 MB`, `912 kB`: what the dialog says a download weighs. */
    fun describeSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
        bytes >= 1_000 -> "${(bytes + 500) / 1_000} kB"
        else -> "$bytes bytes"
    }

    fun downloading(done: Long, total: Long): String =
        if (total > 0) "Downloading… ${describeSize(done)} of ${describeSize(total)}" else "Downloading… ${describeSize(done)}"

    fun notForPlatform(release: Release, platform: Platform): String =
        "${release.version} is available, but not for ${platform.displayName} yet."

    /** The one line that answers the check. */
    fun headline(verdict: Verdict, platform: Platform): String = when (verdict) {
        is Verdict.UpToDate -> "You have the newest version, ${verdict.latest.version}."
        is Verdict.Available ->
            if (verdict.asset != null) "${verdict.release.version} is available." else notForPlatform(verdict.release, platform)
        is Verdict.Ahead -> "This copy, ${verdict.running}, is newer than the newest release, ${verdict.latest.version}."
        is Verdict.VersionUnknown ->
            "Version unknown, so there is nothing to compare." +
                (verdict.latest?.let { " The newest release is ${it.version}." } ?: "")
        Verdict.NoReleases -> "GitHub lists no releases."
    }

    const val RELEASES_PAGE = "https://github.com/Sam-GN/Omega-Plugin/releases"
}
