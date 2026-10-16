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

import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/** Painted on/off switch — the per-effect bypass control. */
class ToggleSwitch(selected: Boolean = false) : JComponent() {

    var onToggle: ((Boolean) -> Unit)? = null

    var isSelected: Boolean = selected
        set(value) {
            if (field != value) {
                field = value
                repaint()
            }
        }

    init {
        preferredSize = Dimension(44, 22)
        minimumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Bypass on / off"
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                isSelected = !isSelected
                onToggle?.invoke(isSelected)
            }
        })
    }

    /**
     * A switch can now be shown **on and locked**, which is a state the app needs and a plain
     * disabled checkbox cannot express.
     *
     * The case is the rhythm menu's "click over drum patterns": when the selected feel is already a
     * click you *are* hearing one, so the row has to read as on while refusing to be turned off.
     * Greyed-and-off would say the click is silent while a click is audibly playing, which is
     * reported as a bug rather than understood as a lock.
     */
    override fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        super.setEnabled(enabled)
        cursor = Cursor.getPredefinedCursor(
            if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
        )
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val h = height.toDouble()
            val w = width.toDouble()

            // Locked keeps the on/off *shape* — knob to the right, track filled — and drains the
            // colour out of it. The state stays readable; only its being changeable goes away.
            val live = isEnabled

            g2.color = when {
                !live -> if (isSelected) Theme.ACCENT_TINT else Theme.PANEL_RAISED
                isSelected -> Theme.ACCENT_SOFT
                else -> Theme.TRACK
            }
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, w, h, h, h))

            g2.color = when {
                !live -> Theme.DISABLED
                isSelected -> Theme.ACCENT
                else -> Theme.PANEL_RAISED
            }
            g2.stroke = BasicStroke(1.5f)
            g2.draw(RoundRectangle2D.Double(0.75, 0.75, w - 1.5, h - 1.5, h, h))

            val d = h - 6
            val x = if (isSelected) w - d - 3 else 3.0
            g2.color = when {
                !live -> Theme.DISABLED
                isSelected -> Theme.TEXT
                else -> Theme.TEXT_DIM
            }
            g2.fill(Ellipse2D.Double(x, 3.0, d, d))
        } finally {
            g2.dispose()
        }
    }
}
