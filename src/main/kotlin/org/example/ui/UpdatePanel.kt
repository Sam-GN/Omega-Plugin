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

import org.example.update.Checksums
import org.example.update.Platform
import org.example.update.ReleaseNotes
import org.example.update.Updater
import org.example.update.Updates
import org.example.update.Verdict
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Window
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * The bottom of the About dialog: **Check for updates**, and what it leads to.
 *
 * Pressing Check asks GitHub once. The answer replaces the small line under the button: you have the
 * newest, or a newer version with its notes and an **Update** button, or why it could not be asked.
 * Pressing Update downloads this platform's bundle, verifies it against the release's checksum list,
 * stages it, and hands the last step to the window, which saves everything and exits.
 *
 * Those two presses are the only times the program touches the network, and the line under the
 * button says so before the first press — that promise is the reason this panel exists in this
 * shape rather than as a check at startup.
 */
class UpdatePanel(private val host: Host) : JPanel() {

    /** What the window provides: whether sound is running, and the way out. */
    interface Host {
        val window: Window?
        fun isStreaming(): Boolean

        /** Loop lanes recorded and not saved, which an update's restart would lose. */
        fun unsavedLanes(): Int

        /** Stops the stream, saves the session and settings, runs [launch], exits. */
        fun exitAfterSaving(launch: () -> Unit)
    }

    private val platform = Platform.current()
    private val installer = Updates.installer()

    private val checkButton = PushButton("Check for updates") { check() }
    private val line = paragraph(PROMISE)

