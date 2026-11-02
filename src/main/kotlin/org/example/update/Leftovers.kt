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

import org.example.audio.linux.LinuxPlatform
import org.example.util.Log
import java.nio.file.Files
import java.nio.file.Path

/**
 * Removes what an update can leave behind, once, on a low-priority thread as the window starts.
 * Deleting is the point of this class and the danger of it, so each rule errs towards leaving a file:
 * the worst a wrong "no" costs is some disk until the next start.
 *
 * - **A download cut short.** A `.part` file, or a staged `.new` image, under the program's own name,
 *   when the program died before finishing the update. Only once nothing has touched it for
 *   [PARTIAL_AGE_MILLIS], because a second running copy may be in the middle of its own update.
 * - **Extract-and-run copies on Linux.** Without FUSE the AppImage runtime unpacks the whole program
 *   into `appimage_extracted_<hash>` in the temp folder, keyed by the image's contents, and never
 *   removes it: every version leaves 65 MB behind. Every AppImage on the machine uses that prefix,
 *   so a folder goes only if [isOurs] proves it is this program's, this user's, not the one running
 *   here and not in use by anything else. The current version's own copy stays, since the runtime
 *   reuses it at the next start.
 *
 * Touches no network and opens no device. Nothing in here may run on a diagnostic path: `--version`
 * in particular is how a new image is tested, and that must not change the machine.
 */
object Leftovers {

    /** Far longer than a download can sit idle: [GitHubSource] gives up after 60 seconds of silence. */
    const val PARTIAL_AGE_MILLIS = 10 * 60 * 1000L

    private val EXTRACTED = Regex("appimage_extracted_[0-9a-f]{32}")

    fun sweep() {
        val partials = sweepPartials(listOf(Updates.installer().downloadFolder(), Updates.downloadsFolder()), System.currentTimeMillis())
        if (partials > 0) Log.info("update", "Removed $partials file(s) left by an update that did not finish.")
        if (!LinuxPlatform.isLinux) return
        val temp = Path.of(System.getenv("TMPDIR")?.takeIf { it.isNotBlank() } ?: "/tmp")
        val own = Path.of(System.getProperty("java.home")).parent
        val running = runningPrograms()
        val copies = sweepExtracted(temp, own) { folder -> running.any { it.startsWith(folder) } }
        if (copies > 0) Log.info("update", "Removed $copies extracted copy(ies) of older versions.")
    }

    /** A name only an update of this program writes: `Omega-Plugin-….part`, `.Omega-Plugin-….new`. */
    fun isPartial(name: String): Boolean =
        name.removePrefix(".").startsWith(Platform.ASSET_PREFIX) && (name.endsWith(".part") || name.endsWith(".new"))

    /** Returns how many files were removed. A folder that cannot be read is simply passed over. */
    fun sweepPartials(folders: Collection<Path>, nowMillis: Long): Int {
        var removed = 0
        for (folder in folders.toSet()) {
            val found = runCatching { Files.list(folder).use { it.toList() } }.getOrDefault(emptyList())
            for (file in found) {
                if (!isPartial(file.fileName.toString()) || !Files.isRegularFile(file)) continue
                val age = nowMillis - Files.getLastModifiedTime(file).toMillis()
                if (age >= PARTIAL_AGE_MILLIS && runCatching { Files.delete(file) }.isSuccess) removed++
            }
        }
        return removed
    }

    /**
     * Proof, not a guess from the prefix: the program's own desktop entry and its jar, in a folder
     * this user owns. The jar by prefix because before 2.0.0 its name carried the version.
     */
    fun isOurs(folder: Path): Boolean {
        if (!EXTRACTED.matches(folder.fileName.toString()) || !Files.isDirectory(folder)) return false
        if (!Files.isRegularFile(folder.resolve("omega-plugin.desktop"))) return false
        // Windows names an owner `MACHINE\user`; the account is the part after the last backslash.
        val owner = runCatching { Files.getOwner(folder).name.substringAfterLast('\\') }.getOrNull()
        if (owner != System.getProperty("user.name")) return false
        return runCatching {
            Files.list(folder.resolve("lib")).use { files ->
                files.anyMatch { it.fileName.toString().let { n -> n.startsWith("OmegaPlugin") && n.endsWith(".jar") } }
            }
        }.getOrDefault(false)
    }

    /** Returns how many copies were removed. [own] and anything [inUse] are left alone. */
    fun sweepExtracted(temp: Path, own: Path?, inUse: (Path) -> Boolean): Int {
        val root = runCatching { temp.toRealPath() }.getOrNull() ?: return 0
        val mine = own?.let { path -> runCatching { path.toRealPath() }.getOrNull() }
        val found = runCatching { Files.list(root).use { it.toList() } }.getOrDefault(emptyList())
        var removed = 0
        for (folder in found) {
            if (folder == mine || !isOurs(folder) || inUse(folder)) continue
            deleteTree(folder)
            if (!Files.exists(folder)) removed++
        }
        return removed
    }

    /** The executable of every process this user can see, which is every one that could use a copy of ours. */
    private fun runningPrograms(): List<Path> =
        Files.list(Path.of("/proc")).use { entries ->
            entries.toList()
                .filter { entry -> entry.fileName.toString().all { it.isDigit() } }
                .mapNotNull { entry -> runCatching { Files.readSymbolicLink(entry.resolve("exe")) }.getOrNull() }
        }

    /**
     * A bundled runtime holds read-only folders, which cannot be emptied until they are made writable.
     * `Files.walk` does not follow links, so nothing outside the folder can be reached.
     */
    private fun deleteTree(folder: Path) {
        Files.walk(folder).use { paths ->
            paths.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }.forEach { it.toFile().setWritable(true, true) }
        }
        Files.walk(folder).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }
}
