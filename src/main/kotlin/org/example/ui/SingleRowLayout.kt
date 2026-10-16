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

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager

/**
 * One centred row, at preferred sizes, that never wraps.
 *
 * The distinction from `FlowLayout` is only visible when the container is **too narrow for its
 * contents**, and that is exactly the state a pedal passes through while its expand animation runs.
 * `FlowLayout` responds by starting a second line; inside a fixed-height pedal that means two rows
 * of half-height knobs for the fifth of a second the resize takes. Overflowing and being clipped by
 * the parent is what reads as a reveal rather than as a glitch.
 *
 * Centred rather than left-aligned so the overflow is split between the two edges: knobs appear from
 * both sides as the pedal widens, which is symmetrical with the panel growing about its own centre.
 *
 * [preferredLayoutSize] reports the row's natural width whatever it has actually been given, which
 * is what keeps a pedal at rest sized to its knobs exactly as before.
 */
class SingleRowLayout(private val gap: Int = 0) : LayoutManager {

    private fun visible(parent: Container): List<Component> =
        (0 until parent.componentCount).map { parent.getComponent(it) }.filter { it.isVisible }

    private fun natural(parent: Container): Dimension {
        val children = visible(parent)
        if (children.isEmpty()) return Dimension(0, 0)
        val width = children.sumOf { it.preferredSize.width } + gap * (children.size - 1)
        val height = children.maxOf { it.preferredSize.height }
        return Dimension(width, height)
    }

    override fun layoutContainer(parent: Container) {
        val children = visible(parent)
        if (children.isEmpty()) return
        val insets = parent.insets
        val inner = parent.width - insets.left - insets.right
        val row = natural(parent)

        // Negative when the row is wider than the space, which puts the first knob off the left edge
        // and the last off the right. That is the intent: the parent clips both.
        var x = insets.left + (inner - row.width) / 2
        val middle = insets.top + (parent.height - insets.top - insets.bottom) / 2
        for (child in children) {
            val size = child.preferredSize
            child.setBounds(x, middle - size.height / 2, size.width, size.height)
            x += size.width + gap
        }
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val row = natural(parent)
        return Dimension(
            row.width + insets.left + insets.right,
            row.height + insets.top + insets.bottom,
        )
    }

    /**
     * The same as preferred, deliberately.
     *
     * A minimum that shrank would invite a parent layout to hand this row less than it needs and
     * call the result correct. The row is meant to overflow and be clipped when that happens, and it
     * should be the panel's decision that it happens — not a layout negotiation.
     */
    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit

    override fun removeLayoutComponent(comp: Component?) = Unit
}
