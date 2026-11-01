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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionsTest {

    private fun v(text: String): Version = assertNotNull(Versions.parse(text), "parses: $text")

    @Test
    fun `numbers compare before anything else`() {
        assertTrue(v("1.8.1-beta") > v("1.8.0-beta"))
        assertTrue(v("1.10.0-beta") > v("1.9.9-beta"))
        assertTrue(v("2.0.0-beta") > v("1.99.99"))
    }

    @Test
    fun `a final release beats its own beta`() {
        assertTrue(v("1.8.0") > v("1.8.0-beta"))
        assertTrue(v("1.8.0-beta") < v("1.8.0"))
    }

    @Test
    fun `two suffixes compare as text`() {
        assertTrue(v("1.8.0-beta") > v("1.8.0-alpha"))
        assertTrue(v("1.8.0-rc1") > v("1.8.0-beta"))
    }

    @Test
    fun `the leading v is optional and the spelling is canonical`() {
        assertEquals(v("1.8.1-beta"), v("v1.8.1-beta"))
        assertEquals("1.8.1-beta", v("v1.8.1-beta").toString())
        assertEquals("1.8.1", v("V1.8.1").toString())
    }

    @Test
    fun `equal versions are equal however many trailing zeros`() {
        assertEquals(0, v("1.8").compareTo(v("1.8.0")))
        assertEquals(v("1.8"), v("1.8.0"))
        assertEquals(v("1.8").hashCode(), v("1.8.0").hashCode())
    }

    @Test
    fun `a tag that is not a version is null, not an exception`() {
        assertNull(Versions.parse("nightly"))
        assertNull(Versions.parse(""))
        assertNull(Versions.parse("v"))
        assertNull(Versions.parse("1.8.x"))
        assertNull(Versions.parse("1.8.0-"))
        assertNull(Versions.parse("latest"))
    }
}
