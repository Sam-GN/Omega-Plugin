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
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.plaf.basic.BasicMenuItemUI
import javax.swing.plaf.basic.BasicMenuUI

/**
 * Dark menus, painted rather than themed.
 *
 * Real [JMenuItem]s and [JMenu]s on a custom UI, not hand-built components: submenu hover delays,
 * keyboard traversal and the rules for when a nested popup should close are genuinely hard to get
 * right, and Swing already has them. Only the painting is taken over — the same division as
 * [DarkComboBoxUI], and for the same reason, since Nimbus's own colours cannot be overridden
 * per-component.
 */
object DarkMenu {

    /** Styles a popup and everything that will be added to it. */
    fun popup(): JPopupMenu = JPopupMenu().apply {
        background = Theme.PANEL_RAISED
        border = BorderFactory.createLineBorder(Theme.TRACK, 1)
        isOpaque = true
    }

    fun item(text: String, enabled: Boolean = true, action: (() -> Unit)? = null): JMenuItem =
        JMenuItem(text).apply {
            setUI(DarkMenuItemUI())
            isEnabled = enabled
            action?.let { handler -> addActionListener { handler() } }
        }

    /** A submenu. Its own popup is styled here so nested levels match. */
    fun submenu(text: String, enabled: Boolean = true): JMenu = JMenu(text).apply {
        setUI(DarkSubmenuUI())
        isEnabled = enabled
        popupMenu.background = Theme.PANEL_RAISED
        popupMenu.border = BorderFactory.createLineBorder(Theme.TRACK, 1)
        popupMenu.isOpaque = true
    }

    /**
     * A row carrying a switch rather than an action.
     *
     * A plain [JPanel] and not a [JMenuItem]: a menu row's whole model is "arm, then fire once",
     * which is the wrong shape for a control that has a state you are meant to read at a glance.
     * The cost is that this row does not take part in keyboard traversal — acceptable for a setting
     * reached by pointing at a three-dot button in the first place.
     *
     * For the unavailable case use a disabled [item] saying *why*, rather than a dead switch: a
     * switch that cannot move tells you nothing about the reason.
     */
    /**
     * A container for rows that are not [JMenuItem]s.
     *
     * Everything hand-built goes inside one of these, because a popup only *looks* dark when its
     * children cover it: `JMenuItem` rows paint their own full-width fill, so the popup's own
     * background is never seen. A label does not, and Nimbus's grey shows through everywhere the
     * text does not reach.
     */
    fun content(): JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = true
        background = Theme.PANEL_RAISED
    }

    /**
     * A labelled switch. [enabled] false shows it **locked in its current position** rather than
     * blank — see [ToggleSwitch.setEnabled] for why that distinction is load-bearing.
     */
    fun switchRow(
        text: String,
        selected: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): JPanel =
        JPanel(BorderLayout(18, 0)).apply {
            isOpaque = false
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(PADDING.top, PADDING.left, PADDING.bottom, PADDING.left)
            add(
                JLabel(text).apply {
                    font = Theme.BODY_FONT
                    foreground = if (enabled) Theme.TEXT else Theme.DISABLED
                },
                BorderLayout.WEST,
            )
            add(
                ToggleSwitch(selected).apply {
                    toolTipText = null
                    onToggle = onChange
                    isEnabled = enabled
                },
                BorderLayout.EAST,
            )
        }

    /**
     * Enables or disables a row built by [switchRow], **after** it has been added to a menu.
     *
     * Needed because rows are built once when the popup opens, and a row whose availability depends
     * on another row in the same popup would otherwise stay wrong until the menu was closed and
     * reopened. That is the same fault the logging note carries a comment about: a snapshot taken
     * before the click, still on screen after it.
     *
     * The label is greyed alongside the switch. Disabling the switch alone leaves a row that reads
     * as available and does not respond, which is worse than one that reads as unavailable.
     */
    fun setRowEnabled(row: JPanel, enabled: Boolean) {
        for (child in row.components) {
            when (child) {
                is ToggleSwitch -> child.isEnabled = enabled
                is JLabel -> child.foreground = if (enabled) Theme.TEXT else Theme.DISABLED
            }
        }
        row.repaint()
    }

    /** A row that only explains why it cannot be used. */
    fun disabledRow(text: String): JComponent = JLabel(text).apply {
        font = Theme.BODY_FONT
        foreground = Theme.DISABLED
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(PADDING.top, PADDING.left, PADDING.bottom, PADDING.left)
    }

    /** An explanatory line under a row. Dimmer and smaller; never interactive. */
    fun note(text: String): JComponent = JLabel(text).apply {
        font = Theme.LABEL_FONT
        foreground = Theme.TEXT_DIM
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, PADDING.left, PADDING.bottom, PADDING.left)
    }

    internal val PADDING = Insets(7, 14, 7, 30)

    /**
     * One row: fill, label, and a chevron when it leads somewhere.
     *
     * Armed means the pointer is on it; for a [JMenu] the model stays *selected* while its child
     * popup is open, which is what keeps the parent row highlighted as you move into the submenu.
     */
    internal fun paintRow(g: Graphics, c: JComponent) {
        val item = c as? JMenuItem ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val highlighted = item.isEnabled &&
                (item.model.isArmed || (item is JMenu && item.model.isSelected))

            g2.color = if (highlighted) Theme.ACCENT_TINT else Theme.PANEL_RAISED
            g2.fillRect(0, 0, c.width, c.height)

            g2.font = item.font
            g2.color = when {
                !item.isEnabled -> Theme.DISABLED
                item.foreground === DANGER -> DANGER
                highlighted -> Theme.TEXT
                else -> Theme.TEXT_DIM
            }
            val metrics = g2.fontMetrics
            g2.drawString(item.text.orEmpty(), PADDING.left, (c.height + metrics.ascent - metrics.descent) / 2)

            if (item is JMenu) {
                val x = c.width - 16.0
                val y = c.height / 2.0
                g2.color = if (item.isEnabled) Theme.TEXT_DIM else Theme.DISABLED
                g2.fill(
                    Path2D.Double().apply {
                        moveTo(x - 2.5, y - 4.0)
                        lineTo(x + 2.5, y)
                        lineTo(x - 2.5, y + 4.0)
                        closePath()
                    }
                )
            }
        } finally {
            g2.dispose()
        }
    }

    /** Marks a destructive row. Compared by identity in [paintRow], so it must be this instance. */
    val DANGER: Color = Theme.BAD
}

