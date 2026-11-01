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

import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * Replaces the installed program with a verified download. One per platform, because a running
 * program can replace itself on Linux and cannot on Windows.
 *
 * The order of operations is the window's, not the installer's: the download and [stage] may run
 * while the stream runs; the action [stage] returns runs **after** the session and settings are
 * saved and immediately before the process exits, so the new copy never reads a session the old one
 * is still writing.
 */
interface Installer {

    /** Null when the installed copy can be replaced; otherwise why not, in the words to show. */
    fun cannotApply(): String?

    /** Where a download goes when it is going to be applied. */
    fun downloadFolder(): Path

    /**
     * Prepares [download], the verified bundle, for installation and returns the step that
     * finishes it. [progress] gets a short line for the dialog. Throws [UpdateException].
     */
    fun stage(download: Path, progress: (String) -> Unit): () -> Unit

    companion object {
        const val NOT_FROM_BUNDLE =
            "This copy was not installed from a release bundle, so it cannot replace itself."
    }
}

/**
 * Linux: the AppImage swaps itself.
 *
 * The AppImage runtime sets `APPIMAGE` to the running image's path. The download is written beside
 * it — a rename must stay on one filesystem — and the final step starts the new image, deletes the
 * old one and exits. Unlinking a running AppImage is safe: the kernel keeps the inode until the
 * process exits, which is what every AppImage updater relies on.
 *
 * The child is started with the runtime's own variables removed from its environment. `APPIMAGE`,
 * `APPDIR`, `OWD` and `ARGV0` describe *this* image and its mount, which is about to disappear;
 * the new image's runtime sets its own.
 */
class AppImageInstaller(
    /** The running image, from `APPIMAGE`, or null when not run from one. */
    private val image: Path?,
    /** Starts the new image. Injected so the swap is testable without launching anything. */
    private val launch: (Path) -> Unit = ::startDetached,
) : Installer {

    override fun cannotApply(): String? {
        val running = image ?: return Installer.NOT_FROM_BUNDLE
        val folder = running.toAbsolutePath().parent
        if (folder == null || !Files.isDirectory(folder) || !Files.isWritable(folder)) {
            return "Could not write to the folder holding the AppImage, $folder. " +
                "Download it from the Releases page and replace the file by hand."
        }
        return null
    }

    override fun downloadFolder(): Path = image?.toAbsolutePath()?.parent ?: Updates.downloadsFolder()

    override fun stage(download: Path, progress: (String) -> Unit): () -> Unit {
        val old = image?.toAbsolutePath() ?: throw UpdateException(Installer.NOT_FROM_BUNDLE, reachedServer = true)
        val fresh = download.toAbsolutePath()
        if (!fresh.toFile().setExecutable(true, false) && !Files.isExecutable(fresh)) {
            throw UpdateException("Could not mark $fresh executable.", reachedServer = true)
        }
        progress("Ready to restart…")
        return {
            launch(fresh)
            // Only after the new one is running, and only if it is a different file: a user who
            // renamed the image to the asset's exact name would otherwise lose the copy just made.
            if (fresh != old) runCatching { Files.deleteIfExists(old) }
        }
    }

    companion object {
        val RUNTIME_VARIABLES = setOf("APPIMAGE", "APPDIR", "OWD", "ARGV0")

        /** The environment for the new image: this process's, less the AppImage runtime's own. */
        fun childEnvironment(parent: Map<String, String>): Map<String, String> =
            parent.filterKeys { it !in RUNTIME_VARIABLES }

        private fun startDetached(image: Path) {
            val builder = ProcessBuilder(image.toString())
                .directory(image.parent?.toFile())
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .redirectInput(Redirect.from(java.io.File("/dev/null")))
            val env = builder.environment()
            RUNTIME_VARIABLES.forEach { env.remove(it) }
            try {
                builder.start()
            } catch (e: IOException) {
                throw UpdateException("Could not start $image: ${e.message}", reachedServer = true, cause = e)
            }
        }
    }
}

/**
 * Windows: a helper script that waits for this process to exit, then copies the new bundle over
 * the installed folder and starts it.
 *
 * While the program runs its launcher and the runtime's DLLs are locked, so nothing in-process can
 * replace them. Everything that *can* be done in-process is: the zip is expanded here, by
 * `java.util.zip`, so the helper is plain `cmd` with no PowerShell in it — wait, copy, start, clean
 * up. It copies **into the installed folder**, not beside it: extracting a new `OmegaPlugin-1.8.1\`
 * next to the old one is how the README said to do it by hand, and it moves the exe and breaks
 * every shortcut and taskbar pin the user made.
 *
 * The installed folder is the launcher's, from `jpackage.app-path`, which the jpackage launcher
 * sets and nothing else does. Writability is proved by writing: a folder under `Program Files`
 * fails the probe, and asking for elevation is out of scope.
 */
