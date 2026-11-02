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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Replaces the installed program with a verified download. One per platform, because a running
 * program can replace itself on Linux and cannot on Windows.
 *
 * The order of operations is the window's, not the installer's: the download and [stage] may run
 * while the stream runs, so a failure there is a line in the dialog and the program carries on. The
 * action [stage] returns runs **after** the session and settings are saved and immediately before
 * the process exits, so the new copy never reads a session the old one is still writing.
 */
interface Installer {

    /** Null when the installed copy can be replaced; otherwise why not, in the words to show. */
    fun cannotApply(): String?

    /** Where a download goes when it is going to be applied. */
    fun downloadFolder(): Path

    /**
     * What a verified download is called while it waits for [stage], given the release's name for
     * it. Only ever used where the download is going to be applied.
     */
    fun stagedName(assetName: String): String = assetName

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
 * The copy is a release bundle, but the path it runs from cannot be represented in this locale, so
 * there is nothing to replace it through. It can still download, like any copy that cannot apply.
 */
object UnreadablePathInstaller : Installer {

    const val REASON =
        "This system's language setting cannot spell the name of the folder this program is in, so " +
            "it cannot replace itself. Replace it by hand."

    override fun cannotApply(): String = REASON

    override fun downloadFolder(): Path = Updates.downloadsFolder()

    override fun stage(download: Path, progress: (String) -> Unit): () -> Unit =
        throw UpdateException(REASON, reachedServer = true)
}

/**
 * Linux: the AppImage replaces itself, at the path it is already at.
 *
 * The AppImage runtime sets `APPIMAGE` to the running image's path. The download is written beside
 * it, because a rename must stay on one filesystem, and [stage] does everything that can fail while
 * the program is still running:
 *
 * 1. **The new image is run**, with `--version`. A checksum proves the file is the one the release
 *    built, not that it starts on this machine: a newer glibc, a missing FUSE or a wrong architecture
 *    all pass it. This is the only test that matters and it costs one JVM start, so it happens while
 *    the old copy is untouched, and a failure ends with nothing changed but the download deleted.
 * 2. **It is renamed over the running image.** Atomic, so the path always holds a whole program, and
 *    safe, because the kernel keeps the old inode until the process exits. The same path whatever
 *    it is called, because everything that points at the program points at the path: a menu entry,
 *    an AppImageLauncher integration, a symlink, a file the user renamed. The release's name has no
 *    version in it, so that path never goes out of date.
 *
 * The step left for the window starts the image at that path.
 *
 * Both runs get [childEnvironment]: the runtime's own variables removed, and AppImageLauncher told
 * to stand aside. It registers itself with the kernel as the handler for every AppImage, and an
 * image it does not recognise gets a modal "integrate?" prompt instead of a program, which would
 * hang the test run and turn the promised restart into a question.
 */
class AppImageInstaller(
    /** The running image, from `APPIMAGE`, or null when not run from one. */
    private val image: Path?,
    /** Runs the new image and returns why it failed, or null. Injected so a test need not start one. */
    private val probe: (Path) -> String? = ::tryRunning,
    /** Starts the new image. Injected so the swap is testable without launching anything. */
    private val launch: (Path) -> Unit = ::startDetached,
) : Installer {

    /** The file itself: a symlink has to survive the swap, and renaming onto it would replace it. */
    private val target: Path?
        get() = image?.let { path -> runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath() } }

    override fun cannotApply(): String? {
        val running = target ?: return Installer.NOT_FROM_BUNDLE
        val folder = running.parent
        if (folder == null || !Files.isDirectory(folder) || !Files.isWritable(folder)) {
            return "Could not write to the folder holding the AppImage, $folder, so this copy " +
                "cannot replace itself. Replace the file by hand."
        }
        return null
    }

    override fun downloadFolder(): Path = target?.parent ?: Updates.downloadsFolder()

    /** Hidden and not named `.AppImage`, so nothing watching the folder takes it for an install. */
    override fun stagedName(assetName: String): String = ".$assetName.new"

    override fun stage(download: Path, progress: (String) -> Unit): () -> Unit {
        val installed = target ?: throw UpdateException(Installer.NOT_FROM_BUNDLE, reachedServer = true)
        val fresh = download.toAbsolutePath()
        try {
            if (!fresh.toFile().setExecutable(true, false) && !Files.isExecutable(fresh)) {
                throw UpdateException("Could not mark $fresh executable.", reachedServer = true)
            }
            progress("Testing the new version…")
            probe(fresh)?.let { why -> throw UpdateException("$WOULD_NOT_START ($why)", reachedServer = true) }
            // ATOMIC_MOVE replaces an existing target on Linux and ignores every other option.
            Files.move(fresh, installed, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: UpdateException) {
            runCatching { Files.deleteIfExists(fresh) }
            throw e
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(fresh) }
            throw UpdateException("Could not replace $installed: ${e.message}", reachedServer = true, cause = e)
        }
        progress("Ready to restart…")
        return { launch(installed) }
    }

