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

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * One lamp per pulse of the bar, lit as the pulse sounds.
 *
 * It reads the transport rather than the audio: [update] is called from the same 30 Hz timer that
 * drives the level meter, and it decides a lamp has fired by watching a beat counter change rather
 * than by looking for a tick in the signal. At 300 BPM — the fastest the tempo field allows — a
 * pulse is 200 ms against a 33 ms poll, so no beat can pass between two ticks unseen.
 *
 * The lamp count is the bar's **pulses**, which is why 6/8 shows two and not six: see
 * `TimeSignature`. That makes this the one place in the UI where a compound signature is visibly
 * different from a simple one, and it is the fastest way to tell you have the wrong SIG selected.
 */
class BeatLamps : JComponent() {

    /** Pulses in the bar. */
    var beats: Int = 4
        set(value) {
            val clamped = value.coerceIn(1, MAX_LAMPS)
            if (field == clamped) return
            field = clamped
            java.util.Arrays.fill(brightness, 0f)
            resize()
            repaint()
        }

    private val brightness = FloatArray(MAX_LAMPS)
    private var lastSerial = -1L
    private var lit = -1

    init {
        isOpaque = false
        resize()
    }

    private fun resize() {
        val width = beats * DOT + (beats - 1) * GAP
        preferredSize = Dimension(width, DOT)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    /**
     * Called on the event thread from the meter timer.
     *
     * [serial] is what distinguishes "still on beat 2" from "beat 2 again a bar later" — at one
     * pulse a bar the beat index alone never changes, and a lamp keyed on it would light once and
     * stay lit. Passing -1 for [beat] means the transport is stopped and everything fades out.
     */
    fun update(beat: Int, serial: Long) {
        var changed = false
        if (serial != lastSerial && beat >= 0) {
            lastSerial = serial
            lit = beat
            if (beat < MAX_LAMPS) brightness[beat] = 1f
            changed = true
        }
        if (beat < 0) lit = -1
        for (i in 0 until beats) {
            if (brightness[i] <= 0f) continue
            // ~180 ms to fade, per tick rather than per second so the look does not change if the
            // timer's period ever does.
            brightness[i] = (brightness[i] - FADE_PER_TICK).coerceAtLeast(0f)
            changed = true
        }
        if (changed) repaint()
    }

    /** Clears every lamp at once, for a transport that has stopped. */
    fun clear() {
        java.util.Arrays.fill(brightness, 0f)
        lit = -1
        lastSerial = -1L
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val top = (height - DOT) / 2
        for (i in 0 until beats) {
            val x = i * (DOT + GAP)
            // Beat 1 gets the full accent; the rest a dimmer one, so the top of the bar is visible
            // at a glance and not only audible.
            val target = if (i == 0) Theme.ACCENT else Theme.ACCENT_SOFT
            g2.color = blend(Theme.DISABLED, target, brightness[i])
            g2.fillOval(x, top, DOT, DOT)
            if (i == lit && brightness[i] > 0.5f) {
                g2.color = Color(target.red, target.green, target.blue, (60 * brightness[i]).toInt())
                g2.fillOval(x - 3, top - 3, DOT + 6, DOT + 6)
            }
        }
    }

    private fun blend(from: Color, to: Color, amount: Float): Color {
        val a = amount.coerceIn(0f, 1f)
        return Color(
            (from.red + (to.red - from.red) * a).toInt(),
            (from.green + (to.green - from.green) * a).toInt(),
            (from.blue + (to.blue - from.blue) * a).toInt(),
        )
    }

    private companion object {
        const val DOT = 10
        const val GAP = 6

        /** 12/8 is the widest bar the app offers; the array is sized once for the worst case. */
        const val MAX_LAMPS = 12

        /** About 180 ms at the meter timer's 33 ms period. */
        const val FADE_PER_TICK = 0.18f
    }
}
