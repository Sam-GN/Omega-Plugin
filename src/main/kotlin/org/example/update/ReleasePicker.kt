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

import org.example.util.Json

/** What a check found. Every case is one the dialog has words for. */
sealed interface Verdict {

    /** The running version is the newest published one. */
    data class UpToDate(val latest: Release) : Verdict

    /**
     * A newer release exists. [asset] is this platform's bundle, or null when the release has none
     * for it — a hot-fix published for the other platform alone.
     */
    data class Available(val release: Release, val asset: Asset?) : Verdict

    /** The running version is newer than anything published: a dev build run ahead of main. */
    data class Ahead(val running: Version, val latest: Release) : Verdict

    /** Run from loose classes, so there is no version to compare. [latest] is still named. */
    data class VersionUnknown(val latest: Release?) : Verdict

    /** GitHub answered with an empty list, or with nothing that parsed as a release. */
    data object NoReleases : Verdict
}

/**
 * From the releases JSON to a [Verdict]: the newest published release by version, and this
 * platform's asset on it. Pure, so it is tested against a captured response.
 */
object ReleasePicker {

    /**
     * Reads the list endpoint's response. Entries whose tag is not a version are skipped, and so
     * are drafts — an unauthenticated caller never sees one, but the field is there and the rule
     * that a draft is never announced is worth enforcing rather than assuming.
     */
    fun parse(json: String): List<Release> {
        val root = try {
            Json.parse(json)
        } catch (e: Json.MalformedException) {
            throw UpdateException(NOT_A_RELEASE_LIST, reachedServer = true, cause = e)
        }
        val list = root as? List<*> ?: throw UpdateException(NOT_A_RELEASE_LIST, reachedServer = true)
        return list.mapNotNull { entry -> (entry as? Map<*, *>)?.let(::release) }
    }

    private fun release(map: Map<*, *>): Release? {
        val tag = map["tag_name"] as? String ?: return null
        val version = Versions.parse(tag) ?: return null
        val draft = map["draft"] as? Boolean ?: false
        if (draft) return null
        val assets = (map["assets"] as? List<*>).orEmpty().mapNotNull { raw ->
            val asset = raw as? Map<*, *> ?: return@mapNotNull null
            val name = asset["name"] as? String ?: return@mapNotNull null
            val url = asset["browser_download_url"] as? String ?: return@mapNotNull null
            val size = (asset["size"] as? Double)?.toLong() ?: 0L
            Asset(name, size, url)
        }
        return Release(
            tag = tag,
            version = version,
            prerelease = map["prerelease"] as? Boolean ?: false,
            draft = false,
            body = map["body"] as? String ?: "",
            htmlUrl = map["html_url"] as? String ?: "",
            assets = assets,
        )
    }

    /**
     * The newest release **by version**, not by position: a hot-fix published for an older line, or
     * a release published out of order, must not win by being first in the list.
     */
    fun newest(releases: List<Release>): Release? = releases.maxByOrNull { it.version }

    fun pick(releases: List<Release>, running: Version?, platform: Platform): Verdict {
        val latest = newest(releases) ?: return Verdict.NoReleases
        if (running == null) return Verdict.VersionUnknown(latest)
        return when {
            latest.version > running -> Verdict.Available(latest, latest.assets.firstOrNull { platform.matches(it) })
            latest.version < running -> Verdict.Ahead(running, latest)
            else -> Verdict.UpToDate(latest)
        }
    }

    const val NOT_A_RELEASE_LIST = "GitHub answered, but not with a release list."
}
