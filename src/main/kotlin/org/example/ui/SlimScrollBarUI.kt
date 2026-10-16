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

import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * A thin, dark scrollbar: no arrow buttons, no track, just a rounded thumb.
 *
 * Nimbus's is a light three-piece affair with arrows at both ends, which would be the only silver
 * thing left in the window.
 */
class SlimScrollBarUI : BasicScrollBarUI() {

    override fun configureScrollBarColors() {
        thumbColor = Theme.TRACK
        trackColor = Theme.BACKGROUND
    }

    /** Zero-sized stand-ins: the superclass requires buttons, this design has none. */
    private fun hiddenButton() = JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = preferredSize
        maximumSize = preferredSize
        isVisible = false
    }

    override fun createDecreaseButton(orientation: Int): JButton = hiddenButton()

    override fun createIncreaseButton(orientation: Int): JButton = hiddenButton()

    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) = Unit

    override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: Rectangle) {
        if (thumbBounds.isEmpty || !scrollbar.isEnabled) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (isDragging || isThumbRollover) Theme.TEXT_DIM else Theme.TRACK
            val inset = 2
            g2.fillRoundRect(
                thumbBounds.x + inset,
                thumbBounds.y + inset,
                thumbBounds.width - inset * 2,
                thumbBounds.height - inset * 2,
                8,
                8,
            )
        } finally {
            g2.dispose()
        }
    }
}