/** Painting for a leaf row. */
class DarkMenuItemUI : BasicMenuItemUI() {

    override fun installDefaults() {
        super.installDefaults()
        menuItem.isOpaque = false
        menuItem.font = Theme.BODY_FONT
        menuItem.border = BorderFactory.createEmptyBorder(
            DarkMenu.PADDING.top,
            DarkMenu.PADDING.left,
            DarkMenu.PADDING.bottom,
            DarkMenu.PADDING.right,
        )
        selectionBackground = Theme.ACCENT_TINT
        selectionForeground = Theme.TEXT
    }

    override fun paintMenuItem(
        g: Graphics,
        c: JComponent,
        checkIcon: Icon?,
        arrowIcon: Icon?,
        background: Color?,
        foreground: Color?,
        defaultTextIconGap: Int,
    ) = DarkMenu.paintRow(g, c)
}

/** Painting for a row that opens a submenu. */
class DarkSubmenuUI : BasicMenuUI() {

    override fun installDefaults() {
        super.installDefaults()
        menuItem.isOpaque = false
        menuItem.font = Theme.BODY_FONT
        menuItem.border = BorderFactory.createEmptyBorder(
            DarkMenu.PADDING.top,
            DarkMenu.PADDING.left,
            DarkMenu.PADDING.bottom,
            DarkMenu.PADDING.right,
        )
        selectionBackground = Theme.ACCENT_TINT
        selectionForeground = Theme.TEXT
    }

    override fun paintMenuItem(
        g: Graphics,
        c: JComponent,
        checkIcon: Icon?,
        arrowIcon: Icon?,
        background: Color?,
        foreground: Color?,
        defaultTextIconGap: Int,
    ) = DarkMenu.paintRow(g, c)
}
