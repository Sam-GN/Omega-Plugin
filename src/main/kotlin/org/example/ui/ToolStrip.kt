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
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * The strip between the top bars and the boards, holding the switches that act on the boards as
 * a whole: the eye, the loop, the fork.
 *
 * They lived in the first board's header until 1.5.0. Once the loop board could sit *above* that
 * board, its toggle would have been under the thing it toggled; a strip of their own above
 * everything is where controls that are about the whole stack belong. Drawn a size up from the
 * pedal glyphs and spaced wider, since they stand alone here rather than beside a title.
 *
 * Each icon follows one convention: grey at rest, orange under the pointer, and **struck through
 * while its thing is off** — hidden pedals, no loop board, one board. The line says what is true
 * now and pressing it removes it.
 */
class ToolStrip(
    onEye: () -> Unit,
    onLoop: () -> Unit,
    onSplit: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, GAP, (HEIGHT - 30) / 2)) {

    val eye = EffectPanel.IconButton(EffectPanel.IconButton.Kind.EYE, large = true, onClick = onEye)
    val loop = EffectPanel.IconButton(EffectPanel.IconButton.Kind.LOOP_OFF, large = true, onClick = onLoop)
    val split = EffectPanel.IconButton(EffectPanel.IconButton.Kind.SPLIT_OFF, large = true, onClick = onSplit)

    init {
        isOpaque = true
        background = Theme.STRIP
        border = javax.swing.BorderFactory.createEmptyBorder(0, LEFT_INSET, 0, 0)
        preferredSize = Dimension(0, HEIGHT)
        minimumSize = Dimension(0, HEIGHT)
        add(eye)
        add(loop)
        add(split)
        setHidden(false)
        setLoop(false)
        setSplit(false)
    }

    fun setHidden(hidden: Boolean) {
        eye.kind = if (hidden) EffectPanel.IconButton.Kind.EYE_CLOSED else EffectPanel.IconButton.Kind.EYE
        eye.toolTipText =
            if (hidden) "Show the pedals that are switched off" else "Hide the pedals that are switched off"
    }

    fun setLoop(on: Boolean) {
        loop.kind = if (on) EffectPanel.IconButton.Kind.LOOP else EffectPanel.IconButton.Kind.LOOP_OFF
        loop.toolTipText = if (on) "Put the loop board away" else "Open the loop board"
    }

    fun setSplit(on: Boolean) {
        split.kind = if (on) EffectPanel.IconButton.Kind.SPLIT else EffectPanel.IconButton.Kind.SPLIT_OFF
        split.toolTipText = if (on) "Back to one board" else "Split into two boards"
    }

    private companion object {
        const val HEIGHT = 38
        const val GAP = 14
        const val LEFT_INSET = 10
    }
}
