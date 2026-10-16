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
 * A wrapping row layout for the device bar.
 *
 * `FlowLayout` wraps but reports a preferred height for a single line, so a wrapped bar inside a
 * `BorderLayout` is silently cut off — the classic Swing trap. This reports the height it will
 * actually use.
 *
 * On top of that it keeps the bar looking the way it did before it could wrap: while everything fits
 * on **one** line, any surplus width is handed to the components passed to [grow] — the two device
 * combos — so they still stretch to fill the bar. Once it wraps, stretching stops and the rows pack
 * left, because a stretched control on a wrapped row just looks like a mistake.
 */
class BarLayout(private val hgap: Int = 10, private val vgap: Int = 10) : LayoutManager {

    private val growable = LinkedHashSet<Component>()

    /** Marks components that should absorb surplus width while the bar is a single line. */
    fun grow(vararg components: Component) {
        growable += components
    }

    private fun visible(parent: Container): List<Component> =
        (0 until parent.componentCount).map { parent.getComponent(it) }.filter { it.isVisible }

    /** Splits the children into rows that fit [available] pixels, preserving order. */
    private fun rows(parent: Container, available: Int): List<List<Component>> {
        val grouped = mutableListOf<MutableList<Component>>()
        var row = mutableListOf<Component>()
        var used = 0

        for (component in visible(parent)) {
            val w = component.preferredSize.width
            val needed = if (row.isEmpty()) w else used + hgap + w
            if (row.isNotEmpty() && needed > available) {
                grouped += row
                row = mutableListOf()
                used = 0
            }
            used = if (row.isEmpty()) w else used + hgap + w
            row += component
        }
        if (row.isNotEmpty()) grouped += row
        return grouped
    }

    private fun width(row: List<Component>): Int =
        row.sumOf { it.preferredSize.width } + hgap * (row.size - 1).coerceAtLeast(0)

    private fun height(row: List<Component>): Int = row.maxOfOrNull { it.preferredSize.height } ?: 0

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val available = parent.width - insets.left - insets.right
        val grouped = rows(parent, available)
        if (grouped.isEmpty()) return

        var y = insets.top
        for (row in grouped) {
            val rowHeight = height(row)
            val growers = row.filter { it in growable }
            // Only while the bar is one line: see the class docs.
            val surplus =
                if (grouped.size == 1 && growers.isNotEmpty()) (available - width(row)).coerceAtLeast(0) else 0
            val share = if (growers.isEmpty()) 0 else surplus / growers.size

            var x = insets.left
            for (component in row) {
                val size = component.preferredSize
                val w = size.width + if (component in growers) share else 0
                component.setBounds(x, y + (rowHeight - size.height) / 2, w, size.height)
                x += w + hgap
            }
            y += rowHeight + vgap
        }
    }

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        // Width is only known once the parent has been sized; before that everything goes on one
        // line, which is what makes the wide single-row bar the layout the window opens at.
        val available =
            if (parent.width > 0) parent.width - insets.left - insets.right else Int.MAX_VALUE
        val grouped = rows(parent, available)
        if (grouped.isEmpty()) return Dimension(insets.left + insets.right, insets.top + insets.bottom)

        return Dimension(
            grouped.maxOf { width(it) } + insets.left + insets.right,
            grouped.sumOf { height(it) } + vgap * (grouped.size - 1) + insets.top + insets.bottom,
        )
    }

    override fun minimumLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val widest = visible(parent).maxOfOrNull { it.minimumSize.width } ?: 0
        val tallest = visible(parent).maxOfOrNull { it.preferredSize.height } ?: 0
        return Dimension(widest + insets.left + insets.right, tallest + insets.top + insets.bottom)
    }

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit

    override fun removeLayoutComponent(comp: Component?) {
        if (comp != null) growable -= comp
    }
}
