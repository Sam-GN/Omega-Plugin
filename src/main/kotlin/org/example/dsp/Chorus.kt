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
package org.example.dsp

import kotlin.math.PI
import kotlin.math.sin

/**
 * Two detuned copies of the signal, swept against the original.
 *
 * A short delay whose length is moving is a pitch shift — the read head chases or outruns the write
 * head — so an LFO on the delay time produces a copy that drifts sharp and flat around the original.
 * Sum them and the beating between them is the chorus.
 *
 * **Two voices, not one.** The second is swept in antiphase from a different base delay, which is
 * most of the difference between "chorus" and "a note that wobbles". One voice gives a single beat
 * frequency that the ear locks onto; two give an interference pattern it does not.
 *
 * Worth knowing why this is on a nylon-string preset: the top end of an undersaddle piezo is brittle
 * in a very *static* way, and two slightly detuned copies smear exactly that. It hides the
 * transducer without an EQ cut that would also take the instrument's own top with it.
 *
 * No feedback path, which is the line between this and a flanger. Feedback turns the smear into the
 * resonant sweep that a flanger is, and both are worth having — as separate types, because the
 * useful settings do not overlap.
 */
class Chorus : AudioProcessor {

    override val name = "Chorus"

    @Volatile
    override var enabled = false

    val rate = Parameter("Rate", 0.05f, 5f, 0.6f, "Hz", skew = 1.5f)
    val depth = Parameter("Depth", 0f, 1f, 0.5f)
    val mix = Parameter("Mix", 0f, 1f, 0.5f)

    override val parameters = listOf(rate, depth, mix)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var line = FloatArray(lineFrames())
    private var writeIndex = 0
    private var phase = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        if (sampleRate == this.sampleRate && line.isNotEmpty()) return
        this.sampleRate = sampleRate
        line = FloatArray(lineFrames())
        writeIndex = 0
    }

    override fun process(buffer: FloatArray, count: Int) {
        val rateHz = rate.value
        val increment = rateHz / sampleRate
        val wet = mix.value
        val dry = 1f - wet
        val framesPerMs = sampleRate / 1000f

        val base1 = VOICE_1_MS * framesPerMs
        val base2 = VOICE_2_MS * framesPerMs
        val sweep = depth.value * SWEEP_MS * framesPerMs

        val line = this.line
        val size = line.size

        var p = phase
        var w = writeIndex

        for (i in 0 until count) {
            val lfo = sin(TWO_PI * p)

            // Antiphase, so the two voices are never at the same distance at the same moment. In
            // phase they would sum into one louder voice with one beat frequency, which is the
            // sound this design exists to avoid.
            val voice1 = read(line, size, w, base1 + sweep * lfo)
            val voice2 = read(line, size, w, base2 - sweep * lfo)

            val x = buffer[i]
            line[w] = x
            w = if (w + 1 >= size) 0 else w + 1

            buffer[i] = x * dry + (voice1 + voice2) * VOICE_GAIN * wet

            p += increment
            if (p >= 1f) p -= 1f
        }

        phase = p
        writeIndex = w
    }

    override fun reset() {
        line.fill(0f)
        writeIndex = 0
        phase = 0f
    }

    /**
     * Reads [distance] frames behind the write head, interpolating between samples.
     *
     * The interpolation is what makes the sweep a pitch shift rather than a stutter: a read head
     * that could only land on whole samples would jump between them, and the jumps are audible as
     * clicking at exactly the rate the LFO is running.
     */
    private fun read(line: FloatArray, size: Int, write: Int, distance: Float): Float {
        var position = write - distance.coerceIn(1f, size - 2f)
        if (position < 0f) position += size
        val i0 = position.toInt()
        val i1 = if (i0 + 1 >= size) 0 else i0 + 1
        val fraction = position - i0
        return line[i0] + (line[i1] - line[i0]) * fraction
    }

    private fun lineFrames(): Int = (sampleRate * LINE_MS / 1000f).toInt().coerceAtLeast(4)

    private companion object {
        const val TWO_PI = (2.0 * PI).toFloat()

        /** Long enough for the longest voice at full sweep, with room for the read head. */
        const val LINE_MS = 60f

        // Both inside the range where a delay reads as thickening rather than as a distinct repeat.
        const val VOICE_1_MS = 11f
        const val VOICE_2_MS = 19f

        /** How far the LFO moves each voice at full depth. */
        const val SWEEP_MS = 6f

        /** Two summed voices at 0.5 each, so full wet is the same level as full dry. */
        const val VOICE_GAIN = 0.5f
    }
}
