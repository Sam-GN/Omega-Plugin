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

import org.example.dsp.AudioProcessor
import org.example.dsp.Parameter
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu

/**
 * One pedal: name, bypass switch, and a knob per parameter.
 *
 * The switch writes straight through to [AudioProcessor.enabled], which the audio thread reads on its
 * next block, so toggling takes effect immediately without restarting the stream.
 *
 * The whole panel is a drag handle for re-ordering, reported through [onDragStart] and friends and
 * acted on by [ChainBoard]. Swing delivers a mouse event to the deepest child that actually listens,
 * so the knobs and the toggle keep their own behaviour and only the title and the bare panel body
 * start a drag — which is what you want, since dragging a pedal by its knob would fight the knob.
 */
class EffectPanel(val effect: AudioProcessor) : JPanel(BorderLayout(0, 8)) {

    /** Fired when the user changes anything on this pedal — a knob, or the bypass switch. */
    var onEdited: (() -> Unit)? = null

    var onDragStart: ((EffectPanel, MouseEvent) -> Unit)? = null
    var onDragMove: ((EffectPanel, MouseEvent) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    /**
     * Lifts the pedal visually while it is being dragged.
     *
     * Setting it widens the border by [LIFT_PAD] so the pedal carries its own shadow inside its
     * bounds. Drawn by the board instead, the shadow would sit behind *every* pedal and be painted
     * over by whichever one the lifted pedal happens to be passing.
     */
    var dragging: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val pad = if (value) LIFT_PAD else 0
            border = BorderFactory.createEmptyBorder(12 + pad, 14 + pad, 12 + pad, 14 + pad)
            revalidate()
            repaint()
        }

    /**
     * Fired when the pedal's own size changes — a slot switched to a type with a different number of
     * knobs, or a module expanded. The board wraps its rows against pedal widths, so it has to be
     * told rather than left to find out at the next resize.
     */
    var onResized: (() -> Unit)? = null

    private var knobs: List<Knob> = emptyList()
    private val toggle = ToggleSwitch(effect.enabled)
    private val headerActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
    /**
     * A row that never wraps, so a pedal caught mid-resize clips its knobs instead of folding them.
     *
     * `FlowLayout` was right while a pedal only ever appeared at its final width — it is always sized
     * to fit its knobs exactly, so the wrap could not fire. The expand animation makes the panel
     * genuinely narrower than its contents for a fifth of a second, and a wrapping row answers that
     * by dropping knobs onto a second line inside a fixed-height pedal, where they are cut in half.
     * Clipping at the edge is the behaviour that reads as a reveal.
     */
    private val knobRow = FadeRow(SingleRowLayout(gap = 2))
    private val title = JLabel(effect.name.uppercase()).apply {
        font = Theme.TITLE_FONT
        foreground = Theme.TEXT
    }

    /** Whether the hidden half of a collapsible module is showing. See [AudioProcessor.compactParameters]. */
    private var expanded = false

    /** The width tween, or null. Stopped and restarted if the button is pressed again mid-flight. */
    private var widthAnimator: javax.swing.Timer? = null

    /** True while the tween owns this pedal's size; see [applyPreferredSize]. */
    private var animating = false

