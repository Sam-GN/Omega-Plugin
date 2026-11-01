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

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import javax.swing.BorderFactory
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * The app's own title bar, replacing the one the OS would draw.
 *
 * Carries the icon, the title, and minimise / maximise / close. Dragging it moves the window and
 * double-clicking toggles maximised, because an undecorated frame gets none of that for free.
 *
 * Worth knowing what this costs: dropping the native frame also drops Aero Snap, the system window
 * menu, and the OS drop shadow. That is the same trade IntelliJ and Discord make.
 */
class TitleBar(
    private val frame: JFrame,
    title: String,
    /**
     * Whether the ☰ menu is there. The main window's bar has it; the loop board's own window has
     * nothing to put in one, and an About and an Exit on a secondary window would be two more ways
     * to do what the main window already does.
     */
    private val withMenu: Boolean = true,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private val maximiseButton = WindowButton(Glyph.MAXIMISE) { toggleMaximised() }

    /**
     * The hamburger's menu.
     *
     * Rebuilt every time it opens rather than kept in sync: the preset list, and which entries are
     * enabled, both depend on state that changes while the menu is closed, and rebuilding is far
     * harder to get subtly wrong than a set of listeners would be.
     */
    private val menu = DarkMenu.popup()

    /** Called with the empty menu just before it opens, to fill in everything above About. */
    var onMenuOpening: ((JPopupMenu) -> Unit)? = null

    /**
     * What About's Check for updates needs from the window. Null leaves About without the button,
     * which is what a secondary window's bar would want if it had a menu at all.
     */
    var updateHost: UpdatePanel.Host? = null

    private val titleLabel = JLabel(title).apply {
        font = Theme.BODY_FONT
        foreground = Theme.TEXT_DIM
        border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
    }

    private val baseTitle = title

    /**
     * Appended after the app name, for the loaded preset. Null clears it.
     *
     * Set on the frame as well as the label so the taskbar agrees with the window.
     */
    var subtitle: String? = null
        set(value) {
            field = value
            val text = if (value.isNullOrBlank()) baseTitle else "$baseTitle — $value"
            titleLabel.text = text
            frame.title = text
        }

    private var pressScreen: Point? = null
    private var pressFrame: Point? = null

    init {
        background = Theme.PANEL
        border = BorderFactory.createEmptyBorder(0, 10, 0, 0)
        preferredSize = Dimension(0, 34)

        val icon = JLabel().apply {
            AppIcon.images.firstOrNull { it.getWidth(null) >= 24 }?.let {
                this.icon = ImageIcon(it.getScaledInstance(18, 18, Image.SCALE_SMOOTH))
            }
            border = BorderFactory.createEmptyBorder(0, 0, 0, 6)
        }

        val menuButton = WindowButton(Glyph.MENU, preferredWidth = 30) { }
        menuButton.onClick = {
            menu.removeAll()
            onMenuOpening?.invoke(menu)
            menu.add(DarkMenu.item("About Omega-Plugin") { showAbout() })
            // Its own entry rather than a paragraph inside About: the GPL asks for the notice to be
            // conspicuous, and something you have to go looking for inside another dialog is not.
            menu.add(DarkMenu.item("Licences") { showLicences() })
            menu.add(DarkMenu.item("Exit") { onClose() })
            menu.show(menuButton, 0, menuButton.height)
        }
        menuButton.toolTipText = "Menu"

        val label = titleLabel

        // Nested BorderLayouts rather than a BoxLayout: every region gets the bar's full height, so
        // the icon, the hamburger and the title all centre on the same line without alignment hints.
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(icon, BorderLayout.WEST)
                        if (withMenu) add(menuButton, BorderLayout.EAST)
                    },
                    BorderLayout.WEST,
                )
                add(label, BorderLayout.CENTER)
            },
            BorderLayout.CENTER,
        )

        add(
            JPanel().apply {
                isOpaque = false
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                add(WindowButton(Glyph.MINIMISE) { frame.extendedState = Frame.ICONIFIED })
                add(maximiseButton)
                add(WindowButton(Glyph.CLOSE) { onClose() })
            },
            BorderLayout.EAST,
        )

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.clickCount == 2) {
                    toggleMaximised()
                    return
                }
                pressScreen = e.locationOnScreen
                pressFrame = frame.location
            }

            override fun mouseDragged(e: MouseEvent) {
                val origin = pressScreen ?: return
                val now = e.locationOnScreen

                // Dragging a maximised window restores it and keeps it under the cursor, which is
                // what every other titled window does.
                if (frame.extendedState == Frame.MAXIMIZED_BOTH) {
                    val fraction = e.x.toDouble() / width.coerceAtLeast(1)
                    frame.extendedState = Frame.NORMAL
                    val restored = frame.width
                    frame.setLocation(now.x - (restored * fraction).toInt(), now.y - e.y)
                    pressScreen = now
                    pressFrame = frame.location
                    return
                }

                val start = pressFrame ?: return
                frame.setLocation(start.x + (now.x - origin.x), start.y + (now.y - origin.y))
            }

            override fun mouseReleased(e: MouseEvent) {
                pressScreen = null
                pressFrame = null
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)

        frame.addWindowStateListener { syncMaximiseGlyph() }

    }

    /**
     * The About box, through the application's own dialog rather than `JOptionPane`.
     *
     * `JOptionPane` brings two things that do not belong here: the platform's window frame, around a
     * program that draws its own title bar, and a look-and-feel button that Nimbus paints silver and
     * will not let anyone recolour. [MessageDialog] is undecorated and already painted to match.
     */
    private fun showAbout() {
        // Only what can actually be known at runtime: the version comes from the jar manifest and is
        // absent when running from loose classes, so it is omitted rather than invented.
        val version = javaClass.`package`?.implementationVersion
        MessageDialog.show(
            owner = frame,
            title = "About",
            heading = "Omega-Plugin",
            lines = Licences.about(version),
            link = "User manual" to Licences.MANUAL_URL,
            // Below the lines, not among them: `Licences.about` answers "what is this and which
            // version", and the button is the one thing on the dialog that does something.
            extra = updateHost?.let { UpdatePanel(it) },
        )
    }

    /**
     * The full notice: copyright, licence, warranty disclaimer, where the source is, and every
     * third-party component with the reason it is allowed to be here.
     *
     * Uncapped, unlike every other use of this dialog — a licence shown as "… and 14 more" would be
     * worse than not showing one.
     */
    private fun showLicences() {
        MessageDialog.show(
            owner = frame,
            title = "Licences",
            heading = "Omega-Plugin",
            lines = Licences.full(),
            maxLines = Int.MAX_VALUE,
        )
    }

    private fun toggleMaximised() {
        if (frame.extendedState == Frame.MAXIMIZED_BOTH) {
            frame.extendedState = Frame.NORMAL
        } else {
            frame.maximizedBounds = workArea()
            frame.extendedState = Frame.MAXIMIZED_BOTH
        }
        syncMaximiseGlyph()
    }

    /**
     * The current screen minus whatever the desktop reserves, i.e. the taskbar.
     *
     * An undecorated frame set to [Frame.MAXIMIZED_BOTH] covers the *whole* display, taskbar
     * included — measured here at 2560x1440 against a 2560x1400 work area. Setting
     * `maximizedBounds` first is what keeps it honest. Recomputed on each maximise so it follows
     * the window to another monitor and picks up a moved or resized taskbar.
     */
    private fun workArea(): Rectangle {
        val config = frame.graphicsConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    private fun syncMaximiseGlyph() {
        maximiseButton.glyph =
            if (frame.extendedState == Frame.MAXIMIZED_BOTH) Glyph.RESTORE else Glyph.MAXIMISE
    }


    private enum class Glyph { MENU, MINIMISE, MAXIMISE, RESTORE, CLOSE }

    /**
     * One caption button, painted rather than themed so it matches the rest of the window on both
     * Windows and Linux.
     */
    private class WindowButton(
        glyph: Glyph,
        preferredWidth: Int = 44,
        var onClick: () -> Unit,
    ) : JComponent() {

        var glyph: Glyph = glyph
            set(value) {
                field = value
                repaint()
            }

        private var hover = false

        init {
            preferredSize = Dimension(preferredWidth, 34)
            // All three, not just preferred and maximum. A lightweight JComponent with no explicit
            // minimum falls through to Component.size(), i.e. its *current* size, which is stale
            // while the container is being laid out — BoxLayout then hands the button about half
            // the row height and the glyph, centred on that, sits 8 px above the title.
            minimumSize = preferredSize
            maximumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hover = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    repaint()
                }
            })
            Clicks.onRelease(this, enabled = { true }) { onClick() }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                if (hover) {
                    // Caption buttons keep the neutral grey; the accent tint is reserved for the
                    // menu's own rows. Close keeps the red, where the colour is the warning rather
                    // than decoration.
                    g2.color = if (glyph == Glyph.CLOSE) CLOSE_HOVER else Theme.PANEL_RAISED
                    g2.fillRect(0, 0, width, height)
                }

                g2.color = if (hover && glyph == Glyph.CLOSE) Color.WHITE else Theme.TEXT_DIM
                g2.stroke = BasicStroke(1.2f)

                val cx = width / 2.0
                val cy = height / 2.0
                val s = 4.5

                when (glyph) {
                    Glyph.MENU -> {
                        val w = 5.5
                        for (offset in doubleArrayOf(-4.0, 0.0, 4.0)) {
                            g2.draw(Line2D.Double(cx - w, cy + offset, cx + w, cy + offset))
                        }
                    }
                    Glyph.MINIMISE -> g2.draw(Line2D.Double(cx - s, cy, cx + s, cy))
                    Glyph.MAXIMISE -> g2.draw(Rectangle2D.Double(cx - s, cy - s, s * 2, s * 2))
                    Glyph.RESTORE -> {
                        // Front square plus the top-right corner of the one behind it.
                        g2.draw(Rectangle2D.Double(cx - s, cy - s + 2, s * 2 - 2, s * 2 - 2))
                        g2.draw(Line2D.Double(cx - s + 2, cy - s, cx + s, cy - s))
                        g2.draw(Line2D.Double(cx + s, cy - s, cx + s, cy + s - 2))
                    }
                    Glyph.CLOSE -> {
                        g2.draw(Line2D.Double(cx - s, cy - s, cx + s, cy + s))
                        g2.draw(Line2D.Double(cx + s, cy - s, cx - s, cy + s))
                    }
                }
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            /** The Windows close-button red, which users read as "this one closes it". */
            val CLOSE_HOVER = Color(0xE8, 0x11, 0x23)
        }
    }
}