    private val notes = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Theme.BODY_FONT
        background = Theme.PANEL
        foreground = Theme.TEXT_DIM
        caretColor = Theme.PANEL
        border = BorderFactory.createEmptyBorder(NOTES_TOP_PADDING, 8, NOTES_TOP_PADDING, 8)
        columns = COLUMNS
    }
    private val notesPane = JScrollPane(notes).apply {
        DarkChrome.apply(this)
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        alignmentX = 0f
        isVisible = false
    }
    private val readMore = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        alignmentX = 0f
        isVisible = false
    }
    private val updateButton = PushButton("Update", Theme.ACCENT_SOFT) { update() }.apply { isVisible = false }
    private val detail = paragraph("").apply { isVisible = false }

    private var available: Verdict.Available? = null
    private val updater = Updater(
        source = Updates.source(),
        installer = installer,
        running = Updates.runningVersion(),
        platform = platform,
        listener = Reports(),
        post = { SwingUtilities.invokeLater(it) },
    )

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = 0f
        add(row(checkButton))
        add(Box.createVerticalStrut(6))
        add(line)
        add(Box.createVerticalStrut(8))
        add(notesPane)
        add(Box.createVerticalStrut(4))
        add(readMore)
        add(Box.createVerticalStrut(8))
        add(row(updateButton))
        add(Box.createVerticalStrut(6))
        add(detail)
    }

    private fun row(button: PushButton) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        alignmentX = 0f
        add(button)
    }

    /** A wrapping line of small text. HTML, because a `JLabel` will not wrap on its own. */
    private fun paragraph(text: String) = JLabel().apply {
        font = Theme.BODY_FONT
        foreground = Theme.TEXT_DIM
        alignmentX = 0f
        this.text = html(text)
    }

    /**
     * A label breaks lines at spaces only, and a file path or an address has none: one of those in
     * a message would set the dialog's width. A zero-width space after each separator is a break
     * the layout may take when it needs one and nothing when it does not.
     */
    private fun html(text: String): String {
        val breakable = text.replace(Regex("([/\\\\?=&.-])")) { it.value + "​" }
        val escaped = breakable.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
        return "<html><div style='width:${WIDTH}px'>$escaped</div></html>"
    }

    private fun say(label: JLabel, text: String, colour: Color = Theme.TEXT_DIM) {
        label.text = html(text)
        label.foreground = colour
        label.isVisible = text.isNotEmpty()
        repack()
    }

    private fun repack() {
        revalidate()
        SwingUtilities.getWindowAncestor(this)?.pack()
    }

    private fun check() {
        checkButton.isEnabled = false
        hideOffer()
        say(line, "Asking GitHub…")
        updater.check()
    }

    private fun hideOffer() {
        available = null
        notesPane.isVisible = false
        readMore.isVisible = false
        readMore.removeAll()
        updateButton.isVisible = false
        detail.isVisible = false
    }

    private fun offer(verdict: Verdict.Available) {
        val asset = verdict.asset ?: return
        available = verdict
        val body = ReleaseNotes.plain(verdict.release.body)
        if (body.isNotEmpty()) {
            notes.text = body
            notes.caretPosition = 0
            // Sized here rather than left to the text area: a wrapping `JTextArea` still reports
            // its longest unwrapped line as its preferred width, and a scroll pane built on that
            // would set the dialog's width from the longest sentence in the notes. The height is
            // measured at that width, since a paragraph is one line of text and several on screen.
            // Full notes stop at NOTE_LINES rows with no bottom padding, so the box ends on a whole
            // row rather than showing the top of the next one in the padding.
            notes.setSize(WIDTH, Short.MAX_VALUE.toInt())
            val lineHeight = notes.getFontMetrics(notes.font).height
            val wanted = notes.preferredSize.height
            val most = NOTES_TOP_PADDING + NOTE_LINES * lineHeight
            val height = if (wanted <= most + NOTES_TOP_PADDING) wanted else most
            notesPane.preferredSize = java.awt.Dimension(WIDTH + 2, height + 2)
            notesPane.maximumSize = notesPane.preferredSize
            notesPane.isVisible = true
        }
        if (verdict.release.htmlUrl.isNotEmpty()) {
            readMore.add(MessageDialog.linkLabel("Read more on GitHub", verdict.release.htmlUrl))
            readMore.isVisible = true
        }
        val size = Updates.describeSize(asset.size)
        val reason = installer.cannotApply()
        if (reason == null) {
            updateButton.text = "Update"
            say(
                detail,
                "Downloads ${asset.name} ($size) from github.com, checks it, and replaces this " +
                    "program. Your settings and presets are kept.",
            )
        } else {
            updateButton.text = "Download"
            say(detail, "Downloads ${asset.name} ($size) from github.com and checks it. $reason")
        }
        updateButton.isEnabled = true
        updateButton.isVisible = true
        repack()
    }

    private fun update() {
        val verdict = available ?: return
        // Only where it will restart: a Download closes nothing.
        val lanes = host.unsavedLanes()
        if (installer.cannotApply() == null && (host.isStreaming() || lanes > 0)) {
            val lost = when (lanes) {
                0 -> ""
                1 -> "1 loop lane is not saved and will be lost; to keep it, press its SAVE first. "
                else -> "$lanes loop lanes are not saved and will be lost; to keep one, press its SAVE first. "
            }
            val go = ConfirmDialog.ask(
                owner = host.window,
                title = "Update",
                message = if (host.isStreaming()) "Updating stops the sound and closes Omega-Plugin." else "Updating closes Omega-Plugin.",
                detail = lost + "It opens again as the new version when the update is done.",
                confirmText = "Update",
                confirmColour = if (lanes > 0) Theme.BAD else Theme.ACCENT_SOFT,
            )
            if (!go) return
        }
        updateButton.isEnabled = false
        checkButton.isEnabled = false
        say(detail, "Downloading…")
        updater.update(verdict)
    }

    /** The updater's report, already on the event thread. */
    private inner class Reports : Updater.Listener {

        override fun checked(verdict: Verdict) {
            checkButton.isEnabled = true
            val headline = Updates.headline(verdict, platform)
            when (verdict) {
                is Verdict.Available -> {
                    say(line, headline, if (verdict.asset != null) Theme.TEXT else Theme.TEXT_DIM)
                    offer(verdict)
                }
                is Verdict.UpToDate -> say(line, headline, Theme.GOOD)
                is Verdict.VersionUnknown -> say(line, "$headline Releases: ${Updates.RELEASES_PAGE}")
                else -> say(line, headline)
            }
        }

        override fun failed(message: String) {
            checkButton.isEnabled = true
            if (available != null) {
                // Back to pressable, except after a checksum mismatch: a corrupt or tampered file is
                // a reason to go and look, not to press again.
                updateButton.isEnabled = message != Checksums.MISMATCH
                say(detail, message, Theme.BAD)
            } else {
                say(line, message, Theme.BAD)
            }
        }

        override fun progress(text: String) = say(detail, text)

        override fun downloaded(file: Path, reason: String) {
            checkButton.isEnabled = true
            say(detail, "Downloaded ${file.fileName} to ${file.parent}. $reason ${Updates.howToInstall(platform)}", Theme.TEXT)
        }

        override fun readyToRestart(launch: () -> Unit) {
            say(detail, "Restarting…")
            // Let the line paint before the window goes.
            SwingUtilities.invokeLater { host.exitAfterSaving(launch) }
        }
    }

    companion object {
        /** The words under the button before anything is pressed. README.md's Network section says the same. */
        const val PROMISE =
            "Omega-Plugin never connects to the internet on its own. This button, and the Update " +
                "button it may show, are the only two times it does. It fetches the release list " +
                "from github.com and sends nothing about you."

        /** Width of the small text, in pixels, and of the notes, in characters of the body font. */
        private const val WIDTH = 380
        private const val COLUMNS = 48
        private const val NOTE_LINES = 12
        private const val NOTES_TOP_PADDING = 6
    }
}
