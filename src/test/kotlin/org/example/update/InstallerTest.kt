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
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two installers against temp folders, with the launch injected: the sequence each performs
 * and the words each refuses with, without starting a process or needing either platform.
 */
class InstallerTest {

    private fun tempDir(): Path = Files.createTempDirectory("omega-install")

    private fun deleteTree(folder: Path) {
        if (!Files.exists(folder)) return
        Files.walk(folder).use { it.sorted(Comparator.reverseOrder()).forEach { p -> Files.deleteIfExists(p) } }
    }

    // ---------------------------------------------------------------- AppImage

    @Test
    fun `appimage - not run from an image cannot apply but still has somewhere to download to`() {
        val installer = AppImageInstaller(image = null) { error("must not launch") }
        assertEquals(Installer.NOT_FROM_BUNDLE, installer.cannotApply())
        assertTrue(Files.isDirectory(installer.downloadFolder()))
    }

    @Test
    fun `appimage - the swap launches the new image and then deletes the old`() {
        val folder = tempDir()
        try {
            val old = folder.resolve("Omega-Plugin-1.8.0-beta-Linux.AppImage").also { Files.writeString(it, "old") }
            val fresh = folder.resolve("Omega-Plugin-1.8.1-beta-Linux.AppImage").also { Files.writeString(it, "new") }
            val launched = mutableListOf<Path>()
            val installer = AppImageInstaller(old) { image ->
                launched.add(image)
                // The old image must still be there while the new one starts.
                assertTrue(Files.exists(old), "old image kept until the new one is running")
            }
            assertNull(installer.cannotApply())
            assertEquals(folder, installer.downloadFolder())
            val progress = mutableListOf<String>()
            val finish = installer.stage(fresh) { progress += it }
            // Staging alone changes nothing on disk but the permission bit.
            assertTrue(Files.exists(old))
            assertTrue(launched.isEmpty())
            finish()
            assertEquals(listOf(fresh.toAbsolutePath()), launched)
            assertFalse(Files.exists(old), "old image deleted after the launch")
            assertTrue(Files.exists(fresh))
            assertTrue(progress.isNotEmpty())
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - a download with the old image's exact name is not deleted`() {
        val folder = tempDir()
        try {
            val image = folder.resolve("omega.AppImage").also { Files.writeString(it, "same file") }
            val installer = AppImageInstaller(image) { }
            installer.stage(image) { }()
            assertTrue(Files.exists(image))
        } finally {
            deleteTree(folder)
        }
    }

    @Test
    fun `appimage - the child's environment loses the runtime's variables and nothing else`() {
        val env = mapOf("APPIMAGE" to "/x/a.AppImage", "APPDIR" to "/tmp/.mount_x", "OWD" to "/x", "ARGV0" to "a", "HOME" to "/home/u", "PATH" to "/bin")
        assertEquals(mapOf("HOME" to "/home/u", "PATH" to "/bin"), AppImageInstaller.childEnvironment(env))
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
            val stage = temp.resolve(WindowsInstaller.STAGE_FOLDER)
            val root = stage.resolve("OmegaPlugin-1.8.1-beta")
            assertTrue(Files.isRegularFile(root.resolve("OmegaPlugin.exe")))
            assertTrue(Files.isRegularFile(root.resolve("app/OmegaPlugin-1.8.1-beta-all.jar")))
            assertTrue(Files.isRegularFile(root.resolve("runtime/bin/java.dll")))
            assertEquals(root, WindowsInstaller.findBundleRoot(stage, "OmegaPlugin.exe"))
            // Nothing in the installed folder has been touched: that is the helper's job, after exit.
            assertEquals("old exe", Files.readString(launcher))

            val helper = temp.resolve(WindowsInstaller.HELPER_NAME)
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
            assertFalse(Files.exists(temp.resolve(WindowsInstaller.STAGE_FOLDER)))
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
        assertContains(script, "tasklist /FI \"PID eq %PID%\"")
        assertContains(script, "del /q \"%DST%\\app\\*.jar\"")
        assertContains(script, "robocopy \"%SRC%\" \"%DST%\" /E")
        assertContains(script, "if errorlevel 8")
        assertContains(script, "start \"\" \"%DST%\\OmegaPlugin.exe\"")
        assertContains(script, "(goto) 2>nul & del \"%~f0\"")
    }
}
