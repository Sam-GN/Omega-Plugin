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

import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.event.ChangeListener
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * A thin, dark scrollbar: no arrow buttons, no track, just a rounded thumb.
 *
 * Nimbus's is a light three-piece affair with arrows at both ends, which would be the only silver
 * thing left in the window.
 *
 * The thumb **fades** in and out as the content starts and stops needing a scrollbar, which lets a
 * scroll pane keep the bar's column reserved at all times (`VERTICAL_SCROLLBAR_ALWAYS`) and still
 * show nothing while there is nothing to scroll. A bar that is added only when needed takes its
 * width from the viewport at that moment, and a board centred in that viewport shifts sideways by
 * half of it — which is what the pedal board did when split mode made the window scroll.
 */
class SlimScrollBarUI : BasicScrollBarUI() {

    /** 0 to 1: how much of the thumb is drawn. Eases towards [needed]. */
    private var alpha = 0f

    /** Whether the content is taller than the view, read from the model. */
    private var needed = false

    /** Where the thumb last was, so it has somewhere to fade out from once the model has no thumb. */
    private val lastThumb = Rectangle()

    private val fader = Timer(FRAME_MS) { step() }.apply { isCoalesce = true }

    private val needListener = ChangeListener { syncNeeded(animate = true) }

    override fun configureScrollBarColors() {
        thumbColor = Theme.TRACK
        trackColor = Theme.BACKGROUND
    }

    override fun installListeners() {
        super.installListeners()
        scrollbar.model.addChangeListener(needListener)
        syncNeeded(animate = false)
    }

    override fun uninstallListeners() {
        scrollbar.model.removeChangeListener(needListener)
        fader.stop()
        super.uninstallListeners()
    }

    private fun syncNeeded(animate: Boolean) {
        val model = scrollbar.model
        val now = model.extent < model.maximum - model.minimum
        if (now == needed) return
        needed = now
        if (animate) {
            fader.start()
        } else {
            alpha = if (now) 1f else 0f
        }
    }

    private fun step() {
        val target = if (needed) 1f else 0f
        alpha = if (kotlin.math.abs(target - alpha) < 0.02f) target else alpha + (target - alpha) * EASE
        if (alpha == target) fader.stop()
        scrollbar.repaint()
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
        if (!scrollbar.isEnabled) return
        // The superclass empties the thumb the moment the content fits; the fade-out draws the thumb
        // where it was, dissolving, rather than cutting it off at the first frame.
        if (!thumbBounds.isEmpty) lastThumb.bounds = thumbBounds
        val bounds = if (thumbBounds.isEmpty) lastThumb else thumbBounds
        if (alpha <= 0f || bounds.isEmpty) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (alpha < 1f) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            g2.color = if (isDragging || isThumbRollover) Theme.TEXT_DIM else Theme.TRACK
            val inset = 2
            g2.fillRoundRect(
                bounds.x + inset,
                bounds.y + inset,
                bounds.width - inset * 2,
                bounds.height - inset * 2,
                8,
                8,
            )
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /** ~60 fps, and the easing the boards use for their own fades: about 150 ms to settle. */
        const val FRAME_MS = 16
        const val EASE = 0.35f
    }
}
