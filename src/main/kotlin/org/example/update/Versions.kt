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
 * A version as this project writes them: `1.8.1-beta`, `1.8.1`, with or without a leading `v`.
 *
 * Numbers first, then the suffix: equal numbers with no suffix beat the same numbers with one, so a
 * final release beats its own beta, and two suffixes compare as text. That last rule is cruder than
 * semver's, and deliberately — this project has only ever had `-beta`, and a comparison that tried
 * to rank `rc` against `beta` by a table would be code guarding against releases nobody makes.
 */
class Version(val numbers: List<Int>, val suffix: String?) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val length = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until length) {
            val a = numbers.getOrElse(i) { 0 }
            val b = other.numbers.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> suffix.compareTo(other.suffix)
        }
    }

    override fun equals(other: Any?): Boolean = other is Version && compareTo(other) == 0

    override fun hashCode(): Int = numbers.dropLastWhile { it == 0 }.hashCode() * 31 + (suffix?.hashCode() ?: 0)

    /** The canonical spelling, without the `v`: what the dialog shows. */
    override fun toString(): String =
        numbers.joinToString(".") + (suffix?.let { "-$it" } ?: "")
}

object Versions {

    /**
     * Parses a release tag or a version string. Null for anything that is not one — a tag called
     * `nightly`, say — so a caller can skip it rather than fail on it.
     */
    fun parse(text: String): Version? {
        val trimmed = text.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val dash = trimmed.indexOf('-')
        val numberPart = if (dash >= 0) trimmed.substring(0, dash) else trimmed
        val suffix = if (dash >= 0) trimmed.substring(dash + 1).takeIf { it.isNotEmpty() } ?: return null else null
        val numbers = numberPart.split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.isEmpty() || numbers.any { it < 0 }) return null
        return Version(numbers, suffix)
    }
}
