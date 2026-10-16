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
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import kotlin.math.log10
import kotlin.math.max

/**
 * Output level bar with peak hold.
 *
 * Displays dBFS on a -60..0 scale rather than raw amplitude — a linear bar spends most of its travel
 * on levels too quiet to see. The bar falls back gradually while the peak marker holds, which is what
 * makes brief clipping visible at a 30 Hz refresh.
 */
class LevelMeter : JComponent() {

    private var displayed = 0f
    private var peak = 0f
    private var peakHoldFrames = 0

    init {
        preferredSize = Dimension(150, 12)
        minimumSize = preferredSize
        toolTipText = "Output level (-60 to 0 dBFS)"
    }

    /** Feeds a new linear amplitude (0..1) from the audio engine; call from the EDT. */
    fun update(level: Float) {
        val normalised = toNormalisedDb(level)
        displayed = if (normalised > displayed) normalised else displayed * FALLBACK
        if (normalised >= peak) {
            peak = normalised
            peakHoldFrames = PEAK_HOLD_FRAMES
        } else if (peakHoldFrames > 0) {
            peakHoldFrames--
        } else {
            peak = max(displayed, peak * FALLBACK)
        }
        repaint()
    }

    private fun toNormalisedDb(amplitude: Float): Float {
        if (amplitude <= 0.00001f) return 0f
        val db = 20f * log10(amplitude)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width.toDouble()
            val h = height.toDouble()

            g2.color = Theme.TRACK
            g2.fill(RoundRectangle2D.Double(0.0, 0.0, w, h, h, h))

            if (displayed > 0.001f) {
                g2.color = if (displayed > CLIP_THRESHOLD) Theme.BAD else Theme.GOOD
                g2.fill(RoundRectangle2D.Double(0.0, 0.0, w * displayed, h, h, h))
            }

            if (peak > 0.001f) {
                val x = (w * peak).coerceIn(2.0, w - 2.0)
                g2.color = if (peak > CLIP_THRESHOLD) Theme.BAD else Theme.TEXT
                g2.fill(RoundRectangle2D.Double(x - 1.0, 0.0, 2.0, h, 2.0, 2.0))
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val FLOOR_DB = -60f
        const val FALLBACK = 0.86f
        const val PEAK_HOLD_FRAMES = 25
        /** -3 dBFS on the normalised scale. */
        const val CLIP_THRESHOLD = 0.95f
    }
}
