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
package org.example.ui

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The placement rules against monitors described as rectangles, so none of this needs a display:
 * a primary screen with a taskbar and a second screen to its left, which is where the negative
 * coordinates come from.
 */
class WindowPlacementTest {

    private val primary = Rectangle(0, 0, 2560, 1400)
    private val left = Rectangle(-1920, 200, 1920, 1040)
    private val screens = listOf(primary, left)

    @Test
    fun `text round-trips, with and without maximised`() {
        val normal = WindowPlacement(Rectangle(100, 60, 1200, 800), maximised = false)
        val max = WindowPlacement(Rectangle(-1800, 300, 1000, 700), maximised = true)
        assertEquals("100,60,1200,800", normal.text())
        assertEquals("-1800,300,1000,700,max", max.text())
        assertEquals(normal, WindowPlacement.parse(normal.text()))
        assertEquals(max, WindowPlacement.parse(max.text()))
    }

    @Test
    fun `the old four-part form and spaces still parse, nonsense does not`() {
        assertEquals(
            WindowPlacement(Rectangle(10, 20, 900, 500), false),
            WindowPlacement.parse(" 10, 20 ,900,500 "),
        )
        assertNull(WindowPlacement.parse(null))
        assertNull(WindowPlacement.parse(""))
        assertNull(WindowPlacement.parse("10,20,900"))
        assertNull(WindowPlacement.parse("10,20,900,500,huge"))
        assertNull(WindowPlacement.parse("a,b,c,d"))
        assertNull(WindowPlacement.parse("10,20,50,500"), "a width under the minimum is not a window")
        assertNull(WindowPlacement.parse("10,20,900,50"), "nor is a height under it")
    }

    @Test
    fun `a window on a monitor that is still there comes back as it was`() {
        val saved = WindowPlacement(Rectangle(-1500, 400, 1000, 600), false)
        val r = assertNotNull(WindowPlacement.resolve(saved, screens))
        assertEquals(saved.bounds, r.bounds)
        assertEquals(left, r.workArea)
        assertFalse(r.maximised)
        assertEquals(Point(-1000, 700), r.centre)
    }

    @Test
    fun `a window on a monitor that has gone is not restored`() {
        val saved = WindowPlacement(Rectangle(-1500, 400, 1000, 600), false)
        assertNull(WindowPlacement.resolve(saved, listOf(primary)))
        assertNull(WindowPlacement.resolve(null, screens))
    }

    @Test
    fun `the title bar has to be reachable, not just some corner of the window`() {
        // Hanging off the right edge with 100 px of bar still on screen: fine.
        assertNotNull(WindowPlacement.resolve(WindowPlacement(Rectangle(2460, 300, 1000, 600), false), screens))
        // Only 60 px of bar on screen: not enough to grab.
        assertNull(WindowPlacement.resolve(WindowPlacement(Rectangle(2500, 300, 1000, 600), false), screens))
        // The bar is above the top of the screen, the body is on it: the user could not move it.
        assertNull(WindowPlacement.resolve(WindowPlacement(Rectangle(500, -30, 1000, 600), false), screens))
        // The bar is below the work area, which is where the taskbar is.
        assertNull(WindowPlacement.resolve(WindowPlacement(Rectangle(500, 1390, 1000, 600), false), screens))
    }

    @Test
    fun `a size larger than the monitor is cut down to it, the position is kept`() {
        val saved = WindowPlacement(Rectangle(-1900, 250, 2400, 1300), false)
        val r = assertNotNull(WindowPlacement.resolve(saved, screens))
        assertEquals(Rectangle(-1900, 250, 1920, 1040), r.bounds)
    }

    @Test
    fun `maximised comes back maximised on its own monitor, with the normal bounds kept`() {
        val saved = WindowPlacement(Rectangle(-1500, 400, 1000, 600), true)
        val r = assertNotNull(WindowPlacement.resolve(saved, screens))
        assertTrue(r.maximised)
        assertEquals(saved.bounds, r.bounds)
        assertEquals(left, r.workArea)
        assertEquals(Point(left.x + left.width / 2, left.y + left.height / 2), r.centre, "the logo goes to the middle of the screen it will fill")
    }

    @Test
    fun `when the bar straddles two monitors the one holding more of it wins`() {
        val saved = WindowPlacement(Rectangle(-300, 400, 1000, 600), false)
        val r = assertNotNull(WindowPlacement.resolve(saved, screens))
        assertEquals(primary, r.workArea)
    }
}
