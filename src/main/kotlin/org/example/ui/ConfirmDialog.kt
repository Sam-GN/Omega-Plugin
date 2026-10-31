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
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * A modal yes/no, painted to match the window.
 *
 * `JOptionPane` is not used because the destructive choice has to be visibly destructive, and its
 * buttons come from the look-and-feel — under Nimbus they are silver and cannot be recoloured per
 * dialog, which is the same wall the combos and menus ran into.
 *
 * The confirming button is the coloured one and the cancel is neutral, so the dangerous action is
 * the one you have to aim at rather than the one under your finger.
 */
object ConfirmDialog {

    fun ask(
        owner: Window?,
        title: String,
        message: String,
        detail: String? = null,
        confirmText: String,
        confirmColour: Color = Theme.BAD,
    ): Boolean {
        var confirmed = false

        val dialog = JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL).apply {
            isUndecorated = true
            isResizable = false
        }

        val body = JPanel(BorderLayout(0, 16)).apply {
            background = Theme.PANEL
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.TRACK, 1),
                BorderFactory.createEmptyBorder(22, 26, 18, 26),
            )
        }

        body.add(
            JPanel(BorderLayout(0, 8)).apply {
                isOpaque = false
                add(
                    JLabel(message).apply {
                        font = Theme.TITLE_FONT
                        foreground = Theme.TEXT
                    },
                    BorderLayout.NORTH,
                )
                if (detail != null) {
                    add(
                        JLabel(detail).apply {
                            font = Theme.BODY_FONT
                            foreground = Theme.TEXT_DIM
                        },
                        BorderLayout.CENTER,
                    )
                }
            },
            BorderLayout.CENTER,
        )

        body.add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                isOpaque = false
                add(
                    PushButton("Cancel") {
                        dialog.dispose()
                    }
                )
                add(
                    PushButton(confirmText, confirmColour) {
                        confirmed = true
                        dialog.dispose()
                    }
                )
            },
            BorderLayout.SOUTH,
        )

        dialog.contentPane.add(body)
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
        return confirmed
    }

}

/**
 * A modal "here is what went wrong", matching [ConfirmDialog].
 *
 * Takes a list because failures come in batches — importing five files can fail on three of them,
 * and one line each is the difference between a usable message and "something went wrong".
 */
object MessageDialog {

    /**
     * [link] is a line that opens an address: its text, and the URL. One at most, and after
     * everything else, because the one caller with one — About — has one place to send people.
     *
     * [maxLines] exists for the one caller that is not a list of failures.
     *
     * The cap is there so a folder full of broken presets cannot produce a dialog taller than the
     * screen — a sensible default for messages that are *unbounded*. A licence notice is a fixed,
     * known length that must be shown in full, and truncating it with "… and 14 more" would defeat
     * the point of displaying it at all.
     */
    fun show(
        owner: Window?,
        title: String,
        heading: String,
        lines: List<String>,
        maxLines: Int = MAX_LINES,
        link: Pair<String, String>? = null,
    ) {
        val dialog = JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL).apply {
            isUndecorated = true
            isResizable = false
        }

