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
import java.awt.Dimension
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants

/**
 * The loop board in a window of its own: the same chrome as the main window — the app's title bar
 * with minimise, maximise and close, and the resize gutter — around nothing but the board.
 *
 * Close is "pop back in", not "switch off": the board goes back to the stack and the loop keeps
 * playing, since moving a panel between windows is a change to the picture and to nothing else.
 * A frame rather than a dialog so it has a taskbar entry of its own and can live on a second
 * screen without following the main window about.
 *
 * The size is the user's. The window grows when it is too short to show every lane and otherwise
 * keeps whatever height it was given, maximised included — the board sits at the top and the slack,
 * if there is any, is below it. Until 1.8.1 every lane added or removed put the window back at the
 * lanes' own height, which un-maximised a maximised window and shrank one made tall on purpose.
 */
class LoopWindow(
    private val board: LoopBoard,
    bounds: Rectangle?,
    private val onPopIn: () -> Unit,
) : JFrame("Omega-Plugin — Loop") {

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        isUndecorated = true
        contentPane.background = Theme.BACKGROUND
        if (AppIcon.images.isNotEmpty()) iconImages = AppIcon.images

        val root = JPanel(BorderLayout()).apply {
            background = Theme.BACKGROUND
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            // North, not centre: a window taller than the lanes keeps them at the top, where the
            // rows are, rather than stretching the board and opening a gap between the last lane
            // and the transport.
            add(board, BorderLayout.NORTH)
        }
        val shell = JPanel(BorderLayout()).apply {
            background = Theme.BACKGROUND
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.TRACK, 1),
                BorderFactory.createEmptyBorder(
                    WindowResizer.MARGIN,
                    WindowResizer.MARGIN,
                    WindowResizer.MARGIN,
                    WindowResizer.MARGIN,
                ),
            )
            add(TitleBar(this@LoopWindow, "Omega-Plugin — Loop", withMenu = false) { onPopIn() }, BorderLayout.NORTH)
            add(root, BorderLayout.CENTER)
        }
        WindowResizer(this).installOn(shell)
        contentPane.add(shell)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = onPopIn()
        })

        pack()
        minimumSize = Dimension(560, 200)
        if (bounds != null && onScreen(bounds)) {
            setBounds(bounds)
            // The lanes may have changed since those bounds were saved.
            fitLanes()
        } else {
            setLocationRelativeTo(null)
        }
    }

    /**
     * Grows the window when it is too short to show every lane; otherwise leaves it as the user
     * sized it. Never shrinks, and a maximised window is left maximised. If the taller window would
     * run off the bottom of the screen it is moved up by the difference, since a lane that cannot
     * be seen is the thing this exists to prevent.
     */
    fun fitLanes() {
        if ((extendedState and Frame.MAXIMIZED_VERT) != 0) return
        val needed = preferredSize.height
        if (height >= needed) return
        val area = workArea()
        val bottom = area.y + area.height
        val top = if (y + needed > bottom) maxOf(area.y, bottom - needed) else y
        setBounds(x, top, width, needed)
        validate()
    }

    private fun workArea(): Rectangle {
        val config = graphicsConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    /** The window's place, as the settings store it. */
    fun boundsText(): String = "${x},${y},${width},${height}"

    private fun onScreen(r: Rectangle): Boolean {
        // A monitor that was there last time may not be now; a window placed on it would be lost.
        val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        return screens.any { it.defaultConfiguration.bounds.intersects(r) }
    }

    companion object {
        fun parseBounds(text: String?): Rectangle? {
            val parts = text?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (parts.size != 4 || parts[2] < 200 || parts[3] < 100) return null
            return Rectangle(parts[0], parts[1], parts[2], parts[3])
        }
    }
}
