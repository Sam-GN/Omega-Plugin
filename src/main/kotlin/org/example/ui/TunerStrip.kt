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

import org.example.dsp.tuner.Tuner
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.abs

/**
 * The tuner on the rhythm row: a `>` lamp, the button, a `<` lamp, both pointing at the button.
 *
 * Off, the button reads TUNE. On, it reads the string being heard — `E`, `A`, `D`, `G`, `B`, `E` —
 * and the lamps point the string towards it: the left `>` lit means the string is flat and must
 * come up to the note, the right `<` lit means it is sharp and must come down to it (the other way
 * round until 2.1.3, which read as the note being pushed away). The lamp is always the accent, full beyond
 * [FAR_CENTS] and dimmer the nearer the string gets, because green means one thing here: when the
 * string has sat within [IN_TUNE_CENTS] for [IN_TUNE_RUN] readings in a row, the lamps go out and
 * the letter itself turns green. Half a second
 * of nothing heard and the button reads TUNE again, so a muted string never leaves a stale verdict.
 *
 * The analysis runs on a thread of its own at about 20 Hz, because it is a few million
 * multiplications that have no business on the event thread either. [tick] on the event thread
 * paints whatever that thread last found.
 */
class TunerStrip(private val tuner: Tuner, onToggle: () -> Unit) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {

    val button = PushButton(IDLE_TEXT, minimumWidth = 60) { onToggle() }

    /** On the left, pointing at the button: lit when the string is flat and must come up to it. */
    private val flat = Chevron(pointsLeft = false)

    /** On the right, pointing at the button: lit when the string is sharp and must come down to it. */
    private val sharp = Chevron(pointsLeft = true)

    @Volatile
    var on: Boolean = false
        private set

    @Volatile
    private var latest: Tuner.Reading? = null

    @Volatile
    private var heardAt = 0L

    private var worker: Thread? = null
    private var inTuneRun = 0

    init {
        isOpaque = false
        add(flat)
        add(button)
        add(sharp)
        setAvailable(false)
    }

    /** The button follows the stream, as PLAY does: with no input there is nothing to listen to. */
    fun setAvailable(streaming: Boolean) {
        button.isEnabled = streaming
        button.toolTipText =
            if (streaming) "Tune to standard tuning, A at 440 Hz. Pauses the click and the loops while on. Esc turns it off."
            else "Start the stream first — the tuner listens to the input."
    }

    fun setOn(on: Boolean) {
        if (this.on == on) return
        this.on = on
        inTuneRun = 0
        latest = null
        heardAt = 0L
        showIdle()
        if (on) {
            worker = Thread({ listen() }, "omega-tuner").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY + 1
                start()
            }
        } else {
            worker?.interrupt()
            worker = null
        }
    }

    private fun listen() {
        var reported = false
        try {
            while (on) {
                // A reading that fails is one missed reading, not the end of the tuner: before
                // 2.1.4 anything thrown here ended this thread, and the strip showed TUNE for good.
                try {
                    val reading = tuner.read()
                    if (reading != null) {
                        latest = reading
                        heardAt = System.nanoTime()
                    }
                } catch (e: Exception) {
                    if (!reported) {
                        reported = true
                        org.example.util.Log.error("tuner", "A reading failed; the tuner carries on.", e)
                    }
                }
                Thread.sleep(PERIOD_MILLIS)
            }
        } catch (_: InterruptedException) {
            // Switched off.
        }
    }

    /** From the window's meter timer, on the event thread. */
    fun tick() {
        if (!on) return
        val reading = latest
        if (reading == null || System.nanoTime() - heardAt > HOLD_NANOS) {
            showIdle()
            return
        }
        val cents = reading.cents
        val distance = abs(cents)
        val inTune = distance <= IN_TUNE_CENTS
        inTuneRun = if (inTune) inTuneRun + 1 else 0
        button.text = reading.string.label
        button.ink = if (inTuneRun >= IN_TUNE_RUN) Theme.GOOD else null
        if (inTune) {
            flat.set(0f, null)
            sharp.set(0f, null)
            return
        }
        // One colour for "turn the peg", dimming as the string gets close; green is kept for done.
        val brightness = (distance / FAR_CENTS).coerceIn(0.3f, 1f)
        if (cents < 0f) {
            flat.set(brightness, Theme.ACCENT)
            sharp.set(0f, null)
        } else {
            sharp.set(brightness, Theme.ACCENT)
            flat.set(0f, null)
        }
    }

    private fun showIdle() {
        inTuneRun = 0
        button.text = IDLE_TEXT
        // On and hearing nothing reads TUNE in the accent, so on is visibly not off.
        button.ink = if (on) Theme.ACCENT else null
        flat.set(0f, null)
        sharp.set(0f, null)
    }

    /**
     * One lamp, drawn as the chevron it means rather than a dot with a glyph beside it. Unlit it is
     * the grey of an unlit beat lamp; lit it takes its colour at its brightness, with the same halo.
     */
    private class Chevron(private val pointsLeft: Boolean) : JComponent() {

        private var brightness = 0f
        private var colour: Color? = null

        init {
            isOpaque = false
            val size = Dimension(WIDTH, PushButton.HEIGHT)
            preferredSize = size
            minimumSize = size
            maximumSize = size
        }

        fun set(brightness: Float, colour: Color?) {
            val b = brightness.coerceIn(0f, 1f)
            if (b == this.brightness && colour == this.colour) return
            this.brightness = b
            this.colour = colour
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val midY = height / 2f
                val half = 6f
                val tip = if (pointsLeft) 3f else WIDTH - 3f
                val tail = if (pointsLeft) WIDTH - 3f else 3f
                val path = Path2D.Float().apply {
                    moveTo(tail, midY - half)
                    lineTo(tip, midY)
                    lineTo(tail, midY + half)
                }
                val lit = colour
                if (lit != null && brightness > 0.5f) {
                    g2.color = Color(lit.red, lit.green, lit.blue, (70 * brightness).toInt())
                    g2.stroke = BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(path)
                }
                g2.color = if (lit == null) Theme.DISABLED else blend(Theme.DISABLED, lit, brightness)
                g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(path)
            } finally {
                g2.dispose()
            }
        }

        private fun blend(from: Color, to: Color, amount: Float): Color = Color(
            (from.red + (to.red - from.red) * amount).toInt(),
            (from.green + (to.green - from.green) * amount).toInt(),
            (from.blue + (to.blue - from.blue) * amount).toInt(),
        )

        private companion object {
            const val WIDTH = 14
        }
    }

    companion object {
        const val IDLE_TEXT = "Tune"

        /** Inside this the letter goes green, once it has held for [IN_TUNE_RUN] readings. */
        const val IN_TUNE_CENTS = 3f

        /** Beyond this the lamp is at full brightness: a long way to go yet. */
        const val FAR_CENTS = 50f

        /** Readings in a row inside [IN_TUNE_CENTS] before the letter is called in tune: 150 ms. */
        const val IN_TUNE_RUN = 3

        /** How often the thread reads the pitch. */
        const val PERIOD_MILLIS = 50L

        /** How long after the last clear note the display keeps showing it. */
        const val HOLD_NANOS = 500_000_000L
    }
}
