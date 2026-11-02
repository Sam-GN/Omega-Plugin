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
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two installers against temp folders, with the launch injected: the sequence each performs
 * and the words each refuses with, without starting a process or needing either platform.
 */
class InstallerTest {

    private fun tempDir(): Path = Files.createTempDirectory("omega-install")

    /** What staging leaves in [temp]: its folders and helpers, by their prefix. */
    private fun stages(temp: Path): List<Path> =
        Files.list(temp).use { it.toList() }.filter { it.fileName.toString().startsWith(WindowsInstaller.STAGE_PREFIX) }

    private fun deleteTree(folder: Path) {
        if (!Files.exists(folder)) return
        Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } }
    }

    // ---------------------------------------------------------------- AppImage

    @Test
    fun `appimage - not run from an image cannot apply but still has somewhere to download to`() {
        val installer = AppImageInstaller(image = null, probe = { null }) { error("must not launch") }
        assertEquals(Installer.NOT_FROM_BUNDLE, installer.cannotApply())
        assertTrue(Files.isDirectory(installer.downloadFolder()))
    }

    /** An installed image and a staged download beside it, the shape [Updater] leaves. */
    private fun images(folder: Path): Pair<Path, Path> {
        val installed = folder.resolve("Omega-Plugin-1.8.0-beta-Linux.AppImage").also { Files.writeString(it, "old") }
        val staged = folder.resolve(".Omega-Plugin-1.8.1-beta-Linux.AppImage.new").also { Files.writeString(it, "new") }
        return installed to staged
    }

    @Test
    fun `appimage - the new image takes the running image's path and starts from it`() {
        val folder = tempDir()
        try {
            val (installed, staged) = images(folder)
            val launched = mutableListOf<Path>()
            val installer = AppImageInstaller(installed, { null }) { launched.add(it) }
            assertNull(installer.cannotApply())
            assertEquals(folder.toRealPath(), installer.downloadFolder())
            val progress = mutableListOf<String>()
            val finish = installer.stage(staged) { progress += it }
            // Everything that can fail has happened by now; only the restart is left.
            assertTrue(launched.isEmpty())
            assertEquals("new", Files.readString(installed))
            assertFalse(Files.exists(staged))
            finish()
            assertEquals(listOf(installed.toRealPath()), launched)
            assertEquals(1, Files.list(folder).use { it.count() }, "one program in the folder")
            assertTrue(progress.isNotEmpty())
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - the new image is run before the old one is touched`() {
        val folder = tempDir()
        try {
            val (installed, staged) = images(folder)
            var probed: Path? = null
            val installer = AppImageInstaller(installed, { image ->
                probed = image
                assertEquals("old", Files.readString(installed), "old image intact during the test")
                assertTrue(Files.isExecutable(image), "marked executable before it is run")
                null
            }) { }
            installer.stage(staged) { }
            assertEquals(staged.toAbsolutePath(), probed)
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - an image that will not run changes nothing and starts nothing`() {
        val folder = tempDir()
        try {
            val (installed, staged) = images(folder)
            val installer = AppImageInstaller(installed, { "it exited with code 127" }) { error("must not launch") }
            val failure = assertFailsWith<UpdateException> { installer.stage(staged) { } }
            assertContains(failure.message!!, AppImageInstaller.WOULD_NOT_START)
            assertContains(failure.message!!, "127")
            assertEquals("old", Files.readString(installed))
            assertFalse(Files.exists(staged), "the download is deleted")
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - a renamed image keeps the name the user gave it`() {
        val folder = tempDir()
        try {
            val installed = folder.resolve("omega.AppImage").also { Files.writeString(it, "old") }
            val staged = folder.resolve(".download.new").also { Files.writeString(it, "new") }
            AppImageInstaller(installed, { null }) { }.stage(staged) { }()
            assertEquals("new", Files.readString(installed))
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - started through a symlink, the file is replaced and the link survives`() {
        val folder = tempDir()
        try {
            val apps = Files.createDirectory(folder.resolve("apps"))
            val bin = Files.createDirectory(folder.resolve("bin"))
            val (real, staged) = images(apps)
            // A standard Windows account can create a symlink only in Developer Mode. A test of the
            // Linux swap is not a reason for the Windows build to fail, so there it is skipped.
            val link = runCatching { Files.createSymbolicLink(bin.resolve("omega"), real) }.getOrElse { e ->
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "this account cannot create a symlink: ${e.message}")
                error("unreachable")
            }
            val installer = AppImageInstaller(link, { null }) { }
            assertEquals(apps.toRealPath(), installer.downloadFolder(), "the file's folder, for a same-disk rename")
            installer.stage(staged) { }()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("new", Files.readString(link))
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - the staged name is not an AppImage to anything watching the folder`() {
        val name = AppImageInstaller(null, { null }) { }.stagedName("Omega-Plugin-1.8.1-beta-Linux.AppImage")
        assertTrue(name.startsWith("."))
        assertFalse(name.endsWith(".AppImage"))
    }

    @Test
    fun `appimage - the child loses the runtime's variables and is kept from AppImageLauncher`() {
        val env = mapOf("APPIMAGE" to "/x/a.AppImage", "APPDIR" to "/tmp/.mount_x", "OWD" to "/x", "ARGV0" to "a", "HOME" to "/home/u", "PATH" to "/bin")
        assertEquals(
            mapOf("HOME" to "/home/u", "PATH" to "/bin", "APPIMAGELAUNCHER_DISABLE" to "1"),
            AppImageInstaller.childEnvironment(env),
        )
    }

    // ---------------------------------------------------------------- Whose image APPIMAGE names

    @Test
    fun `APPIMAGE is ours when this runtime is inside APPDIR`() {
        val mount = tempDir()
        try {
            val home = Files.createDirectories(mount.resolve("runtime"))
            assertEquals(Path.of("/apps/Omega-Plugin-Linux.AppImage"), Updates.ownAppImage("/apps/Omega-Plugin-Linux.AppImage", mount.toString(), home.toString()))
        } finally {
            deleteTree(mount)
        }
    }

    @Test
    fun `APPIMAGE inherited from another AppImage is not ours, so it is never replaced`() {
        val theirs = tempDir()
        val ours = tempDir()
        try {
            val home = Files.createDirectories(ours.resolve("jdk"))
            assertNull(Updates.ownAppImage("/apps/SomeOtherApp.AppImage", theirs.toString(), home.toString()))
            // A folder whose name merely starts with the mount's is not inside it.
            val lookalike = Files.createDirectories(Path.of(theirs.toString() + "-x").resolve("runtime"))
            try {
                assertNull(Updates.ownAppImage("/apps/SomeOtherApp.AppImage", theirs.toString(), lookalike.toString()))
            } finally {
                deleteTree(lookalike.parent)
            }
        } finally {
            deleteTree(theirs)
            deleteTree(ours)
        }
    }

    @Test
    fun `APPIMAGE without APPDIR, or with nothing behind it, is not ours`() {
        val home = tempDir()
        try {
            assertNull(Updates.ownAppImage("/apps/x.AppImage", null, home.toString()))
            assertNull(Updates.ownAppImage("/apps/x.AppImage", "", home.toString()))
            assertNull(Updates.ownAppImage("/apps/x.AppImage", home.resolve("gone").toString(), home.toString()))
            assertNull(Updates.ownAppImage(null, home.toString(), home.toString()))
        } finally {
            deleteTree(home)
        }
    }

    // ---------------------------------------------------------------- A path Java cannot hold

    @Test
    fun `a path that cannot be represented gives a copy that downloads, not a dialog that never opens`() {
        // A NUL is the one character no platform's Path accepts, under any locale, which is what
        // stands in here for a name the locale cannot encode.
        val installer = Updates.guarded { AppImageInstaller(Path.of("/opt/omega\u0000.AppImage")) }
        assertEquals(UnreadablePathInstaller, installer)
        assertEquals(UnreadablePathInstaller.REASON, installer.cannotApply())
        assertNotEquals(Installer.NOT_FROM_BUNDLE, installer.cannotApply(), "it is from a bundle")
    }

    @Test
    fun `a path that can be represented is left alone`() {
        val installer = AppImageInstaller(null, { null }) { }
        assertEquals(installer, Updates.guarded { installer })
    }

    @Test
    fun `an installer with no path refuses to stage rather than guessing`() {
        assertFailsWith<UpdateException> { UnreadablePathInstaller.stage(Path.of("x")) { } }
    }

    // ---------------------------------------------------------------- Windows

    @Test
    fun `windows - not run from the bundle cannot apply`() {
        val installer = WindowsInstaller(launcher = null, temp = tempDir(), pid = 1) { error("must not run") }
        assertEquals(Installer.NOT_FROM_BUNDLE, installer.cannotApply())
    }

    @Test
    fun `windows - a folder that cannot be written to is refused with the words to act on`() {
        val folder = tempDir()
        try {
            // A regular file where the install folder should be: nothing can be created under it,
            // on any filesystem, which is the one portable way to make a path unwritable.
            val notAFolder = folder.resolve("blocked").also { Files.writeString(it, "x") }
            val installer = WindowsInstaller(notAFolder.resolve("OmegaPlugin.exe"), folder, 1) { }
            assertEquals(WindowsInstaller.NOT_WRITABLE, installer.cannotApply())
            assertFalse(WindowsInstaller.isWritable(notAFolder))
            assertTrue(WindowsInstaller.isWritable(folder))
        } finally {
            deleteTree(folder)
        }
    }

    private fun writeBundleZip(zip: Path, root: String) {
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            fun put(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            put("$root/", "")
            put("$root/OmegaPlugin.exe", "exe")
            put("$root/app/", "")
            put("$root/app/OmegaPlugin-1.8.1-beta-all.jar", "jar")
            put("$root/app/OmegaPlugin.cfg", "cfg")
            put("$root/runtime/bin/java.dll", "dll")
            put("$root/README.md", "readme")
        }
    }

    @Test
    fun `windows - staging unpacks the zip, finds the bundle root and writes the helper`() {
        val temp = tempDir()
        val install = tempDir()
        try {
            val launcher = install.resolve("OmegaPlugin.exe").also { Files.writeString(it, "old exe") }
            val zip = temp.resolve("Omega-Plugin-1.8.1-beta-Windows.zip")
            writeBundleZip(zip, "OmegaPlugin-1.8.1-beta")
            val ran = mutableListOf<Path>()
            val installer = WindowsInstaller(launcher, temp, pid = 4242) { ran.add(it) }
            assertNull(installer.cannotApply())
            assertEquals(temp, installer.downloadFolder())

            val finish = installer.stage(zip) { }
            val stage = stages(temp).single { Files.isDirectory(it) }
            assertTrue(Files.isRegularFile(stage.resolve(WindowsInstaller.STAGE_MARKER)), "marked as ours")
            val root = stage.resolve("OmegaPlugin-1.8.1-beta")
            assertTrue(Files.isRegularFile(root.resolve("OmegaPlugin.exe")))
            assertTrue(Files.isRegularFile(root.resolve("app/OmegaPlugin-1.8.1-beta-all.jar")))
            assertTrue(Files.isRegularFile(root.resolve("runtime/bin/java.dll")))
            assertEquals(root, WindowsInstaller.findBundleRoot(stage, "OmegaPlugin.exe"))
            // Nothing in the installed folder has been touched: that is the helper's job, after exit.
            assertEquals("old exe", Files.readString(launcher))

            val helper = temp.resolve(stage.fileName.toString() + ".cmd")
            assertTrue(Files.isRegularFile(helper))
            assertTrue(ran.isEmpty(), "the helper is not started by staging")
            finish()
            assertEquals(listOf(helper), ran)
        } finally {
            deleteTree(temp)
            deleteTree(install)
        }
    }

    @Test
    fun `windows - a zip without the launcher is refused and cleaned up`() {
        val temp = tempDir()
        val install = tempDir()
        try {
            val launcher = install.resolve("OmegaPlugin.exe").also { Files.writeString(it, "exe") }
            val zip = temp.resolve("bad.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                out.putNextEntry(ZipEntry("README.md")); out.write(1); out.closeEntry()
            }
            val installer = WindowsInstaller(launcher, temp, 1) { error("must not run") }
            val failure = runCatching { installer.stage(zip) { } }.exceptionOrNull()
            assertTrue(failure is UpdateException, "refused: $failure")
            assertContains(failure.message!!, "OmegaPlugin.exe")
            assertTrue(stages(temp).isEmpty(), "the stage is cleaned up")
        } finally {
            deleteTree(temp)
            deleteTree(install)
        }
    }

    @Test
    fun `windows - unzip refuses an entry that escapes the folder`() {
        val temp = tempDir()
        try {
            val zip = temp.resolve("slip.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                out.putNextEntry(ZipEntry("../escaped.txt")); out.write(1); out.closeEntry()
            }
            val into = temp.resolve("into").also { Files.createDirectories(it) }
            val failure = runCatching { WindowsInstaller.unzip(zip, into) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(Files.exists(temp.resolve("escaped.txt")))
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun `windows - the helper script is the golden copy`() {
        val script = WindowsInstaller.helperScript(
            pid = 4242,
            source = Path.of("C:\\Users\\Pat Smith\\AppData\\Local\\Temp\\omega-update\\OmegaPlugin-1.8.1-beta"),
            target = Path.of("C:\\Users\\Pat Smith\\Desktop\\OmegaPlugin-1.8.0-beta"),
            exe = "OmegaPlugin.exe",
            zip = Path.of("C:\\Users\\Pat Smith\\AppData\\Local\\Temp\\Omega-Plugin-1.8.1-beta-Windows.zip"),
            expanded = Path.of("C:\\Users\\Pat Smith\\AppData\\Local\\Temp\\omega-update"),
        )
        val golden = checkNotNull(javaClass.getResourceAsStream("/update/omega-update.cmd")) { "golden helper missing" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            // The golden copy is stored with LF; the script is written with CRLF, which is what cmd wants.
            .replace("\r\n", "\n").replace("\n", "\r\n")
        assertEquals(golden, script)
        // The properties a reader would check by eye, stated so a golden-copy update cannot lose them.
        assertContains(script, "chcp 65001 >nul", message = "cmd reads the file in the OEM code page; this makes it UTF-8")
        assertContains(script, "setlocal DisableDelayedExpansion", message = "a ! in a path must survive a cmd set to expand late")
        assertContains(script, "\"%SystemRoot%\\System32\\tasklist.exe\" /FI \"PID eq %PID%\"")
        assertContains(script, "| \"%SystemRoot%\\System32\\find.exe\" \"%PID%\"", message = "a bare find can be GNU find")
        assertContains(script, "\"%SRC%\\app\" \"%DST%\\app\" /MIR", message = "a file a version dropped must go")
        assertContains(script, "\"%SRC%\\runtime\" \"%DST%\\runtime\" /MIR", message = "a file a version dropped must go")
        assertContains(script, "\"%SRC%\" \"%DST%\" /E", message = "the folder itself gets a plain copy")
        assertFalse(script.contains("\"%SRC%\" \"%DST%\" /MIR"), "that would delete what the user keeps beside the exe")
        assertFalse(Regex("""^\s*(tasklist|find|ping|robocopy|explorer)(\.exe)?\s""", RegexOption.MULTILINE).containsMatchIn(script), "every external command by full path")
        assertContains(script, "if errorlevel 8")
        assertContains(script, "start \"\" \"%DST%\\OmegaPlugin.exe\"")
        assertContains(script, "(goto) 2>nul & del \"%~f0\" & exit", message = "start runs a batch file under cmd /K; without the exit the console stays")
    }

    @Test
    fun `windows - the helper is started by its bare name from its own folder`() {
        // %TEMP% is under the user's profile, and a profile with a & in its name split the command.
        val helper = Path.of("C:\\Users\\Tom & Jerry\\AppData\\Local\\Temp\\omega-update-4242.cmd")
        val command = WindowsInstaller.launchCommand(helper)
        assertEquals(listOf("cmd.exe", "/c", "start", "\"\"", "/min", "omega-update-4242.cmd"), command)
        assertTrue(command.none { it.contains('\\') || it.contains('&') }, "no path crosses the command line: $command")
    }

    @Test
    fun `windows - a percent sign in a path reaches cmd doubled, so it is not dropped`() {
        // cmd drops a lone % from a batch file's line: "D:\50% off" would be read as "D:\50 off".
        val script = WindowsInstaller.helperScript(
            pid = 1,
            source = Path.of("C:\\Users\\100%\\AppData\\Local\\Temp\\omega-update\\OmegaPlugin"),
            target = Path.of("D:\\50% off\\OmegaPlugin"),
            exe = "OmegaPlugin.exe",
            zip = Path.of("C:\\Users\\100%\\AppData\\Local\\Temp\\Omega-Plugin-2.0.4-beta-Windows.zip"),
            expanded = Path.of("C:\\Users\\100%\\AppData\\Local\\Temp\\omega-update"),
        )
        assertContains(script, "set \"DST=D:\\50%% off\\OmegaPlugin\"")
        assertContains(script, "set \"SRC=C:\\Users\\100%%\\AppData\\Local\\Temp\\omega-update\\OmegaPlugin\"")
        assertContains(script, "set \"STAGE=C:\\Users\\100%%\\AppData\\Local\\Temp\\omega-update\"")
        assertFalse(Regex("""set "[A-Z]+=[^"]*(?<!%)%(?!%)[^"]*"""").containsMatchIn(script), "no lone % in any set line")
        // The lines that use the values are untouched: %DST% is still the variable, not %%DST%%.
        assertContains(script, "\"%SRC%\\app\" \"%DST%\\app\" /MIR")
    }

    @Test
    fun `windows - a bundle with an empty app or runtime folder is refused before the helper exists`() {
        // robocopy /MIR from an empty folder empties the other one and exits 2, a success. The
        // helper must never be written for such a bundle.
        for (missing in WindowsInstaller.MIRRORED) {
            val temp = tempDir()
            val install = tempDir()
            try {
                val launcher = install.resolve("OmegaPlugin.exe").also { Files.writeString(it, "old exe") }
                Files.createDirectories(install.resolve("app")).resolve("OmegaPlugin.jar").also { Files.writeString(it, "installed jar") }
                val zip = temp.resolve("hollow.zip")
                ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                    fun put(name: String, content: String) {
                        out.putNextEntry(ZipEntry(name)); out.write(content.toByteArray()); out.closeEntry()
                    }
                    put("OmegaPlugin/OmegaPlugin.exe", "exe")
                    put("OmegaPlugin/$missing/", "")
                    val other = WindowsInstaller.MIRRORED.first { it != missing }
                    put("OmegaPlugin/$other/something", "x")
                }
                val installer = WindowsInstaller(launcher, temp, 1) { error("must not run") }
                val failure = runCatching { installer.stage(zip) { } }.exceptionOrNull()
                assertTrue(failure is UpdateException, "refused for an empty $missing: $failure")
                assertContains(failure.message!!, missing)
                assertTrue(stages(temp).isEmpty(), "no stage and no helper for an empty $missing")
                assertEquals("installed jar", Files.readString(install.resolve("app/OmegaPlugin.jar")), "the installed program is untouched")
            } finally {
                deleteTree(temp)
                deleteTree(install)
            }
        }
    }

    @Test
    fun `windows - staging never touches a folder or file of the same old name, and each stage is new`() {
        val temp = tempDir()
        val install = tempDir()
        try {
            // What 2.0.5 and earlier would have emptied: names nobody can be sure are ours.
            val theirs = Files.createDirectories(temp.resolve("omega-update")).resolve("their file.txt").also { Files.writeString(it, "theirs") }
            val theirScript = temp.resolve("omega-update.cmd").also { Files.writeString(it, "theirs") }
            val launcher = install.resolve("OmegaPlugin.exe").also { Files.writeString(it, "old exe") }
            val zip = temp.resolve("Omega-Plugin-2.0.6-beta-Windows.zip")
            writeBundleZip(zip, "OmegaPlugin")
            val installer = WindowsInstaller(launcher, temp, 1) { }
            installer.stage(zip) { }
            installer.stage(zip) { }
            assertEquals("theirs", Files.readString(theirs))
            assertEquals("theirs", Files.readString(theirScript))
            assertEquals(2, stages(temp).count { Files.isDirectory(it) }, "a new folder each time")
        } finally {
            deleteTree(temp)
            deleteTree(install)
        }
    }

    // ---------------------------------------------------------------- links, roots and cmd's settings

    /**
     * A directory link at [link] to [target]: a symlink where the account may make one, else a
     * junction, which a standard Windows account can make. False when neither is possible.
     */
    private fun linkTo(link: Path, target: Path): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return true
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        return process.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS)
    }

    @Test
    fun `windows - a link inside app or runtime is refused before anything is copied`() {
        // robocopy /MIR removes old files through a junction in the destination, with /XJ or without:
        // a folder with a link in it is download-only, said as About opens and again at stage.
        for (name in WindowsInstaller.MIRRORED) {
            val temp = tempDir()
            val install = tempDir()
            val outside = tempDir()
            val link = install.resolve(name).resolve("linked")
            try {
                Files.writeString(outside.resolve("precious.txt"), "keep")
                val launcher = install.resolve("OmegaPlugin.exe").also { Files.writeString(it, "old exe") }
                for (folder in WindowsInstaller.MIRRORED) {
                    Files.createDirectories(install.resolve(folder)).resolve("file").also { Files.writeString(it, "x") }
                }
                org.junit.jupiter.api.Assumptions.assumeTrue(linkTo(link, outside), "this account can make neither a symlink nor a junction")
                assertTrue(WindowsInstaller.linkInside(install.resolve(name)))
                assertFalse(WindowsInstaller.linkInside(install.resolve(WindowsInstaller.MIRRORED.first { it != name })))
                val installer = WindowsInstaller(launcher, temp, 1) { error("must not run") }
                assertEquals(WindowsInstaller.HAS_LINKS, installer.cannotApply())
                val zip = temp.resolve("Omega-Plugin-Windows.zip")
                writeBundleZip(zip, "OmegaPlugin")
                val failure = runCatching { installer.stage(zip) { } }.exceptionOrNull()
                assertTrue(failure is UpdateException, "refused for a link in $name: $failure")
                assertEquals(WindowsInstaller.HAS_LINKS, failure.message)
                assertTrue(stages(temp).isEmpty(), "no stage and no helper for a folder with a link in it")
                assertEquals("keep", Files.readString(outside.resolve("precious.txt")))
            } finally {
                Files.deleteIfExists(link)
                deleteTree(temp)
                deleteTree(install)
                deleteTree(outside)
            }
        }
    }

    @Test
    fun `windows - removing a stage deletes a link in it, not what is on the other side of it`() {
        // Files.walk descends into a junction, which Java reports as a directory: a junction inside
        // a stage being removed had the folder it pointed at emptied. A failed unpack and the sweep
        // of abandoned stages both remove a stage through this.
        val stage = tempDir()
        val outside = tempDir()
        val link = stage.resolve("linked")
        try {
            Files.writeString(outside.resolve("precious.txt"), "keep")
            Files.writeString(stage.resolve(WindowsInstaller.STAGE_MARKER), "x")
            Files.createDirectories(stage.resolve("OmegaPlugin/app")).resolve("stale.jar").also { Files.writeString(it, "old") }
            org.junit.jupiter.api.Assumptions.assumeTrue(linkTo(link, outside), "this account can make neither a symlink nor a junction")
            WindowsInstaller.deleteTree(stage)
            assertEquals("keep", Files.readString(outside.resolve("precious.txt")), "the link was removed, not followed")
            assertFalse(Files.exists(stage, LinkOption.NOFOLLOW_LINKS), "the stage went, the link in it with it")
        } finally {
            Files.deleteIfExists(link)
            deleteTree(stage)
            deleteTree(outside)
        }
    }

    @Test
    fun `windows - a program at the root of a drive is refused before the write probe`() {
        // robocopy reads "D:\" as an escaped quote and fails with 16. The root is refused instead,
        // and ahead of isWritable, which would otherwise try to create a file there.
        val temp = tempDir()
        try {
            val root = checkNotNull(temp.toAbsolutePath().root)
            val installer = WindowsInstaller(root.resolve("OmegaPlugin.exe"), temp, 1) { error("must not run") }
            assertEquals(WindowsInstaller.AT_ROOT, installer.cannotApply())
            val failure = assertFailsWith<UpdateException> { installer.stage(temp.resolve("nothing.zip")) { } }
            assertEquals(WindowsInstaller.AT_ROOT, failure.message)
            assertTrue(stages(temp).isEmpty(), "no stage and no helper")
        } finally {
            deleteTree(temp)
        }
    }

    @Test
    fun `windows - the helper turns delayed expansion off before it reads a path`() {
        // Under cmd /V:ON, or the registry default that means the same, "D:\Music!\OmegaPlugin" is
        // read as "D:\Music\OmegaPlugin". The script cannot know how a user's cmd is set, so it says.
        val script = WindowsInstaller.helperScript(
            pid = 1,
            source = Path.of("C:\\Users\\Pat\\AppData\\Local\\Temp\\omega-update\\OmegaPlugin"),
            target = Path.of("D:\\Music!\\OmegaPlugin"),
            exe = "OmegaPlugin.exe",
            zip = Path.of("C:\\Users\\Pat\\AppData\\Local\\Temp\\Omega-Plugin-Windows.zip"),
            expanded = Path.of("C:\\Users\\Pat\\AppData\\Local\\Temp\\omega-update"),
        )
        val off = script.indexOf("setlocal DisableDelayedExpansion")
        assertTrue(off >= 0, "the script turns delayed expansion off")
        assertTrue(off < script.indexOf("set \""), "before the first set line")
        assertContains(script, "set \"DST=D:\\Music!\\OmegaPlugin\"", message = "the ! is written as it is; setlocal is what protects it")
    }
}
