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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecksumsTest {

    /** SHA-256 of the ASCII bytes `abc`, from FIPS 180-4's own worked example. */
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `parses sha256sum's format, text and binary modes, and ignores the rest`() {
        val sums = Checksums.parse(
            """
            # comment
            $abc  Omega-Plugin-1.8.1-beta-Windows.zip
            $abc *Omega-Plugin-1.8.1-beta-Linux.AppImage
            ${abc.uppercase()}  dist/SHA256SUMS.txt
            not a checksum line
            0123  too-short.bin
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "Omega-Plugin-1.8.1-beta-Windows.zip" to abc,
                "Omega-Plugin-1.8.1-beta-Linux.AppImage" to abc,
                "SHA256SUMS.txt" to abc,
            ),
            sums,
        )
    }

    @Test
    fun `hashes a file to the published value`() {
        val file = Files.createTempFile("omega-sum", ".bin")
        try {
            Files.write(file, "abc".toByteArray())
            assertEquals(abc, Checksums.sha256(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a matching file passes, a changed byte fails, a missing entry is named`() {
        val file = Files.createTempFile("omega-sum", ".bin")
        try {
            Files.write(file, "abc".toByteArray())
            val sums = mapOf("bundle.zip" to abc)
            assertNull(Checksums.verify(file, "bundle.zip", sums))
            Files.write(file, "abd".toByteArray())
            assertEquals(Checksums.MISMATCH, Checksums.verify(file, "bundle.zip", sums))
            val missing = Checksums.verify(file, "other.zip", sums)
            assertTrue(missing != null && missing.contains("other.zip"), "names the missing asset: $missing")
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
