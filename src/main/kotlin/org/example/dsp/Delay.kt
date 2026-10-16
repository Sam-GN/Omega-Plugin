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

/**
 * Feedback delay with a fractional read head.
 *
 * The read distance is smoothed towards the knob value rather than jumping, so sweeping the time
 * knob pitch-bends the repeats like a tape echo instead of clicking. Reading between samples via
 * linear interpolation is what makes that smooth motion possible.
 *
 * The line is sized from the device rate in [prepare]. Sizing it at a fixed 44.1 kHz would quietly
 * halve the usable range of the time knob on a 96 kHz device.
 */
class Delay : AudioProcessor {

    override val name = "Delay"

    @Volatile
    override var enabled = false

    val time = Parameter("Time", 20f, 1000f, 350f, "ms", skew = 1.5f)
    val feedback = Parameter("Feedback", 0f, 0.95f, 0.35f)
    val mix = Parameter("Mix", 0f, 1f, 0.3f)

    override val parameters = listOf(time, feedback, mix)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var line = FloatArray(lineFrames())
    private var writeIndex = 0
    private var readDistance = targetDistance()

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        if (sampleRate == this.sampleRate) return
        this.sampleRate = sampleRate
        line = FloatArray(lineFrames())
        writeIndex = 0
        readDistance = targetDistance()
    }

    override fun process(buffer: FloatArray, count: Int) {
        val target = targetDistance()
        val fb = feedback.value
        val wet = mix.value
        val dry = 1f - wet
        val line = this.line
        val size = line.size

        var distance = readDistance
        var w = writeIndex

        for (i in 0 until count) {
            distance += (target - distance) * TIME_SMOOTHING

            var read = w - distance
            if (read < 0f) read += size
            val i0 = read.toInt()
            val i1 = if (i0 + 1 >= size) 0 else i0 + 1
            val frac = read - i0
            val delayed = line[i0] + (line[i1] - line[i0]) * frac

            val x = buffer[i]
            line[w] = x + delayed * fb
            w = if (w + 1 >= size) 0 else w + 1

            buffer[i] = x * dry + delayed * wet
        }

        readDistance = distance
        writeIndex = w
    }

    override fun reset() {
        line.fill(0f)
        writeIndex = 0
        readDistance = targetDistance()
    }

    /** The longest delay the knob offers, plus headroom for the interpolating read head. */
    private fun lineFrames(): Int = (sampleRate * time.max / 1000f * 1.1f).toInt()

    private fun targetDistance(): Float =
        (time.value * sampleRate / 1000f).coerceIn(1f, line.size - 2f)

    private companion object {
        const val TIME_SMOOTHING = 0.0005f
    }
}
