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

import org.example.dsp.Parameter
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotary control bound to a [Parameter].
 *
 * Vertical drag rather than circular tracking: dragging in a circle around a 46 px knob is fiddly,
 * whereas up/down is what every audio plugin uses. Shift drags at a sixth of the speed for fine
 * adjustment, the wheel nudges, and double-click restores the default.
 */
class Knob(val parameter: Parameter, private val compact: Boolean = false) : JComponent() {

    /** Notified after any user-driven change, so the panel can repaint dependent readouts. */
    var onChange: (() -> Unit)? = null

    /**
     * Invoked on a press-and-release that never became a drag.
     *
     * Setting it replaces double-click-to-reset rather than joining it: a control cannot sensibly do
     * both, because every double click begins with a single one.
     */
    var onClick: (() -> Unit)? = null

    /**
     * Paints the control as muted **without** disabling it — [isEnabled] would also swallow the
     * click that unmutes, which is the one interaction that has to keep working.
     */
    var muted: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    private var dragAnchorY = 0
    private var dragAnchorPosition = 0f
    private var dragged = false

    /** The in-place value editor, while one is open. */
    private var editor: JTextField? = null

    /** A press that opened the editor must not also drag the knob on its way back up. */
    private var suppressDrag = false

    /** Briefly true after input was refused, so the readout can say so. */
    private var rejected = false

