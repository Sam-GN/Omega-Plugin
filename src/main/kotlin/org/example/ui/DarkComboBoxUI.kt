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
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.plaf.basic.BasicComboBoxUI
import javax.swing.plaf.basic.BasicComboPopup
import javax.swing.plaf.basic.ComboPopup

/**
 * Gunmetal combo boxes, painted rather than themed.
 *
 * Nimbus draws its combos with a light silver gradient, which only turned dark once the control took
 * focus — so the bar was a mix of two looks and the light state made the white value text hard to
 * read. This paints the dark state always, and keeps the accent purely as the focus indicator.
 *
 * Built on [BasicComboBoxUI] rather than by overriding Nimbus's painter keys. Nimbus paints through
 * `SynthStyle` and applies its own colours *after* a renderer returns, which is why an earlier
 * attempt to recolour it from `UIManager` came to nothing and why a disabled combo there paints its
 * value invisibly.
 */
class DarkComboBoxUI : BasicComboBoxUI() {

    override fun installDefaults() {
        super.installDefaults()
        comboBox.isOpaque = false
        comboBox.background = Theme.KNOB_BODY
        comboBox.foreground = Theme.TEXT
        comboBox.border = BorderFactory.createEmptyBorder(3, 9, 3, 3)
    }

    /** A plain chevron. The stock button brings its own border and fill, both wrong here. */
    override fun createArrowButton(): JButton = object : JButton() {
        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusable = false
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(20, 20)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (comboBox.isEnabled) Theme.TEXT_DIM else Theme.DISABLED
                val cx = width / 2.0
                val cy = height / 2.0
                g2.fill(
                    Path2D.Double().apply {
                        moveTo(cx - 4.0, cy - 2.0)
                        lineTo(cx + 4.0, cy - 2.0)
                        lineTo(cx, cy + 3.0)
                        closePath()
                    }
                )
            } finally {
                g2.dispose()
            }
        }
    }

    override fun paint(g: Graphics, c: JComponent) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (c.isEnabled) Theme.KNOB_BODY else Theme.PANEL
            g2.fillRoundRect(0, 0, c.width - 1, c.height - 1, 8, 8)
            g2.color = if (c.isEnabled && c.hasFocus()) Theme.ACCENT else Theme.TRACK
            g2.drawRoundRect(0, 0, c.width - 1, c.height - 1, 8, 8)
        } finally {
            g2.dispose()
        }
        super.paint(g, c)
    }

    /** The whole control is filled in [paint]; this would lay a second rectangle over it. */
    override fun paintCurrentValueBackground(g: Graphics, bounds: Rectangle, hasFocus: Boolean) = Unit

    /**
     * Draws the value itself rather than deferring to [BasicComboBoxUI].
     *
     * The superclass hands the renderer a background and lets it fill: the popup list's *selection*
     * colours while focused, and `ComboBox.disabledBackground` from `UIManager` while disabled.
     * Either way the renderer paints an opaque **rectangle** over the rounded fill underneath. It
     * goes unnoticed while enabled and unfocused because the colours happen to match, and shows up
     * as a hard-edged patch of the wrong shade the moment they do not — which is what a fixed,
     * unchangeable rate does to the RATE combo.
     *
     * So: the renderer is made transparent, focus is left to the border in [paint], and the only
     * colour forced is the disabled foreground, since Nimbus's is close enough to the fill to be
     * unreadable.
     */
    override fun paintCurrentValue(g: Graphics, bounds: Rectangle, hasFocus: Boolean) {
        val renderer = comboBox.renderer ?: return

        @Suppress("UNCHECKED_CAST")
        val cell = (renderer as ListCellRenderer<Any?>)
            .getListCellRendererComponent(listBox as JList<Any?>, comboBox.selectedItem, -1, false, false)

        cell.font = comboBox.font
        if (cell is JComponent) cell.isOpaque = false
        if (!comboBox.isEnabled) cell.foreground = Theme.DISABLED

        currentValuePane.paintComponent(
            g,
            cell,
            comboBox,
            bounds.x,
            bounds.y,
            bounds.width,
            bounds.height,
            cell is JPanel,
        )
    }

    override fun createPopup(): ComboPopup = (super.createPopup() as BasicComboPopup).apply {
        list.background = Theme.PANEL_RAISED
        list.foreground = Theme.TEXT
        list.selectionBackground = Theme.ACCENT_SOFT
        list.selectionForeground = Theme.TEXT
        border = BorderFactory.createLineBorder(Theme.TRACK, 1)
    }

    companion object {
        /** Applies the look. Renderers are left alone — this only changes the control's own paint. */
        fun install(vararg combos: JComboBox<*>) {
            combos.forEach { it.setUI(DarkComboBoxUI()) }
        }
    }
}
