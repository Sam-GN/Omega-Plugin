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
 * - **A download cut short.** A partial download or a staged `.new` image, under the hidden names only
 *   the updater writes ([Updater.partName], [Installer.stagedName]), when the program died before
 *   finishing the update. Never a browser's `.part`, which in Downloads can be a download of this very
 *   program that the user has paused. Only once nothing has touched it for [PARTIAL_AGE_MILLIS],
 *   because a second running copy may be in the middle of its own update.
 * - **Extract-and-run copies on Linux.** Without FUSE the AppImage runtime unpacks the whole program
 *   into `appimage_extracted_<hash>` in the temp folder, keyed by the image's contents, and never
 *   removes it: every version leaves 65 MB behind. Every AppImage on the machine uses that prefix,
 *   so a folder goes only if [isOurs] proves it is this program's and this user's, it is not the one
 *   running here, nothing has it open — as its program, its working folder or any open file — and it
 *   is not freshly unpacked, since a copy still being unpacked for a launch has nothing open between
 *   files. The current version's own copy stays, since the runtime reuses it at the next start.
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
        val inUse = pathsInUse()
        val copies = sweepExtracted(temp, own, System.currentTimeMillis()) { folder -> inUse.any { it.startsWith(folder) } }
        if (copies > 0) Log.info("update", "Removed $copies extracted copy(ies) of older versions.")
    }

    /** A name only an update of this program writes: `.Omega-Plugin-….omega-download`, `.Omega-Plugin-….new`. */
    fun isPartial(name: String): Boolean =
        name.startsWith("." + Platform.ASSET_PREFIX) && (name.endsWith(Updater.PART_SUFFIX) || name.endsWith(".new"))

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

    /** Returns how many copies were removed. [own], anything [inUse] and anything unpacked recently are left alone. */
    fun sweepExtracted(temp: Path, own: Path?, nowMillis: Long, inUse: (Path) -> Boolean): Int {
        val root = runCatching { temp.toRealPath() }.getOrNull() ?: return 0
        val mine = own?.let { path -> runCatching { path.toRealPath() }.getOrNull() }
        val found = runCatching { Files.list(root).use { it.toList() } }.getOrDefault(emptyList())
        var removed = 0
        for (folder in found) {
            if (folder == mine || !isOurs(folder) || inUse(folder)) continue
            val age = runCatching { nowMillis - Files.getLastModifiedTime(folder).toMillis() }.getOrDefault(0L)
            if (age < PARTIAL_AGE_MILLIS) continue
            deleteTree(folder)
            if (!Files.exists(folder)) removed++
        }
        return removed
    }

    /**
     * Everything a process this user can see is using: its program, its working and root folders,
     * and every file it has open. Another user's processes cannot be read, and cannot be using a
     * folder only this user can write to.
     */
    private fun pathsInUse(): List<Path> {
        val found = ArrayList<Path>()
        val processes = runCatching { Files.list(Path.of("/proc")).use { it.toList() } }.getOrDefault(emptyList())
        for (process in processes) {
            if (!process.fileName.toString().all { it.isDigit() }) continue
            for (link in listOf("exe", "cwd", "root")) {
                runCatching { found.add(Files.readSymbolicLink(process.resolve(link))) }
            }
            runCatching {
                Files.list(process.resolve("fd")).use { fds ->
                    fds.forEach { fd -> runCatching { found.add(Files.readSymbolicLink(fd)) } }
                }
            }
        }
        return found
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
