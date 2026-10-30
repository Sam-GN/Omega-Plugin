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
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * What a panel has to be to stack: a height for a width, and an opacity the stack can fade.
 *
 * [ChainBoard] and [LoopBoard] both are. The stack never asks a panel's preferred size directly,
 * because the wrap of a pedal board depends on the width it is given and the stack has to know the
 * heights before it has laid anything out.
 */
interface Stackable {
    fun preferredHeightAt(available: Int): Int
    var alpha: Float
}

/**
 * The boards, one above another, in a single scroll view: the loop board when it is on, the first
 * pedal board always, the second pedal board in split mode.
 *
 * Above and below rather than side by side, so split mode changes nothing about how a pedal looks
 * or how many fit on a row: the window only gets taller. A panel arriving fades in where it will
 * live and fades out again when switched off — the same gesture a pedal makes arriving on or
 * leaving a board, for the same reason: the eye follows a thing that appears far better than it
 * notices a picture that is suddenly different.
 *
 * **A panel's share of the height follows its opacity.** While the loop board fades in above the
 * first board, the room it takes grows with it, so the first board's rows glide down to make way
 * rather than jumping; while the second board fades in below, the first board's half shrinks the
 * same way. Nothing in the stack ever moves in one step.
 *
 * This is what the scroll pane holds, even with one board, so there is one scrolling path rather
 * than two that could drift apart. It tracks the viewport's width, because the wrap is computed
 * from it, and fills the viewport's height only while the panels fit, so the scrollbar appears
 * exactly when they do not.
 */
class BoardStack(private val first: ChainBoard) : JPanel(null), Scrollable {

    /** A panel that can be on the stack, with whether it is meant to be and whether it is still fading. */
    private inner class Entry(val panel: JComponent, val stackable: Stackable) {
        var showing = false
        val alpha: Float get() = stackable.alpha
    }

    /** Above the first board. */
    private var above: Entry? = null

    /** Below the first board. */
    private var below: Entry? = null

    private val animator = Timer(FRAME_MS) { step() }.apply { isCoalesce = true }

    init {
        isOpaque = false
        add(first)
    }

    /** Brings [board] onto the stack below the first board, fading it in. */
    fun showSecond(board: ChainBoard) {
        below = place(below, board, board)
    }

    /** Fades the second board out; it leaves the hierarchy once it is gone. */
    fun hideSecond() = hide(below)

    /** Brings [board] onto the stack above the first board, fading it in. */
    fun showAbove(board: JComponent, stackable: Stackable) {
        above = place(above, board, stackable)
    }

    /** Fades the panel above out; it leaves the hierarchy once it is gone. */
    fun hideAbove() = hide(above)

    /**
     * Takes [panel] off the stack **at once**, without a fade — for a panel that is going somewhere
     * else, such as the loop board into its own window, where a ghost of it fading here would read
     * as two of them.
     */
    fun detachAbove() {
        val entry = above ?: return
        remove(entry.panel)
        above = null
        revalidate()
        repaint()
    }

    private fun <T> place(current: Entry?, panel: T, stackable: Stackable): Entry where T : JComponent {
        val entry = if (current?.panel === panel) {
            current
        } else {
            current?.let { remove(it.panel) }
            stackable.alpha = 0f
            add(panel)
            Entry(panel, stackable)
        }
        entry.showing = true
        revalidate()
        repaint()
        animator.start()
        return entry
    }

    private fun hide(entry: Entry?) {
        entry ?: return
        entry.showing = false
        animator.start()
    }

    private fun step() {
        var busy = false
        for (slot in listOf(above, below)) {
            val entry = slot ?: continue
            val target = if (entry.showing) 1f else 0f
            val current = entry.alpha
            val next = if (kotlin.math.abs(target - current) < 0.01f) target else current + (target - current) * EASE
            entry.stackable.alpha = next
            if (next != target) {
                busy = true
            } else if (!entry.showing) {
                remove(entry.panel)
                if (slot === above) above = null else below = null
                revalidate()
            }
        }
        if (!busy) animator.stop()
        // The heights follow the alphas — see doLayout — so every frame is a layout. A *validate*,
        // not a bare doLayout: setting a child's bounds only marks it invalid, and a pedal board
        // whose height changed has to lay its rows out again or they stay where the previous
        // frame put them. That was visible once: the rows ended the fade a lane too low.
        invalidate()
        validate()
        repaint()
    }

