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
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.Timer

/**
 * One board, or two stacked, in a single scroll view.
 *
 * Above and below rather than side by side, so split mode changes nothing about how a pedal looks
 * or how many fit on a row: the window only gets taller. The second board fades in where it will
 * live and fades out again when split is switched off — the same gesture a pedal makes arriving on
 * or leaving a board, for the same reason: the eye follows a thing that appears far better than it
 * notices a picture that is suddenly different.
 *
 * This is what the scroll pane holds now, even with one board, so there is one scrolling path
 * rather than two that could drift apart. It behaves as [ChainBoard] did on its own: it tracks the
 * viewport's width, because the wrap is computed from it, and fills the viewport's height only
 * while the boards fit, so the scrollbar appears exactly when they do not.
 */
class BoardStack(private val first: ChainBoard) : JPanel(null), Scrollable {

    private var second: ChainBoard? = null

    /** True while the second board is meant to be there; false while it is fading out. */
    private var showingSecond = false

    private val animator = Timer(FRAME_MS) { step() }.apply { isCoalesce = true }

    init {
        isOpaque = false
        add(first)
    }

    /** Brings [board] onto the stack below the first, fading it in. */
    fun showSecond(board: ChainBoard) {
        if (second !== board) {
            second?.let { remove(it) }
            second = board
            board.alpha = 0f
            add(board)
        }
        showingSecond = true
        revalidate()
        repaint()
        animator.start()
    }

    /** Fades the second board out; it leaves the hierarchy once it is gone. */
    fun hideSecond() {
        if (second == null) return
        showingSecond = false
        animator.start()
    }

    private fun step() {
        val board = second
        if (board == null) {
            animator.stop()
            return
        }
        val target = if (showingSecond) 1f else 0f
        val next = if (kotlin.math.abs(target - board.alpha) < 0.01f) target else board.alpha + (target - board.alpha) * EASE
        board.alpha = next
        if (next == target) {
            animator.stop()
            if (!showingSecond) {
                remove(board)
                second = null
                revalidate()
            }
        }
        repaint()
    }

    override fun doLayout() {
        val w = width
        val lower = second
        if (lower == null) {
            first.setBounds(0, 0, w, height)
            return
        }
        val firstHeight = first.preferredHeightAt(w)
        val secondHeight = lower.preferredHeightAt(w)
        // Whatever the viewport has beyond what the boards need is shared, so a roomy window keeps
        // both boards centred in their halves rather than piling the slack under the second.
        val spare = ((height - firstHeight - DIVIDER - secondHeight) / 2).coerceAtLeast(0)
        val h1 = firstHeight + spare
        first.setBounds(0, 0, w, h1)
        lower.setBounds(0, h1 + DIVIDER, w, secondHeight + spare)
    }

    override fun getPreferredSize(): Dimension {
        val available = if (width > 0) width else UNSIZED_WIDTH
        val lower = second
        val widest = maxOf(first.preferredSize.width, lower?.preferredSize?.width ?: 0)
        val height = first.preferredHeightAt(available) +
            (lower?.let { DIVIDER + it.preferredHeightAt(available) } ?: 0)
        return Dimension(widest, height)
    }

    override fun getMinimumSize(): Dimension = first.minimumSize

    /** A hairline between the boards: two signal paths, not one long one. */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val lower = second ?: return
        if (lower.alpha <= 0f) return
        g.color = Theme.TRACK
        val y = lower.y - DIVIDER / 2
        g.drawLine(DIVIDER_INSET, y, width - DIVIDER_INSET, y)
    }

    // ------------------------------------------------------------- scrolling

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = 24

    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height

    /** Always take the viewport's width — that width is what each board's wrap is computed from. */
    override fun getScrollableTracksViewportWidth() = true

    /** Fill the viewport only while the boards fit in it; past that, scroll. */
    override fun getScrollableTracksViewportHeight(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.height >= preferredSize.height
    }

    private companion object {
        /** Clear space between the two boards, with the hairline through its middle. */
        const val DIVIDER = 28

        /** The hairline stops short of the edges, so it reads as a rule rather than a border. */
        const val DIVIDER_INSET = 24

        /** ~60 fps, and the same easing the boards use for their own fades. */
        const val FRAME_MS = 16
        const val EASE = 0.35f

        /** Width the boards wrap against before the window has been laid out; see `ChainBoard`. */
        const val UNSIZED_WIDTH = 1500
    }
}