class WindowsInstaller(
    /** `OmegaPlugin.exe`, from `jpackage.app-path`, or null when not run from the bundle. */
    private val launcher: Path?,
    /** Where the download, the expanded bundle and the helper go: `%TEMP%`. */
    private val temp: Path,
    /** This process, for the helper to wait on. */
    private val pid: Long,
    /** Starts the helper, detached. Injected for the tests. */
    private val run: (Path) -> Unit = ::startHelper,
) : Installer {

    private val installDir: Path? get() = launcher?.toAbsolutePath()?.parent

    override fun cannotApply(): String? {
        val folder = installDir ?: return Installer.NOT_FROM_BUNDLE
        return if (isWritable(folder)) null else NOT_WRITABLE
    }

    override fun downloadFolder(): Path = temp

    override fun stage(download: Path, progress: (String) -> Unit): () -> Unit {
        val target = installDir ?: throw UpdateException(Installer.NOT_FROM_BUNDLE, reachedServer = true)
        val exe = launcher!!.fileName.toString()
        progress("Unpacking…")
        val expanded = temp.resolve(STAGE_FOLDER)
        deleteTree(expanded)
        Files.createDirectories(expanded)
        try {
            unzip(download, expanded)
        } catch (e: IOException) {
            deleteTree(expanded)
            throw UpdateException("Could not unpack the download: ${e.message}", reachedServer = true, cause = e)
        }
        val source = findBundleRoot(expanded, exe)
            ?: run {
                deleteTree(expanded)
                throw UpdateException("The download does not contain $exe.", reachedServer = true)
            }
        val helper = temp.resolve(HELPER_NAME)
        Files.writeString(helper, helperScript(pid, source, target, exe, download, expanded))
        progress("Ready to restart…")
        return { run(helper) }
    }

    companion object {
        const val HELPER_NAME = "omega-update.cmd"
        const val STAGE_FOLDER = "omega-update"
        const val NOT_WRITABLE =
            "Could not write to the program folder. Download it from the Releases page and unzip it over this one."

        /** Proof by doing: create and delete a file. Permissions on Windows are not worth reading. */
        fun isWritable(folder: Path): Boolean {
            if (!Files.isDirectory(folder)) return false
            val probe = folder.resolve(".omega-write-probe")
            return try {
                Files.deleteIfExists(probe)
                Files.createFile(probe)
                Files.delete(probe)
                true
            } catch (e: Exception) {
                false
            }
        }

        /** The folder inside an expanded zip that holds the launcher: the zip's root, or one level down. */
        fun findBundleRoot(expanded: Path, exe: String): Path? {
            if (Files.isRegularFile(expanded.resolve(exe))) return expanded
            Files.list(expanded).use { children ->
                return children.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve(exe)) }
                    .findFirst().orElse(null)
            }
        }

        /** Expands [zip] into [into], refusing entries that would land outside it. */
        fun unzip(zip: Path, into: Path) {
            val root = into.toAbsolutePath().normalize()
            ZipInputStream(Files.newInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val target = root.resolve(entry.name).normalize()
                    if (!target.startsWith(root)) throw IOException("entry escapes the folder: ${entry.name}")
                    if (entry.isDirectory) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    input.closeEntry()
                }
            }
        }

        /**
         * The helper, as text. A function of its inputs so a test can hold it against a golden copy.
         *
         * Waits with `tasklist` and `ping` rather than PowerShell's `Wait-Process`, so the script
         * runs on any Windows with nothing but `cmd`. `app\*.jar` is deleted first because the jar
         * is versioned by name and the launcher's `.cfg` names the new one; a stale jar would be
         * dead weight. `robocopy` exit codes below 8 are success. On a copy that failed the old
         * program is started again if it still can be, and the zip is shown in Explorer so the user
         * can unzip it by hand. The last line is the idiom for a batch file deleting itself.
         */
        fun helperScript(pid: Long, source: Path, target: Path, exe: String, zip: Path, expanded: Path): String =
            """
            @echo off
            rem Omega-Plugin update helper. Written by the program; removes itself when done.
            set "PID=$pid"
            set "SRC=$source"
            set "DST=$target"
            set "ZIP=$zip"
            set "STAGE=$expanded"
            title Omega-Plugin update
            :wait
            tasklist /FI "PID eq %PID%" 2>nul | find "%PID%" >nul
            if not errorlevel 1 (
                ping -n 2 127.0.0.1 >nul
                goto wait
            )
            del /q "%DST%\app\*.jar" 2>nul
            robocopy "%SRC%" "%DST%" /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP >nul
            if errorlevel 8 (
                start "" explorer.exe /select,"%ZIP%"
                start "" "%DST%\$exe"
                goto end
            )
            start "" "%DST%\$exe"
            rd /s /q "%STAGE%" 2>nul
            del /q "%ZIP%" 2>nul
            :end
            (goto) 2>nul & del "%~f0"
            """.trimIndent().replace("\n", "\r\n") + "\r\n"

        private fun startHelper(helper: Path) {
            // `start "" /min <helper>`: a new, minimised console that outlives this process. The
            // empty title is `start`'s own quirk — its first quoted argument is the window title,
            // so a quoted path given first would be taken as one.
            val builder = ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/min", helper.toString())
                .directory(helper.parent.toFile())
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
            try {
                builder.start()
            } catch (e: IOException) {
                throw UpdateException("Could not start the update helper: ${e.message}", reachedServer = true, cause = e)
            }
        }

        private fun deleteTree(folder: Path) {
            if (!Files.exists(folder)) return
            Files.walk(folder).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