    override fun doLayout() {
        val w = width
        val top = above
        val bottom = below
        if (top == null && bottom == null) {
            first.setBounds(0, 0, w, height)
            return
        }
        // Each fading panel's room is its full height times its alpha, divider included, so it
        // opens and closes smoothly; the first board takes the rest, which keeps its rows gliding.
        val topFull = top?.let { it.stackable.preferredHeightAt(w) + DIVIDER } ?: 0
        val bottomFull = bottom?.let { it.stackable.preferredHeightAt(w) + DIVIDER } ?: 0
        val topRoom = (topFull * (top?.alpha ?: 0f)).roundToInt()
        val bottomRoom = (bottomFull * (bottom?.alpha ?: 0f)).roundToInt()
        val firstHeight = first.preferredHeightAt(w)
        // Whatever the viewport has beyond what the panels need is shared between the pedal boards,
        // so a roomy window keeps both centred in their halves rather than piling the slack under
        // the second. The loop board keeps its own height; it has no rows to centre.
        val needed = topRoom + firstHeight + bottomRoom
        val spare = (height - needed).coerceAtLeast(0)
        val bottomWeight = bottom?.alpha ?: 0f
        val bottomSpare = (spare * bottomWeight / (1f + bottomWeight)).roundToInt()
        val firstSpare = spare - bottomSpare

        var y = 0
        if (top != null) {
            val h = top.stackable.preferredHeightAt(w)
            // Laid at its final size at the top, and revealed from the bottom as its room opens.
            top.panel.setBounds(0, topRoom - DIVIDER - h, w, h)
            y = topRoom
        }
        first.setBounds(0, y, w, firstHeight + firstSpare)
        y += firstHeight + firstSpare
        if (bottom != null) {
            val h = bottom.stackable.preferredHeightAt(w)
            bottom.panel.setBounds(0, y + DIVIDER, w, h + bottomSpare)
        }
    }

    override fun getPreferredSize(): Dimension {
        val available = if (width > 0) width else UNSIZED_WIDTH
        val widest = listOfNotNull(first, above?.panel, below?.panel).maxOf { it.preferredSize.width }
        var height = first.preferredHeightAt(available)
        above?.let { height += DIVIDER + it.stackable.preferredHeightAt(available) }
        below?.let { height += DIVIDER + it.stackable.preferredHeightAt(available) }
        return Dimension(widest, height)
    }

    override fun getMinimumSize(): Dimension = first.minimumSize

    /** A hairline between the panels: separate things, not one long one. */
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        above?.let { paintDivider(g, it, first.y - DIVIDER / 2) }
        below?.let { paintDivider(g, it, it.panel.y - DIVIDER / 2) }
    }

    private fun paintDivider(g: Graphics, entry: Entry, y: Int) {
        if (entry.alpha <= 0f) return
        val g2 = g.create() as Graphics2D
        try {
            if (entry.alpha < 1f) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, entry.alpha)
            }
            g2.color = Theme.TRACK
            g2.drawLine(DIVIDER_INSET, y, width - DIVIDER_INSET, y)
        } finally {
            g2.dispose()
        }
    }

    // ------------------------------------------------------------- scrolling

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = 24

    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = r.height

    /** Always take the viewport's width — that width is what each board's wrap is computed from. */
    override fun getScrollableTracksViewportWidth() = true

    /** Fill the viewport only while the panels fit in it; past that, scroll. */
    override fun getScrollableTracksViewportHeight(): Boolean {
        val viewport = parent as? JViewport ?: return false
        return viewport.height >= preferredSize.height
    }

    private companion object {
        /** Clear space between two panels, with the hairline through its middle. */
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
