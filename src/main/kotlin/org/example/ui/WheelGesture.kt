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

import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

/**
 * Who a run of wheel notches belongs to.
 *
 * Every notch is delivered to whatever is under the pointer at that instant. While a board scrolls
 * under a still mouse, a knob arrives beneath it and the next notch turns the knob instead: the
 * scroll stops dead and a setting has moved that nobody meant to touch. So notches that arrive
 * close together are one **gesture**, the first notch picks its owner by what was under the pointer
 * then, and every notch of the gesture goes to that owner however the content has moved since.
 *
 * The gesture ends when the notches stop for [GAP_MILLIS], or the moment the pointer itself moves:
 * a hand that moves the mouse from one knob to the next has a new intention, and only the physical
 * motion says so — Swing's enter and exit events fire for content sliding under a still pointer
 * too, which is exactly the case this exists to see through.
 *
 * Event thread only.
 */
object WheelGesture {

    private var owner: Any? = null
    private var lastNanos = 0L

    init {
        // Any real pointer motion ends the gesture. Scrolling content under a still mouse produces
        // no MOUSE_MOVED, which is what makes this the right signal.
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { event ->
                if (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED) owner = null
            },
            AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
    }

    /**
     * Asks whether [who] may take this notch. Yes if it owns the live gesture or there is none, in
     * which case it becomes the owner; no if the gesture belongs to something else.
     */
    fun take(who: Any): Boolean {
        val now = System.nanoTime()
        val live = owner != null && now - lastNanos < GAP_NANOS
        if (live && owner !== who) return false
        owner = who
        lastNanos = now
        return true
    }

    /**
     * Hands a notch that [from] may not take on to the nearest ancestor that scrolls, which is
     * where it would have gone had [from] not been under the pointer.
     */
    fun passUp(from: Component, e: MouseWheelEvent) {
        val parent = from.parent ?: return
        parent.dispatchEvent(SwingUtilities.convertMouseEvent(from, e, parent))
    }

    /**
     * Longer than any pause within one unhurried scroll. It can afford to be generous, because a
     * change of mind — scrolling, then turning the knob that stopped under the pointer — always
     * involves moving the mouse onto that knob, and motion ends the gesture at once.
     */
    const val GAP_MILLIS = 700L
    private const val GAP_NANOS = GAP_MILLIS * 1_000_000L
}
