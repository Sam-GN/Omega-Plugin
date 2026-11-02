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

import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bars in the shape the window holds them: two stacked in a [BoxLayout], in the north of a
 * [BorderLayout]. Swing needs a real window to validate, so the passes are run here by hand, which
 * also shows how many it takes. The heights are worked out from the sizes given, not read back.
 */
class BarLayoutTest {

    private val rowHeight = 30
    private val gap = 10

    private fun block() = JPanel().apply { preferredSize = Dimension(300, rowHeight) }

    private inner class Window(blocks: Int) {
        var requests = 0
        val top = JPanel(BarLayout(hgap = gap, vgap = gap, relayout = { requests++ })).apply {
            repeat(blocks) { add(block()) }
        }
        val below = JPanel().apply { preferredSize = Dimension(300, 40) }
        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(top)
            add(below)
        }
        val root = JPanel(BorderLayout()).apply { add(stack, BorderLayout.NORTH) }

        private fun lay(container: Container) {
            container.doLayout()
            container.components.forEach { if (it is Container) lay(it) }
        }

        /** What Swing does: one pass, and another each time something asked for one. */
        fun show(width: Int) {
            root.setSize(width, 800)
            invalidateUpwards()
            var done = -1
            repeat(4) {
                if (done != requests) {
                    done = requests
                    lay(root)
                    if (requests != done) invalidateUpwards()
                }
            }
        }

        /**
         * What `revalidate` reaches in a real window. Here each level has to be told, since a
         * container with no window is never valid and so never passes an invalidation on.
         */
        private fun invalidateUpwards() {
            top.invalidate()
            stack.invalidate()
            root.invalidate()
        }

        val lowestInTop: Int get() = top.components.maxOf { it.y + it.height }
    }

    private fun assertAllVisible(window: Window, rows: Int) {
        val expected = rows * rowHeight + (rows - 1) * gap
        assertEquals(expected, window.top.height, "the bar is as tall as its rows")
        assertEquals(expected, window.lowestInTop)
        assertEquals(expected, window.below.y, "what comes below starts after the last row")
    }

    @Test
    fun `a bar that fits on one line stays one line and asks for nothing more`() {
        val window = Window(3)
        window.show(2000)
        assertAllVisible(window, rows = 1)
        assertEquals(0, window.requests)
    }

    @Test
    fun `a bar too narrow for its children grows to show them all`() {
        val window = Window(3)
        window.show(700)
        assertAllVisible(window, rows = 2)
    }

    @Test
    fun `it follows the window narrower and wider again`() {
        val window = Window(3)
        window.show(2000)
        assertAllVisible(window, rows = 1)
        window.show(700)
        assertAllVisible(window, rows = 2)
        window.show(320)
        assertAllVisible(window, rows = 3)
        window.show(2000)
        assertAllVisible(window, rows = 1)
    }

    @Test
    fun `a parent that will not give the room is asked once, not for ever`() {
        var requests = 0
        val bar = JPanel(BarLayout(hgap = gap, vgap = gap, relayout = { requests++ })).apply {
            repeat(3) { add(block()) }
            setSize(700, rowHeight)
        }
        repeat(5) { bar.doLayout() }
        assertEquals(1, requests)
    }
}