    init {
        preferredSize = if (compact) Dimension(46, 46) else Dimension(78, 100)
        minimumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
        // Children are positioned by hand: there is exactly one, it is transient, and it goes
        // precisely over the readout it replaces.
        layout = null
        toolTipText = if (compact) {
            "${parameter.name} — drag to adjust, shift for fine, double-click to reset"
        } else {
            "${parameter.name} — drag to adjust, shift for fine, double-click to reset, " +
                "or click the value to type it"
        }

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                if (overValue(e.point)) {
                    // The readout is its own control. Dragging from here would move the knob under
                    // the pointer that was aiming at a number.
                    suppressDrag = true
                    beginEdit()
                    return
                }
                if (onClick == null && e.clickCount == 2) {
                    parameter.reset()
                    changed()
                }
                dragAnchorY = e.y
                dragAnchorPosition = parameter.position
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!isEnabled || suppressDrag) return
                // Swallow the pixel or two of jitter in an ordinary click, so a click stays a click
                // and does not nudge the value on its way past.
                if (!dragged && abs(e.y - dragAnchorY) < DRAG_THRESHOLD) return
                dragged = true
                val travel = if (e.isShiftDown) FINE_TRAVEL else COARSE_TRAVEL
                parameter.position = dragAnchorPosition + (dragAnchorY - e.y) / travel
                changed()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (suppressDrag) {
                    suppressDrag = false
                    return
                }
                if (isEnabled && !dragged) onClick?.invoke()
            }

            override fun mouseMoved(e: MouseEvent) {
                cursor = Cursor.getPredefinedCursor(
                    if (overValue(e.point)) Cursor.TEXT_CURSOR else Cursor.N_RESIZE_CURSOR
                )
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener { e ->
            // A notch that belongs to a scroll already under way is not for this knob, however it
            // came to be under the pointer; it goes on up to whatever is scrolling. See WheelGesture.
            if (!WheelGesture.take(this)) {
                WheelGesture.passUp(this, e)
                return@addMouseWheelListener
            }
            if (isEnabled) {
                parameter.position -= e.wheelRotation * WHEEL_STEP
                changed()
            }
        }
    }

    private fun changed() {
        repaint()
        onChange?.invoke()
    }

    /** Where the readout is drawn, and so where a click means "let me type this". */
    private fun valueBounds(): Rectangle {
        val metrics = getFontMetrics(Theme.VALUE_FONT)
        val text = parameter.formatted()
        val boxWidth = maxOf(metrics.stringWidth(text) + 14, MIN_EDIT_WIDTH).coerceAtMost(width)
        val baseline = height - 6
        val top = baseline - metrics.ascent - 2
        return Rectangle((width - boxWidth) / 2, top, boxWidth, metrics.ascent + metrics.descent + 4)
    }

    private fun overValue(point: java.awt.Point): Boolean =
        !compact && isEnabled && editor == null && valueBounds().contains(point)

    private fun knobDiameter(): Double = if (compact) 32.0 else 46.0

    private fun knobCentreX(): Double = width / 2.0

    private fun knobCentreY(): Double = if (compact) height / 2.0 else KNOB_TOP + knobDiameter() / 2.0

    /**
     * The knob is the circle, not the box around it.
     *
     * The component is a rectangle wide enough for its name and its readout, and for a long time
     * the whole of it answered to the mouse: a wheel notch or a drag that began beside the knob, in
     * what looks like pedal, turned it. Hit-testing is cut down to the dial itself with a little
     * margin, plus the readout where a click means "let me type this". Everything else passes
     * through to the pedal behind, where a press picks the pedal up to move it — which is what the
     * picture says it should do.
     */
    override fun contains(x: Int, y: Int): Boolean {
        val dx = x - knobCentreX()
        val dy = y - knobCentreY()
        val reach = knobDiameter() / 2.0 + HIT_MARGIN
        if (dx * dx + dy * dy <= reach * reach) return true
        return !compact && valueBounds().contains(x, y)
    }

    /** The value with no unit, so typing over the selection replaces exactly what is wanted. */
    private fun editableText(): String {
        val text = parameter.formatted()
        return if (parameter.unit.isEmpty()) text else text.removeSuffix(parameter.unit).trim()
    }

    private fun beginEdit() {
        if (compact || !isEnabled || editor != null) return

        val field = JTextField(editableText()).apply {
            font = Theme.VALUE_FONT
            horizontalAlignment = JTextField.CENTER
            background = Theme.KNOB_BODY
            foreground = Theme.TEXT
            caretColor = Theme.ACCENT
            border = BorderFactory.createLineBorder(Theme.ACCENT_SOFT, 1)
            bounds = valueBounds()
            // Enter accepts.
            addActionListener { finishEdit(commit = true) }
            // Escape abandons, which has to be distinguishable from typing something unusable.
            registerKeyboardAction(
                { finishEdit(commit = false) },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_FOCUSED,
            )
            addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = finishEdit(commit = true)
            })
        }

        editor = field
        add(field)
        repaint()
        field.requestFocusInWindow()
        field.selectAll()
    }

    /**
     * Closes the editor, applying what was typed if it is usable.
     *
     * [editor] is cleared *first*: removing a focused field fires `focusLost`, which lands back
     * here, and without the guard the value would be applied twice — or, worse, applied and then
     * re-read from a field that no longer exists.
     */
    private fun finishEdit(commit: Boolean) {
        val field = editor ?: return
        editor = null

        val accepted = !commit || apply(field.text)
        remove(field)
        revalidate()
        if (!accepted) flashRejected()
        repaint()
    }

    /**
     * Reads a typed value, and refuses anything it cannot honour.
     *
     * Out of range is refused rather than clamped. Clamping looks helpful and is not: typing 5000
     * into a knob that stops at 1000 would silently leave 1000, which is indistinguishable from the
     * value having been accepted — and the one thing worse than a rejected edit is one you think
     * worked.
     */
    private fun apply(typed: String): Boolean {
        // Tolerant of what people actually type: the unit back again, and a decimal comma.
        val cleaned = typed.trim()
            .removeSuffix(parameter.unit)
            .trim()
            .replace(',', '.')
        val value = cleaned.toFloatOrNull() ?: return false
        if (!value.isFinite()) return false
        if (value < parameter.min || value > parameter.max) return false
        parameter.value = value
        changed()
        return true
    }

    private fun flashRejected() {
        rejected = true
        repaint()
        Timer(REJECT_FLASH_MS) {
            rejected = false
            repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // A knob switched off under an open editor must not leave one floating over a dead control.
        if (!enabled) finishEdit(commit = false)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val live = isEnabled
            val diameter = knobDiameter()
            val cx = knobCentreX()
            val cy = knobCentreY()
            val position = parameter.position

            // The muted colour has to beat the disabled one: a muted knob is still live.
            val accent = when {
                muted -> Theme.BAD
                live -> Theme.ACCENT
                else -> Theme.DISABLED
            }

            if (!compact) {
                g2.font = Theme.LABEL_FONT
                g2.color = if (live) Theme.TEXT_DIM else Theme.DISABLED
                drawCentered(g2, parameter.name.uppercase(), width / 2, LABEL_BASELINE)
            }

            val arc = Arc2D.Double(cx - diameter / 2, cy - diameter / 2, diameter, diameter, 0.0, 0.0, Arc2D.OPEN)
            g2.stroke = BasicStroke(if (compact) 4f else 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            // Track: 270 degrees of travel starting at 7:30 and ending at 4:30.
            arc.setAngleStart(START_ANGLE)
            arc.angleExtent = -SWEEP
            g2.color = Theme.TRACK
            g2.draw(arc)

            if (position > 0.001f) {
                arc.angleExtent = -SWEEP * position
                g2.color = accent
                g2.draw(arc)
            }

            val body = diameter - if (compact) 10 else 13
            g2.color = Theme.KNOB_BODY
            g2.fill(Ellipse2D.Double(cx - body / 2, cy - body / 2, body, body))

            val angle = Math.toRadians(START_ANGLE - SWEEP * position)
            val inner = if (compact) 2.5 else 4.0
            val outer = diameter / 2 - if (compact) 6 else 9
            g2.color = if (muted) Theme.BAD else if (live) Theme.TEXT else Theme.DISABLED
            g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(
                Line2D.Double(
                    cx + cos(angle) * inner, cy - sin(angle) * inner,
                    cx + cos(angle) * outer, cy - sin(angle) * outer,
                )
            )

            // Not while the editor is over it, and red for a moment after input was refused — a
            // rejected edit that simply put the old number back is indistinguishable from a click
            // that did nothing at all.
            if (!compact && editor == null) {
                g2.font = Theme.VALUE_FONT
                g2.color = when {
                    rejected -> Theme.BAD
                    live -> Theme.TEXT
                    else -> Theme.DISABLED
                }
                drawCentered(g2, parameter.formatted(), width / 2, height - 6)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawCentered(g2: Graphics2D, text: String, centerX: Int, baselineY: Int) {
        val w = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, centerX - w / 2, baselineY)
    }

    private companion object {
        const val START_ANGLE = 225.0
        const val SWEEP = 270.0
        const val COARSE_TRAVEL = 160f
        const val FINE_TRAVEL = 900f
        const val WHEEL_STEP = 0.02f

        /** Baseline of the parameter name, above the knob. */
        const val LABEL_BASELINE = 11

        /**
         * Top of the knob's bounding box.
         *
         * The gap to [LABEL_BASELINE] is what keeps the name off the arc, and it needs to be larger
         * than it looks: the arc's 5 px stroke straddles this line, so the knob reads 2.5 px higher
         * than it measures. At the original 14.0 the two were touching.
         */
        const val KNOB_TOP = 20.0

        /** Pixels of vertical movement before a press counts as a drag rather than a click. */
        const val DRAG_THRESHOLD = 3

        /** Beyond the dial's edge that still counts as the dial: the arc's stroke, and a little grace. */
        const val HIT_MARGIN = 4.0

        /** Narrow readouts still need a field wide enough to type into. */
        const val MIN_EDIT_WIDTH = 46

        const val REJECT_FLASH_MS = 700
    }
}