    companion object {
        val RUNTIME_VARIABLES = setOf("APPIMAGE", "APPDIR", "OWD", "ARGV0")

        /** AppImageLauncher's own switch: run the image, ask nothing. Harmless where it is absent. */
        const val LAUNCHER_DISABLE = "APPIMAGELAUNCHER_DISABLE"

        const val WOULD_NOT_START =
            "The new version would not start on this machine, so this copy has been left as it was " +
                "and the download deleted."

        /** A JVM start, or an extraction and a JVM start where there is no FUSE. Generous on purpose. */
        private const val PROBE_SECONDS = 60L

        /** The environment for the new image: this process's, less the runtime's, plus the switch. */
        fun childEnvironment(parent: Map<String, String>): Map<String, String> =
            parent.filterKeys { it !in RUNTIME_VARIABLES } + (LAUNCHER_DISABLE to "1")

        private fun command(image: Path, vararg arguments: String): ProcessBuilder {
            val builder = ProcessBuilder(listOf(image.toString()) + arguments)
                .directory(image.parent?.toFile())
                .redirectError(Redirect.DISCARD)
                .redirectInput(Redirect.from(java.io.File("/dev/null")))
            val env = builder.environment()
            val child = childEnvironment(env)
            env.clear()
            env.putAll(child)
            return builder
        }

        /**
         * `--version` must stay a flag every release answers. An image that does not know it opens
         * its window instead, which fails here as no answer rather than doing any harm.
         */
        private fun tryRunning(image: Path): String? {
            val process = try {
                command(image, "--version").start()
            } catch (e: IOException) {
                return e.message ?: "it could not be run"
            }
            if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                return "no answer in $PROBE_SECONDS seconds"
            }
            if (process.exitValue() != 0) return "it exited with code ${process.exitValue()}"
            // What is already there rather than to end of stream: the runtime's FUSE helper holds
            // the other end of this pipe for a moment after the program has gone.
            val input = process.inputStream
            val answer = String(input.readNBytes(input.available()))
            return if (answer.lines().any { it.startsWith(Updates.VERSION_BANNER) }) null else "it did not answer"
        }

        private fun startDetached(image: Path) {
            try {
                command(image).redirectOutput(Redirect.DISCARD).start()
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
 * up. It copies **into the installed folder**, not beside it: the exe keeps its path, so every
 * shortcut and taskbar pin the user made keeps working, and the folder's name has no version in it
 * to go out of date.
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
            "Could not write to the program folder, so this copy cannot replace itself. Unzip the download over it by hand."

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
         * The file is UTF-8 and its second line is `chcp 65001`, because `cmd` reads a batch file in
         * the console's OEM code page, not the system's: written without it, every path here was
         * garbled for a user called José or Пётр, `robocopy` failed with code 16 and, the program
         * having already exited, nothing started it again. Measured on Windows 10 in 1.9.2.
         *
         * Every external command is named by its full path under `System32`. Git for Windows
         * offers to put its Unix tools on the PATH, and then `find` is GNU find, which takes the
         * PID for a directory and fails: the wait loop ended at once, `robocopy` ran against a
         * program that was still open, and the copy failed. Measured on Windows 10 in 1.9.2, from a
         * shell with Git's tools first on the PATH.
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
            chcp 65001 >nul
            set "PID=$pid"
            set "SRC=$source"
            set "DST=$target"
            set "ZIP=$zip"
            set "STAGE=$expanded"
            title Omega-Plugin update
            :wait
            "%SystemRoot%\System32\tasklist.exe" /FI "PID eq %PID%" 2>nul | "%SystemRoot%\System32\find.exe" "%PID%" >nul
            if not errorlevel 1 (
                "%SystemRoot%\System32\ping.exe" -n 2 127.0.0.1 >nul
                goto wait
            )
            del /q "%DST%\app\*.jar" 2>nul
            "%SystemRoot%\System32\robocopy.exe" "%SRC%" "%DST%" /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP >nul
            if errorlevel 8 (
                start "" "%SystemRoot%\explorer.exe" /select,"%ZIP%"
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
