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
import javax.swing.JComboBox
import javax.swing.JList

/**
 * A combo box whose drop-down may be wider than the box itself.
 *
 * Device names are long and mostly differ at the end — "Headset Microphone (3- Arctis Pro Wireless
 * Chat)" against "Headset Earphone (3- Arctis Pro Wireless Chat)" — so a popup clipped to the
 * control's width truncates exactly the part that tells them apart. The control keeps its layout
 * width; only the open list overhangs.
 *
 * The mechanism is a well-known Swing trick rather than an API: `BasicComboPopup` sizes the popup
 * from `comboBox.getSize().width`, so reporting a wider size *outside* of layout widens the popup
 * without widening the control. [layingOut] is what keeps the real layout honest.
 */
class WideComboBox<E> : JComboBox<E>() {

    private var layingOut = false

    /** Only used to ask the renderer how wide each row wants to be; never displayed. */
    private val measuringList = JList<E>()

    override fun doLayout() {
        try {
            layingOut = true
            super.doLayout()
        } finally {
            layingOut = false
        }
    }

    override fun getSize(): Dimension {
        val size = super.getSize()
        if (!layingOut) size.width = maxOf(size.width, popupWidth())
        return size
    }

    /**
     * The widest row the renderer would produce, capped so a pathological device name cannot throw a
     * popup across the whole desktop.
     */
    private fun popupWidth(): Int {
        val cellRenderer = renderer ?: return 0
        var widest = 0
        for (index in 0 until itemCount) {
            val cell = cellRenderer.getListCellRendererComponent(
                measuringList,
                getItemAt(index),
                index,
                false,
                false,
            )
            widest = maxOf(widest, cell.preferredSize.width)
        }
        return if (widest == 0) 0 else minOf(widest + PADDING, MAX_WIDTH)
    }

    private companion object {
        /** Room for the list border and a scrollbar, which appears once the list is long enough. */
        const val PADDING = 28
        const val MAX_WIDTH = 720
    }
}
