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
package org.example.preset

import org.example.util.Json
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Why one preset file could not be used. The message completes "\"<file>\" …". */
class PresetException(message: String) : Exception(message)

/**
 * Presets on disk, one JSON file each.
 *
 * A file per preset rather than one collection, so a single sound can be copied, sent or dropped in
 * on its own — which is what makes importing and exporting worth having. It also contains damage: a
 * file that will not parse costs that one preset, not all of them.
 *
 * Per-user application data rather than beside the jar, because the jar is a build output that gets
 * replaced on every version. Windows uses `%APPDATA%`, Linux `$XDG_CONFIG_HOME` or `~/.config`.
 */
class PresetStore(val folder: Path) {

    /** A file that could not be loaded, and the reason, ready to show. */
    class Failure(val file: Path, val reason: String) {
        override fun toString() = "\"${file.fileName}\" $reason"
    }

    /** Everything that loaded, and everything that did not. Neither hides the other. */
    class Listing(val presets: List<Preset>, val failures: List<Failure>)

    /** Preset name (lowercased) to the file it came from, so saving overwrites rather than duplicates. */
    private val index = HashMap<String, Path>()

    fun list(): Listing {
        migrateLegacyCollection()
        index.clear()
        if (!Files.isDirectory(folder)) return Listing(emptyList(), emptyList())

        val presets = mutableListOf<Preset>()
        val failures = mutableListOf<Failure>()
        for (path in jsonFiles()) {
            try {
                val preset = read(path)
                presets += preset
                index[preset.name.lowercase()] = path
            } catch (e: Exception) {
                failures += Failure(path, e.message ?: e.javaClass.simpleName)
            }
        }
        return Listing(presets.sortedBy { it.name.lowercase() }, failures)
    }

    /** Writes one preset, replacing the file it came from if it has one. Throws on failure. */
    fun save(preset: Preset) {
        Files.createDirectories(folder)
        val target = index[preset.name.lowercase()] ?: freeFile(preset.name)
        writeAtomically(target, Json.write(preset.toJsonValue()))
        index[preset.name.lowercase()] = target
    }

    fun delete(preset: Preset) {
        val path = index.remove(preset.name.lowercase()) ?: return
        Files.deleteIfExists(path)
    }

    /**
     * Every preset file in the folder, and nothing else.
     *
     * `config.json` is excluded by name. It lives in its own subfolder now — subfolders are already
     * skipped, since only regular files are listed — but a copy left beside the presets by an
     * earlier build would otherwise be read as a sound, fail, and be reported to the user as a
     * preset that would not load. Which is exactly what happened.
     */
    private fun jsonFiles(): List<Path> =
        Files.list(folder).use { stream ->
            stream.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".json", true) &&
                    !it.fileName.toString().equals(NOT_A_PRESET, ignoreCase = true)
            }
                .sorted()
                .toList()
        }

    /**
     * A file name that is free and legal.
     *
     * Preset names are free text and file names are not, so anything a filesystem would object to
     * becomes an underscore. Two names can therefore collide once cleaned — "A/B" and "A:B" both
     * become "A_B" — hence the numbered fallback.
     */
    private fun freeFile(name: String): Path {
        val base = name.map { if (it.isLetterOrDigit() || it in " -_()&+.,'") it else '_' }
            .joinToString("")
            .trim()
            .ifEmpty { "preset" }
        var candidate = folder.resolve("$base.json")
        var suffix = 2
        while (Files.exists(candidate)) {
            candidate = folder.resolve("$base ($suffix).json")
            suffix++
        }
        return candidate
    }

    private fun writeAtomically(target: Path, json: String) {
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.write(temporary, json.toByteArray(StandardCharsets.UTF_8))
        runCatching { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse {
                // ATOMIC_MOVE is unavailable on some filesystems; a copy still beats writing over
                // the original in place, where a failure halfway leaves neither version intact.
                Files.copy(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(temporary)
            }
    }

    /**
     * Splits an older single-file collection into one file per preset, once.
     *
     * Earlier builds wrote every preset into `presets.json`. Silently leaving those behind would
     * look exactly like losing them.
     */
    private fun migrateLegacyCollection() {
        val legacy = folder.resolve(LEGACY_FILE)
        if (!Files.isRegularFile(legacy)) return
        val presets = runCatching { readAll(legacy) }.getOrNull().orEmpty()
        for (preset in presets) {
            runCatching { writeAtomically(freeFile(preset.name), Json.write(preset.toJsonValue())) }
        }
        runCatching { Files.move(legacy, legacy.resolveSibling("$LEGACY_FILE.migrated")) }
    }

    companion object {

        private const val FOLDER = "Omega-Plugin"
        private const val LEGACY_FILE = "presets.json"

        /** Settings, not a sound. See [jsonFiles]. */
        private const val NOT_A_PRESET = "config.json"

        fun default(): PresetStore = PresetStore(defaultFolder())

        /**
         * Reads one preset, or explains why it could not.
         *
         * The messages are written to complete `"<file>" …` so a caller can name the file once and
         * append the reason.
         */
        fun read(path: Path): Preset {
            val presets = readAll(path)
            return presets.first()
        }

        /**
         * Reads every preset in a file, and is forgiving about the shape.
         *
         * A file written here holds one preset. An imported one might reasonably be an array, or an
         * older collection with a `presets` key — someone hand-editing or sending a batch would
         * write either.
         */
        fun readAll(path: Path): List<Preset> {
            val text = try {
                String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            } catch (e: IOException) {
                throw PresetException("could not be read: ${e.message ?: "I/O error"}")
            }

            val value = try {
                Json.parse(text)
            } catch (e: Json.MalformedException) {
                throw PresetException("is not valid JSON — ${e.message}")
            }

            val presets = when (value) {
                is Map<*, *> -> (value["presets"] as? List<*>)?.mapNotNull { Preset.fromJsonValue(it) }
                    ?: listOfNotNull(Preset.fromJsonValue(value))
                is List<*> -> value.mapNotNull { Preset.fromJsonValue(it) }
                else -> emptyList()
            }
            if (presets.isEmpty()) {
                throw PresetException("is valid JSON but not a preset — no \"name\" field was found")
            }
            return presets
        }

        /**
         * The per-user settings folder — presets, and [org.example.util.Config] beside them.
         *
         * Public because it is not really the preset store's: it is where this application keeps
         * everything it remembers, and the second thing that needed it should not have had to
         * rediscover the rules.
         */
        fun defaultFolder(): Path {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (os.contains("win")) {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrBlank()) return Path.of(appData, FOLDER)
            }
            val xdg = System.getenv("XDG_CONFIG_HOME")
            if (!xdg.isNullOrBlank()) return Path.of(xdg, "omega-plugin")
            return Path.of(System.getProperty("user.home"), ".config", "omega-plugin")
        }
    }
}
