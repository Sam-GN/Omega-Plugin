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

/** One file attached to a release. */
data class Asset(val name: String, val size: Long, val url: String)

/** One published release, as read out of GitHub's JSON. [version] is the parsed [tag]. */
data class Release(
    val tag: String,
    val version: Version,
    val prerelease: Boolean,
    val draft: Boolean,
    val body: String,
    val htmlUrl: String,
    val assets: List<Asset>,
) {
    /** The checksum list the release workflow publishes beside the bundles, if this release has one. */
    val checksums: Asset? get() = assets.firstOrNull { it.name == Checksums.FILE_NAME }
}

/**
 * The two bundles a release carries, told apart by the end of the asset's name.
 *
 * By suffix rather than by the full name, so a change in how the version is written into the
 * filename does not strand every installed copy. The prefix is still required: a stray `.zip`
 * somebody attached by hand must not be mistaken for the program.
 */
enum class Platform(val displayName: String, private val suffix: String) {
    WINDOWS("Windows", "-Windows.zip"),
    LINUX("Linux", "-Linux.AppImage");

    fun matches(asset: Asset): Boolean =
        asset.name.startsWith(ASSET_PREFIX) && asset.name.endsWith(suffix)

    companion object {
        const val ASSET_PREFIX = "Omega-Plugin-"

        /** The platform this process is on, by the same check the backends use. */
        fun current(): Platform =
            if (org.example.audio.linux.LinuxPlatform.isLinux) LINUX else WINDOWS
    }
}
