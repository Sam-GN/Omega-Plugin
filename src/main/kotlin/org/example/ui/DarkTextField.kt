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

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.Painter
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.text.JTextComponent

/**
 * Makes a `JTextField` look like the rest of this application, and makes clicking away commit it.
 *
 * Two separate problems that both come from Swing's text field being a borrowed component in a
 * window that paints everything else itself.
 */
object DarkTextField {

    /**
     * Replaces Nimbus's background rather than setting a colour on it.
     *
     * `setBackground` does nothing useful here: Nimbus paints a text field through a *painter* held
     * in its defaults, and that painter draws its own gradient and an inner shadow along the top
     * edge regardless of the component's background colour. On a dark theme that shadow reads as a
     * black bar across the top of the field — which is what it is, drawn for a light theme where it
     * would be a subtle bevel.
     *
     * `Nimbus.Overrides` is the supported way to replace it for one component. `InheritDefaults`
     * false is what stops the stock painters being consulted at all; without it the override is
     * merged over them and the shadow survives.
     *
     * The bar is not removed but replaced: a two-pixel accent rule along the top, brighter when the
     * field has focus. It is the same idea as the level meter's peak line — a thin accent mark is
     * how everything else in this window says "this is live".
     */
    fun style(field: JTextField, height: Int? = null) {
        field.font = Theme.VALUE_FONT
        field.foreground = Theme.TEXT
        field.caretColor = Theme.ACCENT
        field.selectionColor = Theme.ACCENT_SOFT
        field.selectedTextColor = Theme.TEXT
        // Symmetric top and bottom. `FieldView` centres a single line vertically inside whatever
        // room it is given, so the insets only have to keep the text off the accent rule; the
        // centring is what makes an explicit [height] land the text where the eye expects it.
        field.border = BorderFactory.createEmptyBorder(3, 8, 3, 8)

        val overrides = UIDefaults()
        overrides["TextField.contentMargins"] = java.awt.Insets(4, 8, 3, 8)
        overrides["TextField[Enabled].backgroundPainter"] = painter(Theme.ACCENT_SOFT)
        overrides["TextField[Focused].backgroundPainter"] = painter(Theme.ACCENT)
        overrides["TextField[Selected].backgroundPainter"] = painter(Theme.ACCENT)
        overrides["TextField[Disabled].backgroundPainter"] = painter(Theme.DISABLED)
        field.putClientProperty("Nimbus.Overrides", overrides)
        field.putClientProperty("Nimbus.Overrides.InheritDefaults", false)

        // Pinned rather than merely preferred: on a `FlowLayout` row a text field is happy to be
        // shorter than the buttons beside it, and a control that is four pixels short of its
        // neighbours reads as misaligned long before anyone works out which one is wrong.
        if (height != null) {
            val size = java.awt.Dimension(field.preferredSize.width, height)
            field.preferredSize = size
            field.minimumSize = size
            field.maximumSize = size
        }
    }

    /** The field's whole surface: a flat fill, a hairline, and the accent rule along the top. */
    private fun painter(rule: java.awt.Color) = Painter<JComponent> { g: Graphics2D, _, w, h ->
        g.color = Theme.KNOB_BODY
        g.fillRect(0, 0, w, h)
        g.color = Theme.TRACK
        g.drawRect(0, 0, w - 1, h - 1)
        g.color = rule
        g.fillRect(1, 1, w - 2, 2)
    }

    /**
     * Clicking anywhere outside a text field commits it, exactly as Enter does.
     *
     * Swing's default is that a text field keeps focus until something else takes it, and most of
     * this window is unfocusable custom-painted components — so clicking a knob, the board, or empty
     * panel left a field still focused, still holding a selection, and still uncommitted. The user
     * has visibly finished with it and the program disagrees.
     *
     * Installed once, on the toolkit, rather than as a listener per field. Every text field in the
     * application is covered by that, including ones created later — the knob's inline editor and
     * the rename dialog's field are both built on demand, and a per-field listener would have to be
     * remembered at each of those sites.
     *
     * It only moves focus; it does not commit anything itself. That is deliberate: every text field
     * here already commits on `focusLost`, because that is what Escape-versus-click has to be
     * distinguishable from, so moving focus *is* pressing Enter. Firing the action as well would
     * commit twice.
     *
     * Clicks that land on another text component are left alone — that field is about to take focus
     * on its own, and stepping in first would make the click set focus twice.
     */
    fun installClickAwayCommit(focusSink: JComponent) {
        focusSink.isFocusable = true
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            if (event !is MouseEvent || event.id != MouseEvent.MOUSE_PRESSED) return@addAWTEventListener
            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focused !is JTextComponent || !focused.isShowing) return@addAWTEventListener

            val target = event.source as? Component ?: return@addAWTEventListener
            // Inside the field itself, or in something it owns: this is ordinary editing.
            if (target === focused || SwingUtilities.isDescendingFrom(target, focused)) {
                return@addAWTEventListener
            }
            if (target is JTextComponent) return@addAWTEventListener
            // A combo's popup and a menu are their own windows. Dismissing one should not also
            // commit a field the user has not touched.
            if (SwingUtilities.getWindowAncestor(target) !== SwingUtilities.getWindowAncestor(focused)) {
                return@addAWTEventListener
            }

            focused.select(0, 0)
            if (!focusSink.requestFocusInWindow()) focused.transferFocusUpCycle()
        }, AWTEvent.MOUSE_EVENT_MASK)
    }
}
