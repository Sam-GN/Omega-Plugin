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
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Paints a button's face: fill, edge, and the label centred in the whole width.
 *
 * Shared so there is one definition of what a button looks like here. [PushButton] is a bare
 * `JComponent` because that is the simplest thing that works for a button this application creates
 * itself; [DarkButtonUI] exists because the `JButton`s inside a `JFileChooser` are created by Swing
 * and can only be restyled through their UI delegate. Two components, two mechanisms, and — because
 * of this function — one appearance.
 *
 * The label is centred in the *whole* width even when a lamp is present. The lamp gets its own
 * reserved column in the button's size, but reserving room is not the same as pushing the label off
 * centre to make it, and an off-centre label is the thing you notice.
 */
internal fun paintPushSurface(
    g2: Graphics2D,
    width: Int,
    height: Int,
    text: String,
    font: Font,
    enabled: Boolean,
    hover: Boolean,
    pressed: Boolean,
    accent: Color?,
) {
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val fill: Color
    val edge: Color
    val ink: Color
    when {
        !enabled -> {
            fill = Theme.KNOB_BODY
            edge = Theme.TRACK
            ink = Theme.DISABLED
        }
        accent != null -> {
            fill = if (pressed) accent.darker() else if (hover) accent.brighter() else accent
            edge = if (hover || pressed) Theme.ACCENT else accent
            ink = Color.WHITE
        }
        else -> {
            fill = if (pressed) Theme.TRACK else if (hover) Theme.ACCENT_TINT else Theme.KNOB_BODY
            edge = if (hover || pressed) Theme.ACCENT_SOFT else Theme.TRACK
            ink = if (hover || pressed) Theme.TEXT else Theme.TEXT_DIM
        }
    }

    g2.color = fill
    g2.fillRoundRect(0, 0, width, height, PUSH_RADIUS, PUSH_RADIUS)
    g2.color = edge
    g2.drawRoundRect(0, 0, width - 1, height - 1, PUSH_RADIUS, PUSH_RADIUS)

    g2.font = font
    g2.color = ink
    val metrics = g2.fontMetrics
    g2.drawString(
        text,
        (width - metrics.stringWidth(text)) / 2,
        (height + metrics.ascent - metrics.descent) / 2,
    )
}

/** Same corner as [EffectPanel.HeaderButton], one size up. */
internal const val PUSH_RADIUS = 8

/**
 * The button, everywhere.
 *
 * Painted rather than themed, for the same reason every other control here is: Nimbus's own colours
 * cannot be overridden per component, and its buttons are silver — which reads as a dialog from
 * another application dropped into the middle of this one.
 *
 * Two shapes, one family:
 *
 * - **Neutral** ([accent] null) — the same fill, border and hover as [EffectPanel.HeaderButton], so
 *   `Rescan` in the top bar and `LOAD` on a pedal are visibly the same kind of thing at two sizes.
 * - **Filled** — a solid call to action in [accent]. Used where there is one obvious action and it
 *   should be the thing your eye lands on: `Start`, `Save`, and the red confirm on a destructive
 *   dialog.
 *
 * Disabled is painted, not merely ignored. A `Start` that cannot be pressed because two devices
 * share no sample rate has to *look* unavailable, or the only feedback is that clicking does
 * nothing.
 */
class PushButton(
    text: String,
    /** Fill for a call to action, or null for the neutral outline style. */
    private val accent: Color? = null,
    minimumWidth: Int = DEFAULT_MIN_WIDTH,
    /**
     * Colour of a status lamp on the button's right, or null for no lamp.
     *
     * For a control that both *does* a thing and *reports* whether that thing is happening. The
     * label alone can say which — `Start` against `Stop` — but a label is read, and a lamp is seen:
     * glancing at the top bar to check whether audio is running should not require reading a word.
     */
    private val lamp: Color? = null,
    private val onClick: () -> Unit,
) : JComponent() {

    private val minimumWidth = minimumWidth
    private var hover = false
    private var pressed = false

    /** Changeable, because `Start` becomes `Stop` without becoming a different button. */
    var text: String = text
        set(value) {
            if (field == value) return
            field = value
            resize()
            repaint()
        }

    /** Whether the [lamp] is lit. */
    var lit: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            repaint()
        }

    init {
        font = Theme.BODY_FONT
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        resize()
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                pressed = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                pressed = true
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                val wasPressed = pressed
                pressed = false
                repaint()
                // Fires on release inside the button, not on click, so dragging off cancels — which
                // is what every other button on the machine does.
                if (wasPressed && isEnabled && contains(e.point)) onClick()
            }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = Cursor.getPredefinedCursor(
            if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
        )
        if (!enabled) {
            hover = false
            pressed = false
        }
        repaint()
    }

    private fun resize() {
        val lampRoom = if (lamp != null) LAMP_ZONE else 0
        val width = maxOf(getFontMetrics(font).stringWidth(text) + PADDING + lampRoom, minimumWidth)
        val size = Dimension(width, HEIGHT)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        revalidate()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            paintPushSurface(g2, width, height, text, font, isEnabled, hover, pressed, accent)
            if (lamp != null) paintLamp(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun paintLamp(g2: Graphics2D) {
        val colour = lamp ?: return
        val centreX = width - LAMP_INSET
        val centreY = height / 2
        val left = centreX - LAMP_SIZE / 2
        val top = centreY - LAMP_SIZE / 2

        if (lit && isEnabled) {
            // A halo, then the core, then a highlight off-centre. Without the halo it reads as a
            // green dot; with it, as something emitting light.
            g2.color = Color(colour.red, colour.green, colour.blue, 70)
            g2.fillOval(centreX - LAMP_SIZE, centreY - LAMP_SIZE, LAMP_SIZE * 2, LAMP_SIZE * 2)
            g2.color = colour
            g2.fillOval(left, top, LAMP_SIZE, LAMP_SIZE)
            g2.color = Color(255, 255, 255, 130)
            g2.fillOval(left + 1, top + 1, LAMP_SIZE / 3, LAMP_SIZE / 3)
        } else {
            // Unlit, not absent: darker than the button face so it reads as a lamp that is off.
            g2.color = Theme.PANEL
            g2.fillOval(left, top, LAMP_SIZE, LAMP_SIZE)
            g2.color = Theme.TRACK
            g2.drawOval(left, top, LAMP_SIZE - 1, LAMP_SIZE - 1)
        }
    }

    companion object {
        /**
         * Matches the device bar's combo boxes, so the row sits on one line.
         *
         * Public because anything else placed on those bars has to agree with it — the tempo field
         * is sized from this rather than from a second copy of the number, which is how two controls
         * side by side end up a pixel apart after one of them is adjusted.
         */
        const val HEIGHT = 30

        private const val PADDING = 30
        private const val DEFAULT_MIN_WIDTH = 92

        private const val LAMP_SIZE = 8

        /** Distance from the right edge to the lamp's centre. */
        private const val LAMP_INSET = 14

        /** Width the lamp reserves, which the label is centred to the left of. */
        private const val LAMP_ZONE = 22
    }
}
