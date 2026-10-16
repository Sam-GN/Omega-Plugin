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

import java.awt.Cursor
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JFrame

/**
 * Edge-drag resizing for an undecorated frame.
 *
 * An undecorated window has no resize border, so this reimplements it: the outer shell carries a few
 * pixels of empty margin that no child covers, and a press inside that margin resizes instead of
 * doing nothing.
 *
 * Install on a component whose own border leaves at least [MARGIN] px uncovered on every side —
 * otherwise children swallow the events and the window simply cannot be resized.
 */
class WindowResizer(private val frame: JFrame) : MouseAdapter() {

    private var zone = 0
    private var pressScreen: Point? = null
    private var pressBounds: Rectangle? = null

    fun installOn(component: JComponent) {
        component.addMouseListener(this)
        component.addMouseMotionListener(this)
    }

    override fun mouseMoved(e: MouseEvent) {
        val component = e.component as JComponent
        component.cursor = Cursor.getPredefinedCursor(cursorFor(zoneAt(e)))
    }

    override fun mouseExited(e: MouseEvent) {
        if (pressScreen == null) (e.component as JComponent).cursor = Cursor.getDefaultCursor()
    }

    override fun mousePressed(e: MouseEvent) {
        zone = zoneAt(e)
        if (zone == 0) return
        pressScreen = e.locationOnScreen
        pressBounds = frame.bounds
    }

    override fun mouseReleased(e: MouseEvent) {
        zone = 0
        pressScreen = null
        pressBounds = null
    }

    override fun mouseDragged(e: MouseEvent) {
        val origin = pressScreen ?: return
        val start = pressBounds ?: return
        if (zone == 0) return

        val dx = e.locationOnScreen.x - origin.x
        val dy = e.locationOnScreen.y - origin.y
        val min = frame.minimumSize

        var x = start.x
        var y = start.y
        var w = start.width
        var h = start.height

        if (zone and WEST != 0) {
            w = (start.width - dx).coerceAtLeast(min.width)
            x = start.x + (start.width - w)
        }
        if (zone and EAST != 0) w = (start.width + dx).coerceAtLeast(min.width)
        if (zone and NORTH != 0) {
            h = (start.height - dy).coerceAtLeast(min.height)
            y = start.y + (start.height - h)
        }
        if (zone and SOUTH != 0) h = (start.height + dy).coerceAtLeast(min.height)

        frame.setBounds(x, y, w, h)
        frame.validate()
    }

    /** Which edges the pointer is on, as a bitmask. Zero means "not on an edge". */
    private fun zoneAt(e: MouseEvent): Int {
        // A maximised window has no edges to drag; resizing one would leave it in a torn state that
        // still reported itself as maximised.
        if (frame.extendedState == Frame.MAXIMIZED_BOTH) return 0

        val component = e.component
        var mask = 0
        if (e.x <= MARGIN) mask = mask or WEST
        if (e.x >= component.width - MARGIN) mask = mask or EAST
        if (e.y <= MARGIN) mask = mask or NORTH
        if (e.y >= component.height - MARGIN) mask = mask or SOUTH
        return mask
    }

    private fun cursorFor(mask: Int): Int = when (mask) {
        NORTH -> Cursor.N_RESIZE_CURSOR
        SOUTH -> Cursor.S_RESIZE_CURSOR
        WEST -> Cursor.W_RESIZE_CURSOR
        EAST -> Cursor.E_RESIZE_CURSOR
        NORTH or WEST -> Cursor.NW_RESIZE_CURSOR
        NORTH or EAST -> Cursor.NE_RESIZE_CURSOR
        SOUTH or WEST -> Cursor.SW_RESIZE_CURSOR
        SOUTH or EAST -> Cursor.SE_RESIZE_CURSOR
        else -> Cursor.DEFAULT_CURSOR
    }

    companion object {
        /** Grab width. Must not exceed the shell's empty border, or the edge becomes unreachable. */
        const val MARGIN = 6

        private const val NORTH = 1
        private const val SOUTH = 2
        private const val WEST = 4
        private const val EAST = 8
    }
}