    /**
     * Opacity, 0 to 1, driven by [ChainBoard] when a module is being hidden or brought back.
     *
     * Applied in [paint] rather than [paintComponent], because the knobs and the switch are child
     * components: a composite set in `paintComponent` covers the pedal's own body and then the
     * children paint over it at full strength, which looks like a bug rather than a fade.
     */
    var fade: Float = 1f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            repaint()
        }

    /** The parameters the knob row was last built for, so [syncFromEffect] can tell when to rebuild. */
    private var builtFor: List<Parameter> = emptyList()

    /** Header dropdowns and what each one selects, so a preset load can relabel them. */
    private val headerChoices = mutableListOf<Pair<HeaderButton, Parameter>>()

    /**
     * Adds a small button to the header, to the left of the bypass switch.
     *
     * The header rather than a row of its own: pedals are a fixed height that every row on the board
     * is laid out against, so a taller pedal would break the alignment for all of them.
     */
    fun addHeaderButton(label: String, tooltip: String, onClick: () -> Unit): HeaderButton {
        val button = HeaderButton(label, onClick).apply { toolTipText = tooltip }
        headerActions.add(button, 0)
        headerActions.revalidate()
        applyPreferredSize()
        return button
    }

    /**
     * Adds a `⋮` overflow button immediately to the left of the bypass switch.
     *
     * Pedals are knobs and a switch, which is the right shape for things you reach for while
     * playing and the wrong one for settings you touch once. Those need somewhere to live that
     * costs no space until opened, and an overflow menu is that — [build] is called each time it is
     * opened, so the menu can reflect what is true now rather than what was true when the pedal was
     * created.
     *
     * Inserted at the end rather than at index 0 so it sits between any [addHeaderButton] and the
     * switch: actions on the left, then the menu, then bypass.
     */
    fun addHeaderMenu(tooltip: String, build: (JPopupMenu) -> Unit): MenuButton {
        val button = MenuButton(build).apply { toolTipText = tooltip }
        headerActions.add(button, headerActions.componentCount - 1)
        headerActions.revalidate()
        applyPreferredSize()
        return button
    }

    /**
     * Adds a `✕` to the far left of the header, hidden until there is something to clear.
     *
     * Hidden rather than disabled, and that is the point of it: a permanently greyed ✕ on a module
     * that has never loaded anything is a control that spends most of its life explaining that it
     * does nothing. It appears when a file is loaded and goes away again when it is not, so seeing
     * it *is* the statement that something is loaded.
     *
     * Index 0, so it sits left of [addHeaderButton]'s LOAD: the discard is the smaller, rarer action
     * and the one you should have to aim at, and putting it inboard of LOAD would put it under the
     * pointer on the way to the button you actually want.
     */
    fun addHeaderClear(tooltip: String, onClick: () -> Unit): ClearButton {
        val button = ClearButton(onClick).apply {
            toolTipText = tooltip
            isVisible = false
        }
        headerActions.add(button, 0)
        headerActions.revalidate()
        applyPreferredSize()
        return button
    }

    /** A bare glyph on the header, left of whatever is already there. See [HeaderIcon]. */
    fun addHeaderIcon(kind: IconButton.Kind, tooltip: String, onClick: () -> Unit): IconButton {
        val button = IconButton(kind, onClick).apply { toolTipText = tooltip }
        headerActions.add(button, 0)
        headerActions.revalidate()
        applyPreferredSize()
        return button
    }

    /**
     * A bare stroked glyph on the header — no fill, no border, no button.
     *
     * The distinction from [HeaderButton] is worth keeping deliberate. A button is a *labelled*
     * action whose text is part of the information: LOAD, or the name of the cabinet in use. These
     * are marks, and drawing them in a bordered box would give a `+` the same visual weight as the
     * name of a file, which is not the weight it has.
     *
     * Dim until the pointer is on it, then [hoverColour]. The default is the accent; [ClearButton]
     * overrides it to red, because that one throws something away and the rest do not.
     */
    abstract class HeaderIcon(private val onClick: () -> Unit) : JComponent() {

        private var hover = false

        protected open val hoverColour: Color get() = Theme.ACCENT

        init {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            val size = Dimension(16, 22)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hover = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (isEnabled) onClick()
                }
            })
        }

        /** Colour and stroke are already set; draw about [cx], [cy]. */
        protected abstract fun paintGlyph(g2: Graphics2D, cx: Int, cy: Int)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (hover) hoverColour else Theme.TEXT_DIM
                g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                paintGlyph(g2, width / 2, height / 2)
            } finally {
                g2.dispose()
            }
        }
    }

    /** A small ✕. Red on hover, because what it does is throw something away. */
    class ClearButton(onClick: () -> Unit) : HeaderIcon(onClick) {

        override val hoverColour: Color get() = Theme.BAD

        override fun paintGlyph(g2: Graphics2D, cx: Int, cy: Int) {
            val arm = 4
            g2.drawLine(cx - arm, cy - arm, cx + arm, cy + arm)
            g2.drawLine(cx + arm, cy - arm, cx - arm, cy + arm)
        }
    }

    /**
     * The non-destructive marks: add one of these, and show me the rest of this.
     *
     * [kind] is settable because the expander is one control with two states rather than two
     * controls — the glyph reverses to say that pressing it again undoes what it just did.
     */
    class IconButton(kind: Kind, onClick: () -> Unit) : HeaderIcon(onClick) {

        enum class Kind { ADD, EXPAND, COLLAPSE, EYE, EYE_CLOSED }

        var kind: Kind = kind
            set(value) {
                if (field == value) return
                field = value
                repaint()
            }

        override fun paintGlyph(g2: Graphics2D, cx: Int, cy: Int) {
            when (kind) {
                Kind.ADD -> {
                    val arm = 5
                    g2.drawLine(cx - arm, cy, cx + arm, cy)
                    g2.drawLine(cx, cy - arm, cx, cy + arm)
                }
                // Chevrons pointing apart, then together. Horizontal because that is the direction
                // the pedal actually grows — a module with more knobs is wider, never taller.
                Kind.EXPAND -> chevrons(g2, cx, cy, apart = true)
                Kind.COLLAPSE -> chevrons(g2, cx, cy, apart = false)
                Kind.EYE -> eye(g2, cx, cy, struck = false)
                Kind.EYE_CLOSED -> eye(g2, cx, cy, struck = true)
            }
        }

        /**
         * A lens with a pupil, and a stroke through it when something is being hidden.
         *
         * Struck means *hidden*, matching every other eye control anyone has used. The alternative —
         * showing what pressing it would do rather than what is true now — reads correctly for
         * exactly as long as it takes to press it once.
         */
        private fun eye(g2: Graphics2D, cx: Int, cy: Int, struck: Boolean) {
            val lens = Path2D.Float()
            lens.moveTo(cx - EYE_HALF_WIDTH, cy.toFloat())
            lens.quadTo(cx.toFloat(), cy - EYE_LID, cx + EYE_HALF_WIDTH, cy.toFloat())
            lens.quadTo(cx.toFloat(), cy + EYE_LID, cx - EYE_HALF_WIDTH, cy.toFloat())
            lens.closePath()
            g2.draw(lens)
            g2.fill(Ellipse2D.Float(cx - EYE_PUPIL / 2f, cy - EYE_PUPIL / 2f, EYE_PUPIL, EYE_PUPIL))
            if (struck) {
                val reach = EYE_HALF_WIDTH.toInt()
                g2.drawLine(cx - reach, cy + 6, cx + reach, cy - 6)
            }
        }

        private fun chevrons(g2: Graphics2D, cx: Int, cy: Int, apart: Boolean) {
            val near = 2f
            val far = 6f
            val rise = 4f
            val tip = if (apart) far else near
            val tail = if (apart) near else far

            val left = Path2D.Float()
            left.moveTo(cx - tail, cy - rise)
            left.lineTo(cx - tip, cy.toFloat())
            left.lineTo(cx - tail, cy + rise)
            g2.draw(left)

            val right = Path2D.Float()
            right.moveTo(cx + tail, cy - rise)
            right.lineTo(cx + tip, cy.toFloat())
            right.lineTo(cx + tail, cy + rise)
            g2.draw(right)
        }

        private companion object {
            const val EYE_HALF_WIDTH = 7f

            /**
             * The lid's control point, which is **twice** how far the eye actually opens.
             *
             * A quadratic Bézier reaches only half the offset of its control, so this draws an apex
             * 4 px above centre and a lens 8 px tall. Worth stating where the number lives: reading
             * it as the height is the mistake, and it is an easy one to make twice.
             */
            const val EYE_LID = 8f

            const val EYE_PUPIL = 5f
        }
    }

    /** Three dots. Opens whatever [build] puts in the popup, under the button. */
    class MenuButton(private val build: (JPopupMenu) -> Unit) : JComponent() {

        private var hover = false

        init {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            val size = Dimension(16, 22)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hover = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    repaint()
                }

                override fun mousePressed(e: MouseEvent) {
                    if (!isEnabled) return
                    val popup = DarkMenu.popup()
                    build(popup)
                    popup.show(this@MenuButton, 0, height + 2)
                }
            })
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            cursor = java.awt.Cursor.getPredefinedCursor(
                if (enabled) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR
            )
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = when {
                    !isEnabled -> Theme.DISABLED
                    hover -> Theme.TEXT
                    else -> Theme.TEXT_DIM
                }
                val x = width / 2 - 1
                val middle = height / 2 - 1
                for (dot in -1..1) g2.fillOval(x, middle + dot * 5 - 1, 3, 3)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * A panel that paints its children at an alpha, so a row of controls can be dissolved.
     *
     * The fade is applied in [paintChildren] rather than per knob, which is what keeps it working for
     * whatever a module happens to put in the row — a `Knob` today, something else later — without
     * that component knowing anything about it.
     *
     * Not opaque, so the pedal's own background is repainted underneath before the children go over
     * it at reduced alpha. An opaque row would fade the knobs towards its own fill instead of towards
     * the pedal.
     */
    class FadeRow(layout: java.awt.LayoutManager) : JPanel(layout) {

        /** 1 is normal. Anything less dissolves the whole row towards the pedal behind it. */
        var alpha: Float = 1f
            set(value) {
                field = value.coerceIn(0f, 1f)
            }

        init {
            isOpaque = false
        }

        override fun paintChildren(g: Graphics) {
            if (alpha >= 1f) {
                super.paintChildren(g)
                return
            }
            if (alpha <= 0f) return
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                super.paintChildren(g2)
            } finally {
                g2.dispose()
            }
        }
    }

    /** A compact painted button; the look-and-feel's is silver and the wrong shape. */
    class HeaderButton(label: String, private val onClick: () -> Unit) : JComponent() {

        private var hover = false

        var label: String = label
            set(value) {
                field = value
                resize()
                repaint()
            }

        /**
         * Marks the button as needing attention — the file it names is not there any more.
         *
         * Red rather than blank, and still carrying the name, because those are two different
         * questions: *something is wrong* and *which file do I go and find*. Clearing the label to
         * "LOAD" would answer the first and lose the second.
         */
        var alert: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                repaint()
            }

        init {
            font = Theme.LABEL_FONT
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            resize()
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hover = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    repaint()
                }

                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

        private fun resize() {
            val width = (getFontMetrics(font).stringWidth(label) + 18).coerceAtMost(MAX_WIDTH)
            val size = Dimension(width, 22)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            revalidate()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (hover) Theme.ACCENT_TINT else Theme.KNOB_BODY
                g2.fillRoundRect(0, 0, width, height, 7, 7)
                g2.color = when {
                    alert -> Theme.BAD
                    hover -> Theme.ACCENT_SOFT
                    else -> Theme.TRACK
                }
                g2.drawRoundRect(0, 0, width - 1, height - 1, 7, 7)

                g2.font = font
                g2.color = when {
                    alert -> Theme.BAD
                    hover -> Theme.TEXT
                    else -> Theme.TEXT_DIM
                }
                val metrics = g2.fontMetrics
                // Elides rather than overflowing: a cabinet file name is longer than any button.
                var text = label
                while (metrics.stringWidth(text) > width - 12 && text.length > 1) {
                    text = text.dropLast(1)
                }
                if (text != label) text = text.dropLast(1) + "…"
                g2.drawString(
                    text,
                    (width - metrics.stringWidth(text)) / 2,
                    (height + metrics.ascent - metrics.descent) / 2,
                )
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            const val MAX_WIDTH = 96
        }
    }

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(12, 14, 12, 14)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(title, BorderLayout.WEST)
            add(headerActions, BorderLayout.EAST)
        }
        headerActions.add(toggle)

        add(header, BorderLayout.NORTH)
        add(knobRow, BorderLayout.CENTER)

        toggle.onToggle = { on ->
            effect.enabled = on
            applyEnabled(on)
            onEdited?.invoke()
        }

        // A choice reads as a dropdown on the header rather than as a knob in the row, because it is
        // not a quantity: a knob that lands between two options and shows the nearer one is lying
        // about what it can do. Built once — the set of choices a module offers is fixed even when
        // the knobs behind them are not.
        for (parameter in effect.parameters.filter { it.isChoice }) addHeaderChoice(parameter)

        // Only for a module that hides something. Everything else gets no button, which is the
        // default [AudioProcessor.compactParameters] arranges.
        if (effect.compactParameters < effect.parameters.size) {
            var button: IconButton? = null
            button = addHeaderIcon(IconButton.Kind.EXPAND, "Show every control") {
                expanded = !expanded
                // The glyph flips at once even though the width takes a moment. It is feedback that
                // the click landed, and delaying it to match the animation makes the button feel
                // unresponsive rather than makes the animation feel considered.
                button?.kind = if (expanded) IconButton.Kind.COLLAPSE else IconButton.Kind.EXPAND
                button?.toolTipText = if (expanded) "Show fewer controls" else "Show every control"
                transitionKnobs()
            }
        }

        rebuildKnobs()
        applyEnabled(effect.enabled)

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                onDragStart?.invoke(this@EffectPanel, e)
            }

            override fun mouseDragged(e: MouseEvent) {
                onDragMove?.invoke(this@EffectPanel, e)
            }

            override fun mouseReleased(e: MouseEvent) {
                onDragEnd?.invoke()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        // The pedal's size is set by [rebuildKnobs], which has already run — a module whose control
        // count can change cannot have it fixed once at construction.
    }

    /**
     * A dropdown on the header showing what [parameter] currently is.
     *
     * Built on [HeaderButton] rather than a `JComboBox` on purpose: a combo brings the
     * look-and-feel's own metrics and chrome into a row that is otherwise entirely hand-painted, and
     * at 22 pixels tall it does not fit. This is the same control as LOAD with a caret on it.
     */
    private fun addHeaderChoice(parameter: Parameter) {
        var button: HeaderButton? = null
        button = addHeaderButton(choiceLabel(parameter), "Choose what this module is") {
            val popup = DarkMenu.popup()
            parameter.choices.forEachIndexed { index, option ->
                popup.add(
                    DarkMenu.item(option) {
                        if (index == parameter.index) return@item
                        parameter.index = index
                        button?.label = choiceLabel(parameter)
                        // The knobs behind a choice are the choice's own, so the row is not merely
                        // relabelled — it is a different set of controls and a different width.
                        // Dissolved rather than swapped: see [transitionKnobs].
                        transitionKnobs()
                        onEdited?.invoke()
                    }
                )
            }
            popup.show(button, 0, (button?.height ?: 0) + 2)
        }
        button?.let { headerChoices += it to parameter }
    }

    private fun choiceLabel(parameter: Parameter): String =
        "${parameter.formatted().uppercase()} $CARET"

    /**
     * Rebuilds the knob row from whatever the effect currently offers.
     *
     * Called whenever the answer can have changed — construction, expanding, a slot changing type,
     * a preset load. Knobs are thrown away and remade rather than re-pointed at new parameters,
     * which is cheap at this size and removes a whole class of bug where a knob keeps writing to the
     * parameter it was built for.
     */
    private fun rebuildKnobs() {
        val all = effect.parameters
        val visible = if (expanded || effect.compactParameters >= all.size) {
            all
        } else {
            all.take(effect.compactParameters)
        }
        builtFor = all

        knobRow.removeAll()
        // Choices live on the header; only quantities get a knob.
        knobs = visible.filter { !it.isChoice }.map { Knob(it) }
        knobs.forEach { knob ->
            knob.onChange = { onEdited?.invoke() }
            knobRow.add(knob)
        }
        applyEnabled(effect.enabled)

        applyPreferredSize()
        knobRow.revalidate()
        repaint()
    }

    /**
     * Sizes the pedal to fit whichever of its two rows needs more room.
     *
     * The knob row was once the only candidate, and that was a real bug the moment a module could
     * have a long header: an FX slot on Transient has two knobs — 188 pixels — and a header carrying
     * a title, a ✕, a `+`, "TRANSIENT ▾" and a bypass switch, which does not. The result was a title
     * running underneath the dropdown and a ✕ pushed off the end of the pedal entirely.
     *
     * So it has to be recomputed on anything that can change *either* row: rebuilding the knobs, a
     * header button being added, and the title changing — a slot renumbering from "FX 9" to "FX 10"
     * is wider than it was.
     */
    /**
     * Changes the knob row without the change being abrupt: a dissolve, then a resize.
     *
     * **This is the module system's answer to a changing control set, not EQ's or the FX slot's.**
     * Anything whose [AudioProcessor.parameters] can change under it calls this instead of
     * [rebuildKnobs] and inherits the behaviour — expanding, switching an FX slot's type, and
     * whatever a later module does for its own reasons.
     *
     * Three phases, and the order is the whole design:
     *
     * 1. **Fade the old knobs out** at the current width. Nothing moves yet.
     * 2. **Swap them** while nothing is visible. This is the only frame in which knobs appear,
     *    vanish or jump position, and it happens behind a fully transparent row — which is what
     *    makes a change of *count* and a change of *arrangement* cost the same as a change of one
     *    label.
     * 3. **Resize and fade the new ones in** together. The pedal grows or shrinks while empty, so
     *    nothing is ever clipped mid-flight and the new controls arrive into a pedal that already
     *    fits them.
     *
     * The effect's own state is **not** deferred to phase 2. An FX slot changes type the instant the
     * menu item is clicked, because that is a change to what the audio thread is running and delaying
     * it by even a tenth of a second would put the sound behind the click. Only the picture waits.
     *
     * [animating] suspends [applyPreferredSize] throughout, because [rebuildKnobs] calls it and would
     * otherwise snap to the final width on the frame the knobs are built.
     */
    fun transitionKnobs() {
        widthAnimator?.stop()
        animating = true

        val from = preferredSize.width
        var to = from
        var swapped = false
        val started = System.nanoTime()

        val timer = javax.swing.Timer(FRAME_MILLIS) { }
        timer.addActionListener {
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            if (!swapped) {
                val t = (elapsed / FADE_OUT_MILLIS).coerceIn(0.0, 1.0)
                knobRow.alpha = (1.0 - t).toFloat()
                knobRow.repaint()
                if (t >= 1.0) {
                    swapped = true
                    rebuildKnobs()
                    to = naturalWidth()
                    knobRow.alpha = 0f
                }
                return@addActionListener
            }
            val t = ((elapsed - FADE_OUT_MILLIS) / RESIZE_MILLIS).coerceIn(0.0, 1.0)
            // Width eases; alpha does not. An eased fade-in starts almost flat, which held the pedal
            // visibly empty for an extra beat after the swap — the one moment in the transition where
            // there is nothing to look at, and so the one that should be shortest. Linear brings the
            // controls back promptly while the resize keeps its curve.
            setAnimatedWidth(from + ((to - from) * ease(t)).toInt())
            knobRow.alpha = t.toFloat()
            knobRow.repaint()
            if (t >= 1.0) {
                timer.stop()
                widthAnimator = null
                setAnimatedWidth(to)
                knobRow.alpha = 1f
                animating = false
                applyPreferredSize()
            }
        }
        widthAnimator = timer
        timer.start()
    }

    /**
     * Ease in and out, cubic.
     *
     * A linear resize looks mechanical because nothing physical starts and stops instantly. Cubic is
     * the mildest curve that reads as deliberate; anything sharper draws attention to itself, which
     * is the wrong outcome for a control that may be used repeatedly.
     */
    private fun ease(t: Double): Double =
        if (t < 0.5) 4.0 * t * t * t else 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0

    /** One frame: the width, and the board told to re-wrap around it. */
    private fun setAnimatedWidth(width: Int) {
        val size = Dimension(width, PEDAL_HEIGHT)
        preferredSize = size
        minimumSize = size
        maximumSize = size
        revalidate()
        onResized?.invoke()
    }

    /** The width this pedal wants for the knobs and header it has right now. */
    private fun naturalWidth(): Int {
        val header = title.preferredSize.width + TITLE_GAP + headerActions.preferredSize.width
        return maxOf(knobs.size * KNOB_WIDTH, header, MIN_BODY) + SIDES
    }

    private fun applyPreferredSize() {
        // The animator owns the width while it runs. Without this, `rebuildKnobs` — which every
        // transition goes through — would set the final size on its own and there would be nothing
        // left to animate.
        if (animating) return
        val header = title.preferredSize.width + TITLE_GAP + headerActions.preferredSize.width
        // Fixed in both directions. Height used to be capped at Int.MAX_VALUE, so a taller window
        // stretched the pedals while their contents stayed put — a pedal is a fixed object, and it
        // should sit in the extra space rather than absorb it. All three sizes are set because a
        // preferred size alone does not stop a layout consulting the other two.
        val size = Dimension(maxOf(knobs.size * KNOB_WIDTH, header, MIN_BODY) + SIDES, PEDAL_HEIGHT)
        if (size == preferredSize) return
        preferredSize = size
        minimumSize = size
        maximumSize = size
        revalidate()
        onResized?.invoke()
    }

    /** Re-reads the effect's name — a slot renumbers when the board changes around it. */
    fun refreshTitle() {
        title.text = effect.name.uppercase()
        // Both the name and the ✕ beside it may have just changed width — renumbering is what makes
        // a slot's ✕ appear and disappear.
        applyPreferredSize()
        title.repaint()
    }

    /**
     * Pushes the effect's state back into the controls, after something else changed it — a preset
     * load. The knobs read [org.example.dsp.Parameter.value] as they paint, so they only need the
     * repaint; the switch holds its own state and has to be told.
     */
    fun syncFromEffect() {
        toggle.isSelected = effect.enabled
        refreshTitle()
        // A preset can have changed *which* controls this module has, not merely their values —
        // loading one onto an FX slot changes its type. Compared by identity against what the row
        // was built from, so the common case of a load that changes nothing structural is free.
        if (effect.parameters !== builtFor) {
            headerChoices.forEach { (button, parameter) -> button.label = choiceLabel(parameter) }
            rebuildKnobs()
        }
        applyEnabled(effect.enabled)
        repaint()
    }

    private fun applyEnabled(on: Boolean) {
        knobs.forEach {
            it.isEnabled = on
            it.repaint()
        }
    }

    companion object {
        /** Tracks `Knob`'s preferred height plus the header and borders. A taller knob clips without it. */
        private const val PEDAL_HEIGHT = 156

        /** Tracks `Knob`'s preferred width. */
        private const val KNOB_WIDTH = 80

        /** The panel's left and right border, which neither row may be laid out into. */
        private const val SIDES = 28

        /** Enough that a one-knob pedal still looks like a pedal. */
        private const val MIN_BODY = 162

        /** Clear space between the title and the header's buttons, so they never appear joined. */
        private const val TITLE_GAP = 12

        /**
         * How long the old knobs take to disappear before they are replaced.
         *
         * Deliberately the shorter half. What is being hidden is a single frame of rearrangement, and
         * the eye needs only enough of a fade to stop reading that frame as a jump — dwelling on an
         * emptying pedal would draw attention to the mechanism instead of covering it.
         */
        private const val FADE_OUT_MILLIS = 70.0

        /**
         * How long the pedal then takes to resize with the new knobs fading in.
         *
         * The two together are 200 ms: short enough that a second click never waits on the first,
         * long enough to read as movement rather than as a flicker. Below about 120 ms the eye
         * registers a jump with a smear; much beyond 250 ms a control used repeatedly feels slow.
         */
        private const val RESIZE_MILLIS = 130.0

        /** ~60 fps. The board re-wraps on each frame, which is the expensive part and is still cheap. */
        private const val FRAME_MILLIS = 16

        /** U+25BE. Present in the default sans font on both targets, unlike most of that block. */
        private const val CARET = "▾"

        /** Margin a lifted pedal reserves for its own shadow, on every side. */
        const val LIFT_PAD = 7

        private val SHADOW = java.awt.Color(0, 0, 0, 20)
    }

    /** The whole pedal, children included, at [fade]. Free when nothing is fading. */
    override fun paint(g: Graphics) {
        if (fade >= 1f) {
            super.paint(g)
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fade)
            super.paint(g2)
        } finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val pad = if (dragging) LIFT_PAD else 0
            val bodyWidth = width - pad * 2.0
            val bodyHeight = height - pad * 2.0

            // Stacked translucent rounds instead of a real blur: cheap, and at this size identical.
            if (dragging) {
                for (spread in pad downTo 1) {
                    g2.color = SHADOW
                    g2.fillRoundRect(
                        pad - spread,
                        pad - spread + 2,
                        (bodyWidth + spread * 2).toInt(),
                        (bodyHeight + spread * 2).toInt(),
                        16,
                        16,
                    )
                }
            }

            val shape = RoundRectangle2D.Double(pad.toDouble(), pad.toDouble(), bodyWidth, bodyHeight, 14.0, 14.0)
            g2.color = if (dragging) Theme.PANEL_RAISED else Theme.PANEL
            g2.fill(shape)
            if (dragging) {
                g2.color = Theme.ACCENT
                g2.stroke = BasicStroke(2f)
                g2.draw(
                    RoundRectangle2D.Double(pad + 1.0, pad + 1.0, bodyWidth - 2, bodyHeight - 2, 13.0, 13.0)
                )
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
