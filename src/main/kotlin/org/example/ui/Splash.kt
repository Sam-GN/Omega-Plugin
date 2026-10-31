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

import org.example.audio.linux.LinuxPlatform
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The opening: the logo dead centre of the screen from the moment `main` reaches Swing until the
 * window is painted, and then on the window's veil as it lifts, so the logo fades out as the
 * application fades in behind it.
 *
 * Two surfaces, one painter. [show] puts up a small frameless window on the background colour
 * with the logo centred on the screen; the main window, centred on the same screen, then appears
 * under its veil with the same logo at the same point, so the eye sees one logo and a window
 * forming around it. [paint] is what both draw, which is what keeps them in register. [dismiss]
 * takes the small window down once the main window has its first frame — not before, or there
 * is a flash of desktop between the two.
 *
 * Only the GUI path shows it. `--selftest` and friends never reach Swing, and a splash flashing
 * over a terminal would be the wrong kind of memorable.
 *
 * **On Linux the paint has to be waited for.** X11 maps a window first and asks for its paint
 * later, as an event of its own — one that arrives behind the event building the main window, so
 * the splash sat unpainted for the half second that takes, in the X server's default white.
 * [whenPainted] is how `main` waits for it there; on Windows the paint arrives in time and the
 * order is left as it was.
 */
object Splash {

    /** Drawn size of the logo, on both surfaces. */
    private const val LOGO = 192

    /** The splash window's side: the logo with room around it. */
    private const val SIDE = 360

    /** How long [whenPainted] waits for a paint that may never come — a window manager can refuse to show it. */
    private const val PAINT_WAIT_MILLIS = 400

    private var window: JWindow? = null
    private var painted = false
    private var afterPaint: (() -> Unit)? = null

    fun show() {
        if (window != null) return
        val w = JWindow()
        w.focusableWindowState = false
        // What X11 fills the window with between mapping it and its first paint. Unset, that is
        // white: the rectangle that came before the logo.
        if (LinuxPlatform.isLinux) w.background = Theme.BACKGROUND
        w.contentPane = object : JComponent() {
            init { preferredSize = Dimension(SIDE, SIDE) }
            override fun paintComponent(g: Graphics) {
                paint(g, width, height, 1f)
                if (!painted) {
                    painted = true
                    runAfterPaint()
                }
            }
        }
        w.pack()
        w.setLocationRelativeTo(null)
        w.isVisible = true
        window = w
    }

    /**
     * Runs [action] on the event thread once the splash has been painted — or after
     * [PAINT_WAIT_MILLIS] if it has not, so a window manager that never shows it cannot hold up
     * the application. At once if there is no splash or it has already painted.
     */
    fun whenPainted(action: () -> Unit) {
        if (window == null || painted) {
            SwingUtilities.invokeLater(action)
            return
        }
        afterPaint = action
        Timer(PAINT_WAIT_MILLIS) { runAfterPaint() }.apply { isRepeats = false }.start()
    }

    /** Exactly once, whichever of the paint and the timeout gets here first. */
    private fun runAfterPaint() {
        val action = afterPaint ?: return
        afterPaint = null
        SwingUtilities.invokeLater(action)
    }

    fun dismiss() {
        window?.dispose()
        window = null
    }

    /**
     * The background colour over [width] by [height] with the logo centred, at [alpha]. The veil
     * calls this with its own alpha as it lifts; the splash window calls it at one.
     */
    fun paint(g: Graphics, width: Int, height: Int, alpha: Float) {
        if (alpha <= 0f) return
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
            g2.color = Theme.BACKGROUND
            g2.fillRect(0, 0, width, height)
            val logo = AppIcon.images.lastOrNull() ?: return
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(logo, (width - LOGO) / 2, (height - LOGO) / 2, LOGO, LOGO, null)
        } finally {
            g2.dispose()
        }
    }
}
