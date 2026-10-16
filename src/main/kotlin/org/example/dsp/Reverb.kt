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
 * Mono Freeverb (Jezar's Schroeder-Moorer design).
 *
 * Eight parallel damped comb filters build the dense tail; four series allpass filters afterwards
 * smear the remaining echoes so individual repeats stop being audible. The magic numbers are the
 * published tunings — they are mutually prime at 44.1 kHz, which is what stops the combs from
 * reinforcing each other into a ringing, metallic tone. [prepare] rescales them for the device rate.
 *
 * The filter banks are [Array]s rather than [List]s deliberately: `for (x in list)` allocates an
 * iterator, and these are walked once per *sample*. As lists that was ~96 000 short-lived objects a
 * second, which is exactly how a young-generation collection turns into an audible dropout.
 */
class Reverb : AudioProcessor {

    override val name = "Reverb"

    @Volatile
    override var enabled = false

    val roomSize = Parameter("Room", 0f, 1f, 0.6f)
    val damping = Parameter("Damping", 0f, 1f, 0.5f)
    val mix = Parameter("Mix", 0f, 1f, 0.25f)

    override val parameters = listOf(roomSize, damping, mix)

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var combs = buildCombs()
    private var allpasses = buildAllpasses()

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        if (sampleRate == this.sampleRate) return
        this.sampleRate = sampleRate
        combs = buildCombs()
        allpasses = buildAllpasses()
    }

    override fun process(buffer: FloatArray, count: Int) {
        val room = roomSize.value * ROOM_SCALE + ROOM_OFFSET
        val damp = damping.value * DAMP_SCALE

        // Hoisted so the per-sample loop reads locals, never fields.
        val c = combs
        val a = allpasses

        for (comb in c) {
            comb.feedback = room
            comb.damp1 = damp
            comb.damp2 = 1f - damp
        }

        val wet = mix.value * WET_SCALE
        val dry = 1f - mix.value

        for (i in 0 until count) {
            val x = buffer[i]
            val input = x * FIXED_GAIN

            var out = 0f
            for (comb in c) out += comb.process(input)
            for (allpass in a) out = allpass.process(out)

            buffer[i] = x * dry + out * wet
        }
    }

    override fun reset() {
        combs.forEach { it.mute() }
        allpasses.forEach { it.mute() }
    }

    private fun buildCombs() = Array(COMB_TUNING.size) { Comb(scale(COMB_TUNING[it])) }

    private fun buildAllpasses() = Array(ALLPASS_TUNING.size) { Allpass(scale(ALLPASS_TUNING[it])) }

    private fun scale(tuningAt44k: Int): Int =
        (tuningAt44k * sampleRate / 44_100f).toInt().coerceAtLeast(1)

    /** Lowpass-damped feedback comb: the damping filter is what makes highs decay faster than lows. */
    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f

        var feedback = 0f
        var damp1 = 0f
        var damp2 = 1f

        fun process(input: Float): Float {
            val out = buffer[index]
            store = out * damp2 + store * damp1
            buffer[index] = input + store * feedback
            if (++index >= buffer.size) index = 0
            return out
        }

        fun mute() {
            buffer.fill(0f)
            store = 0f
        }
    }

    /** Allpass: passes all frequencies at equal level but disperses them in time. */
    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private val feedback = 0.5f

        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * feedback
            if (++index >= buffer.size) index = 0
            return output
        }

        fun mute() = buffer.fill(0f)
    }

    private companion object {
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val ALLPASS_TUNING = intArrayOf(556, 441, 341, 225)

        const val FIXED_GAIN = 0.015f
        const val WET_SCALE = 3f
        const val ROOM_SCALE = 0.28f
        const val ROOM_OFFSET = 0.7f
        const val DAMP_SCALE = 0.4f
    }
}
