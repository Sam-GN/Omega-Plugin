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

import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.plaf.basic.BasicButtonUI

/**
 * Restyling for components this application did not create.
 *
 * Everything Omega builds itself is hand-painted, because Nimbus's colours cannot be overridden per
 * component. But a `JFileChooser` builds its own buttons, combo boxes and lists, and there is no
 * constructor to pass a style to — so the only lever is the one Nimbus itself uses: the **UI
 * delegate**. Replacing it with a `Basic*` subclass takes the component out of Nimbus's painters
 * entirely, which is the same trick [DarkComboBoxUI] and [DarkMenuItemUI] already play.
 *
 * Setting `background` and `foreground` is *not* that lever. Nimbus's painters mostly ignore them,
 * which is exactly the wall the rest of this package was written to get around; where colours are
 * set below it is only on components whose Basic UI does honour them.
 */
object DarkChrome {

    /**
     * Walks a component tree and puts the parts this application has a look for onto that look.
     *
     * Applied after construction, because that is when a chooser's children exist. Anything created
     * later — a renderer, a dialog opened from inside — keeps the platform's appearance; this is a
     * best effort at making a borrowed component fit in, not a second look-and-feel.
     */
    fun apply(component: Component) {
        when (component) {
            is JComboBox<*> -> component.setUI(DarkComboBoxUI())
            // Order matters: JToggleButton is not a JButton, and both are AbstractButtons.
            is AbstractButton -> styleButton(component)
            is JTextField -> {
                component.background = Theme.KNOB_BODY
                component.foreground = Theme.TEXT
                component.caretColor = Theme.ACCENT
                component.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.TRACK, 1),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8),
                )
            }
            is JList<*> -> {
                component.background = Theme.PANEL
                component.foreground = Theme.TEXT
                component.selectionBackground = Theme.ACCENT_SOFT
                component.selectionForeground = Theme.TEXT
            }
            is JTable -> {
                component.background = Theme.PANEL
                component.foreground = Theme.TEXT
                component.gridColor = Theme.TRACK
                component.selectionBackground = Theme.ACCENT_SOFT
                component.selectionForeground = Theme.TEXT
            }
            is JLabel -> component.foreground = Theme.TEXT_DIM
            is JScrollPane -> {
                component.border = BorderFactory.createLineBorder(Theme.TRACK, 1)
                component.viewport.background = Theme.PANEL
                component.verticalScrollBar?.setUI(SlimScrollBarUI())
                component.horizontalScrollBar?.setUI(SlimScrollBarUI())
            }
            is JPanel -> {
                component.isOpaque = true
                component.background = Theme.PANEL
            }
        }
        if (component is JComponent && component !is JComboBox<*>) {
            // A combo's editor and popup belong to its own UI; walking into them undoes it.
            for (child in component.components) apply(child)
        } else if (component is Container && component !is JComboBox<*>) {
            for (child in component.components) apply(child)
        }
    }

    private fun styleButton(button: AbstractButton) {
        if (button is JButton || button is JToggleButton) {
            button.setUI(DarkButtonUI())
            button.isRolloverEnabled = true
            button.isFocusPainted = false
            button.isContentAreaFilled = false
            button.border = BorderFactory.createEmptyBorder(4, 12, 4, 12)
            button.font = Theme.BODY_FONT
        }
    }
}

/**
 * Paints a Swing button the way [PushButton] paints itself.
 *
 * A `BasicButtonUI` rather than a swap for `PushButton`, because the buttons in a borrowed component
 * are already constructed and wired to actions — replacing them would mean rebuilding the component.
 * The appearance comes from [paintPushSurface], so the two cannot drift apart.
 *
 * A button carrying only an icon is left to paint itself: the file chooser's toolbar is icons, and a
 * label-centred painter would erase them.
 */
class DarkButtonUI : BasicButtonUI() {

    override fun paint(g: Graphics, c: JComponent) {
        val button = c as? AbstractButton ?: return
        val text = button.text
        if (text.isNullOrEmpty()) {
            super.paint(g, c)
            return
        }
        val model = button.model
        val g2 = g.create() as Graphics2D
        try {
            paintPushSurface(
                g2,
                c.width,
                c.height,
                text,
                button.font ?: Theme.BODY_FONT,
                button.isEnabled,
                model.isRollover,
                model.isPressed && model.isArmed,
                null,
            )
        } finally {
            g2.dispose()
        }
    }
}
