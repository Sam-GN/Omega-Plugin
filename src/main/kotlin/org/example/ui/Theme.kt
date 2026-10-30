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

import java.awt.Color
import java.awt.Font
import javax.swing.UIManager
import javax.swing.plaf.nimbus.NimbusLookAndFeel

/**
 * Dark colour palette and look-and-feel setup.
 *
 * Nimbus is used because it ships with every standard JDK on both Windows and Linux and, unlike the
 * native look-and-feels, actually honours colour overrides — so the app looks the same on both
 * targets. Its colour keys must be installed *before* the L&F itself, hence [install].
 */
object Theme {

    val BACKGROUND = Color(0x14, 0x16, 0x1A)
    val PANEL = Color(0x1E, 0x22, 0x28)
    val PANEL_RAISED = Color(0x27, 0x2C, 0x34)

    /** The toggle strip between the top bars and the boards: a shade lighter than the bars. */
    val STRIP = Color(0x23, 0x27, 0x2E)
    val TRACK = Color(0x3A, 0x41, 0x4C)
    val KNOB_BODY = Color(0x2C, 0x32, 0x3B)

    val TEXT = Color(0xE6, 0xE9, 0xEF)
    val TEXT_DIM = Color(0x8A, 0x93, 0xA3)
    val DISABLED = Color(0x4A, 0x51, 0x5C)

    val ACCENT = Color(0xFF, 0x8A, 0x3D)
    val ACCENT_SOFT = Color(0xB3, 0x5F, 0x28)

    /**
     * Hover fill: [PANEL_RAISED] warmed about a quarter of the way towards [ACCENT].
     *
     * Enough to read as ours rather than as the look-and-feel's grey, without the full orange
     * shouting from behind a line of text.
     */
    val ACCENT_TINT = Color(0x5A, 0x42, 0x36)
    val GOOD = Color(0x4A, 0xDE, 0x80)
    val BAD = Color(0xF8, 0x71, 0x71)

    val TITLE_FONT = Font(Font.SANS_SERIF, Font.BOLD, 13)
    val LABEL_FONT = Font(Font.SANS_SERIF, Font.BOLD, 10)
    val VALUE_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    val BODY_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)

    fun install() {
        UIManager.put("control", PANEL)
        UIManager.put("info", PANEL)
        UIManager.put("nimbusBase", Color(0x2A, 0x30, 0x38))
        UIManager.put("nimbusBlueGrey", Color(0x33, 0x3A, 0x44))
        UIManager.put("nimbusLightBackground", PANEL_RAISED)
        UIManager.put("nimbusSelectionBackground", ACCENT_SOFT)
        UIManager.put("nimbusSelectedText", TEXT)
        UIManager.put("nimbusFocus", ACCENT)
        UIManager.put("text", TEXT)
        UIManager.put("menu", PANEL)
        UIManager.put("textForeground", TEXT)

        try {
            UIManager.setLookAndFeel(NimbusLookAndFeel())
        } catch (_: Exception) {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        }
    }
}
