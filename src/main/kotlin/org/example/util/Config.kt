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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Application settings that outlive a run — `config.json`, beside the presets.
 *
 * Distinct from a preset on purpose. A preset is a *sound*, and has to load correctly on a machine
 * that has never seen the interface it was made on; this is the opposite, the handful of things that
 * belong to **this installation on this machine** and must not travel with a sound: where recordings
 * go, and whatever comes next.
 *
 * Two rules make it safe to grow:
 *
 * - **Keys it does not recognise are kept.** The whole map is read and the whole map is written, so
 *   a setting added by a later version survives being loaded and saved by an earlier one. The
 *   alternative — parsing into fixed fields and writing those back — silently deletes anything it
 *   was not built to understand.
 * - **A broken or missing file is not an error.** Settings are conveniences; losing them costs a
 *   preference, and refusing to start over one would cost the application. It falls back to defaults
 *   and carries on.
 */
class Config(private val file: Path) {

    private val values = LinkedHashMap<String, Any?>()

    /**
     * Something to print when this load had to **create** the file — null on every other run.
     *
     * It answers one specific dead end. The troubleshooting log is off by default and is turned on
     * from a menu inside the window, so the failure it would be most useful for — a launch that dies
     * before there is a window — is exactly the one it cannot be turned on for. The only escape is to
     * edit the file by hand, and the only run where a user is reliably watching the console *and*
     * has not yet been told where that file is, is the run that creates it.
     *
     * So: the path in full and the key to set, once, on first run. Deliberately not every launch —
     * a line printed every time is a line nobody reads by the third time, and the one occasion it
     * mattered would scroll past unnoticed with the rest.
     *
     * Null too when the file could not be written, because pointing somebody at a file that is not
     * there is worse than saying nothing at all.
     */
    var firstRunNotice: String? = null
        private set

    /**
     * Where recordings are written, or null to use the default.
     *
     * Stored rather than derived so that choosing a folder once is enough, and null-when-default
     * rather than resolving eagerly so that the default can change in a later version without every
     * existing installation being pinned to the old one.
     */
    var recordingFolder: String?
        get() = values[RECORDING_FOLDER] as? String
        set(value) {
            if (value == null) values.remove(RECORDING_FOLDER) else values[RECORDING_FOLDER] = value
            save()
        }

    /**
     * Whether the troubleshooting log is being written — see [Log].
     *
     * Remembered rather than reset each run, and that is the point of it: the failures worth a log
     * are the intermittent ones, so "turn it on and use the thing normally for a few days" has to be
     * something a user can actually do. A switch that quietly reverted every launch would collect
     * nothing but first-minute logs.
     *
     * Defaults to off. Logging is a diagnostic, not a feature, and it writes to a disk nobody asked
     * it to write to.
     */
    var loggingEnabled: Boolean
        get() = values[LOGGING] as? Boolean ?: false
        set(value) {
            values[LOGGING] = value
            save()
        }

    /**
     * Whether the board is hiding the pedals that are switched off.
     *
     * Settings rather than session, and the distinction is the point: this is a preference about
     * *looking* at a board, not part of any sound. A preset that carried it would impose one
     * person's screen habits on everyone it was shared with, and would change what you see every
     * time you loaded one.
     *
     * Defaults to showing everything. A board that opened with pedals missing, on an installation
     * whose owner had forgotten the setting existed, would read as pedals that had been lost.
     */
    var hideDisabledModules: Boolean
        get() = values[HIDE_DISABLED] as? Boolean ?: false
        set(value) {
            values[HIDE_DISABLED] = value
            save()
        }

    /**
     * Where you left off, as raw JSON — see [org.example.preset.Session].
     *
     * Deliberately untyped here. `util` sits under everything and knows nothing about presets or
     * devices, and it does not need to: the session is one value that goes in and comes back out
     * unchanged, and the code that understands its shape is the code that owns it.
     */
    var session: Any?
        get() = values[SESSION]
        set(value) {
            if (value == null) values.remove(SESSION) else values[SESSION] = value
            save()
        }

    fun load() {
        values.clear()
        firstRunNotice = null
        val text = try {
            if (!Files.isRegularFile(file)) {
                create()
                return
            }
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        val parsed = try {
            Json.parse(text)
        } catch (_: Exception) {
            return
        }
        (parsed as? Map<*, *>)?.forEach { (key, value) ->
            if (key is String) values[key] = value
        }
    }

    /**
     * Writes the file the first time, rather than waiting for something to change.
     *
     * It used to be created lazily, by the first setting a user touched. That is fine for storing a
     * preference and useless for the case above: an installation that has never had a folder picked
     * or a switch flipped has no `config.json` at all, so an application that dies during launch
     * leaves nothing on disk to edit and no way in. Writing it here means the file exists before any
     * of the startup that can fail.
     *
     * [LOGGING] is written explicitly, at its default, for the same reason: a file containing `{}`
     * tells somebody who opens it nothing about what may be set in it. The key they are being sent
     * to change has to already be in front of them, spelled the way the program spells it.
     */
    private fun create() {
        values[LOGGING] = false
        save()
        // A settings folder that cannot be written is not worth a message — and certainly not one
        // naming a path with nothing at it.
        if (!Files.isRegularFile(file)) return
        firstRunNotice =
            "Settings created: ${file.toAbsolutePath()}\n" +
                "  If Omega-Plugin ever fails to start, its Logging switch is out of reach. Set\n" +
                "  \"$LOGGING\": true in that file with any text editor and the next launch will\n" +
                "  record what happened in the logs folder beside it."
    }

    /**
     * Writes the file, and says nothing if it cannot.
     *
     * Temp-then-move, like the preset store: a failure halfway through leaves the previous settings
     * intact rather than a truncated file that will not parse.
     */
    fun save() {
        try {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.write(temporary, Json.write(values).toByteArray(StandardCharsets.UTF_8))
            runCatching { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse {
                    Files.copy(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                    Files.deleteIfExists(temporary)
                }
        } catch (_: Exception) {
            // A settings file that cannot be written is not worth interrupting anyone over. The
            // choice still applies for this run; it simply will not be there next time.
        }
    }

    companion object {
        private const val FILE = "config.json"
        private const val FOLDER = "config"
        private const val RECORDING_FOLDER = "recordingFolder"
        private const val SESSION = "session"
        private const val LOGGING = "loggingEnabled"
        private const val HIDE_DISABLED = "hideDisabledModules"

        fun default(): Config = at(org.example.preset.PresetStore.defaultFolder())

        /**
         * Settings live in a `config` folder of their own, **not** beside the presets.
         *
         * They used to sit next to them, and that was a real bug: the preset store lists every
         * `*.json` in its folder, so `config.json` was read as a sound, failed, and was reported to
         * the user as a preset that would not load. A settings file is not a preset and must not
         * share a namespace with one — a subfolder is the cheapest way to make that true rather
         * than merely intended.
         */
        fun at(settingsRoot: Path): Config =
            Config(settingsRoot.resolve(FOLDER).resolve(FILE)).apply { load() }
    }
}