        val detail = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            for (line in lines.take(maxLines)) {
                if (line.isEmpty()) {
                    // A blank line is a group break. An empty JLabel would not do: it lays out at
                    // zero height and the separation silently disappears.
                    add(javax.swing.Box.createVerticalStrut(GROUP_GAP))
                    continue
                }
                add(
                    JLabel(line).apply {
                        font = Theme.BODY_FONT
                        foreground = Theme.TEXT_DIM
                        alignmentX = 0f
                    }
                )
            }
            if (lines.size > maxLines) {
                add(
                    JLabel("… and ${lines.size - maxLines} more").apply {
                        font = Theme.BODY_FONT
                        foreground = Theme.DISABLED
                        alignmentX = 0f
                    }
                )
            }
            if (link != null) {
                add(javax.swing.Box.createVerticalStrut(GROUP_GAP))
                add(linkLabel(link.first, link.second))
            }
        }

        val body = JPanel(BorderLayout(0, 14)).apply {
            background = Theme.PANEL
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.TRACK, 1),
                BorderFactory.createEmptyBorder(22, 26, 18, 26),
            )
            add(
                JLabel(heading).apply {
                    font = Theme.TITLE_FONT
                    foreground = Theme.TEXT
                },
                BorderLayout.NORTH,
            )
            add(detail, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                    isOpaque = false
                    add(PushButton("OK") { dialog.dispose() })
                },
                BorderLayout.SOUTH,
            )
        }

        dialog.contentPane.add(body)
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
    }

    /**
     * A line that opens [url] in the browser when clicked.
     *
     * Accent-coloured and underlined, with the hand cursor and the address in the tooltip, so it
     * reads as a link without a word of explanation. Where no browser can be reached — a Linux
     * desktop without a URL handler is where `Desktop.browse` fails — the address goes on the
     * clipboard instead and the line says so, because a link that silently does nothing is worse
     * than no link.
     */
    private fun linkLabel(text: String, url: String): JLabel {
        val label = JLabel("<html><u>$text</u></html>").apply {
            font = Theme.BODY_FONT
            foreground = Theme.ACCENT
            alignmentX = 0f
            toolTipText = url
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        Clicks.onRelease(label, enabled = { true }) { open(url, label) }
        return label
    }

    private fun open(url: String, label: JLabel) {
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (desktop == null || !desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            copyInstead(url, label)
            return
        }
        // Off the event thread: on some desktops browse() blocks until the browser has started.
        Thread({
            runCatching { desktop.browse(java.net.URI(url)) }
                .onFailure { javax.swing.SwingUtilities.invokeLater { copyInstead(url, label) } }
        }, "omega-open-link").apply { isDaemon = true }.start()
    }

    private fun copyInstead(url: String, label: JLabel) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(url), null)
        }
        label.text = "<html>Copied the address — paste it into a browser:<br>$url</html>"
        label.foreground = Theme.TEXT_DIM
        label.cursor = Cursor.getDefaultCursor()
        javax.swing.SwingUtilities.getWindowAncestor(label)?.pack()
    }

    /** Default cap, for the unbounded case: a batch of failures. See [show]. */
    private const val MAX_LINES = 8

    /** Height of a blank line, used to group related lines. */
    private const val GROUP_GAP = 9
}

/**
 * A modal one-line text prompt, matching [ConfirmDialog].
 *
 * Returns null if cancelled, so an empty string and "cancelled" stay distinguishable.
 */
object TextPrompt {

    fun ask(owner: Window?, title: String, label: String, initial: String = ""): String? {
        var result: String? = null

        val dialog = JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL).apply {
            isUndecorated = true
            isResizable = false
        }

        val field = JTextField(initial, 22).apply {
            font = Theme.BODY_FONT
            background = Theme.KNOB_BODY
            foreground = Theme.TEXT
            caretColor = Theme.ACCENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.TRACK, 1),
                BorderFactory.createEmptyBorder(6, 8, 6, 8),
            )
        }

        fun accept() {
            result = field.text
            dialog.dispose()
        }

        val body = JPanel(BorderLayout(0, 14)).apply {
            background = Theme.PANEL
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.TRACK, 1),
                BorderFactory.createEmptyBorder(20, 24, 16, 24),
            )
            add(
                JLabel(label).apply {
                    font = Theme.LABEL_FONT
                    foreground = Theme.TEXT_DIM
                },
                BorderLayout.NORTH,
            )
            add(field, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
                    isOpaque = false
                    add(PushButton("Cancel") { dialog.dispose() })
                    add(PushButton("Save", Theme.ACCENT_SOFT) { accept() })
                },
                BorderLayout.SOUTH,
            )
        }

        // Enter accepts, which is what anyone typing a name expects.
        field.addActionListener { accept() }

        dialog.contentPane.add(body)
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        field.requestFocusInWindow()
        dialog.isVisible = true
        return result
    }
}

// The dialogs' own button used to live here. It is now `PushButton`, shared with the device bar, so
// there is one button in this application rather than one per place that needed one.
