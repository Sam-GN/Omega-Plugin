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
package org.example.dsp.tuner

import org.example.dsp.DEFAULT_SAMPLE_RATE
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** The six strings of standard tuning, low to high, at A = 440 Hz. */
enum class GuitarString(val label: String, val hz: Float) {
    E2("E", 82.4069f),
    A2("A", 110f),
    D3("D", 146.8324f),
    G3("G", 195.9977f),
    B3("B", 246.9417f),
    E4("E", 329.6276f);

    /** Cents from this string to [hz]: positive when [hz] is sharp of it, negative when flat. */
    fun cents(hz: Float): Float = (1200.0 * ln(hz.toDouble() / this.hz) / ln(2.0)).toFloat()

    companion object {
        /** The string nearest to [hz]. */
        fun nearest(hz: Float): GuitarString = entries.minBy { abs(it.cents(hz)) }

        /**
         * The string [hz] belongs to, given the one it was last heard as. The nearest, unless
         * [previous] is within [HOLD_CENTS] of being as near: a string tuned well towards its
         * neighbour would otherwise flicker between the two names while the player turns the peg.
         */
        fun choose(hz: Float, previous: GuitarString?): GuitarString {
            val nearest = nearest(hz)
            if (previous == null || previous == nearest) return nearest
            return if (abs(previous.cents(hz)) - abs(nearest.cents(hz)) < HOLD_CENTS) previous else nearest
        }

        const val HOLD_CENTS = 50f
    }
}

/**
 * The fundamental of one note, by normalised autocorrelation.
 *
 * For every lag between the highest and lowest pitch a guitar string can be tuned to, the window is
 * correlated with itself shifted by that lag and the result normalised by the energy of both halves,
 * so a periodic signal reads 1 at its period whatever its level. The period is the **first** lag
 * whose correlation is within [PEAK_SHARE] of the best, because a signal correlates with itself at
 * every multiple of its period too, and the smallest of those is the fundamental; the lag is then
 * refined by fitting a parabola through its two neighbours, which is what gets inside one cent.
 * A window quieter than [GATE_RMS], or one with no lag clearer than [CLARITY_MIN], reads as nothing.
 *
 * Never on the audio thread: a window is a few thousand samples and the search is a few million
 * multiplications, which is nothing to a background thread and everything to a 1.45 ms deadline.
 * Everything rate-dependent is sized in [prepare], as every module here does.
 */
class PitchTracker(sampleRate: Float = DEFAULT_SAMPLE_RATE) {

    var sampleRate: Float = sampleRate
        private set

    private var minLag = 0
    private var maxLag = 0
    private var correlation = FloatArray(0)
    private var centred = FloatArray(0)

    /** How clear the last estimate was, 0..1: the correlation at the period it chose. */
    var lastClarity: Float = 0f
        private set

    /** Samples an estimate needs: [WINDOW_PERIODS] of the lowest pitch tracked. */
    val windowSize: Int get() = maxLag * WINDOW_PERIODS

    init {
        prepare(sampleRate)
    }

    fun prepare(rate: Float) {
        sampleRate = rate
        minLag = (rate / MAX_HZ).toInt().coerceAtLeast(2)
        maxLag = (rate / MIN_HZ).toInt() + 1
        correlation = FloatArray(maxLag + 2)
        centred = FloatArray(windowSize)
    }

    /**
     * The fundamental in the first [count] samples of [samples], in hertz, or 0 when nothing clear
     * is sounding. [count] must be at least [windowSize].
     */
    fun estimate(samples: FloatArray, count: Int): Float {
        lastClarity = 0f
        val n = minOf(count, centred.size)
        if (n < windowSize) return 0f
        var sum = 0.0
        for (i in 0 until n) sum += samples[i]
        val mean = (sum / n).toFloat()
        var energy = 0.0
        val x = centred
        for (i in 0 until n) {
            val v = samples[i] - mean
            x[i] = v
            energy += v * v
        }
        if (sqrt(energy / n) < GATE_RMS) return 0f

        val r = correlation
        var best = 0f
        for (lag in minLag..maxLag) {
            var dot = 0.0
            var e0 = 0.0
            var e1 = 0.0
            val m = n - lag
            for (i in 0 until m) {
                val a = x[i]
                val b = x[i + lag]
                dot += a * b
                e0 += a * a
                e1 += b * b
            }
            val value = if (e0 > 0.0 && e1 > 0.0) (dot / sqrt(e0 * e1)).toFloat() else 0f
            r[lag] = value
            if (value > best) best = value
        }
        if (best < CLARITY_MIN) return 0f

        // The first local maximum that comes within PEAK_SHARE of the best: the fundamental's own
        // peak, not the equally tall one at twice its period.
        val threshold = best * PEAK_SHARE
        var lag = -1
        for (candidate in (minLag + 1) until maxLag) {
            val v = r[candidate]
            if (v >= threshold && v >= r[candidate - 1] && v >= r[candidate + 1]) {
                lag = candidate
                break
            }
        }
        if (lag < 0) return 0f
        lastClarity = r[lag]

        val left = r[lag - 1]
        val centre = r[lag]
        val right = r[lag + 1]
        val denominator = left - 2f * centre + right
        val delta = if (denominator != 0f) (left - right) / (2f * denominator) else 0f
        return sampleRate / (lag + delta.coerceIn(-0.5f, 0.5f))
    }

    companion object {
        /** A low E detuned by more than a tone is not being tuned, it is being restrung. */
        const val MIN_HZ = 70f

        /** Above the high E with a semitone and a half of room; the harmonics are not what is tracked. */
        const val MAX_HZ = 360f

        /** Periods of the lowest pitch in one window: about 71 ms at 48 kHz. */
        const val WINDOW_PERIODS = 5

        /** -50 dBFS. Pickup hum sits well below this; a plucked string well above it. */
        const val GATE_RMS = 0.00316f

        /** A correlation below this at every lag is noise, or two notes at once. */
        const val CLARITY_MIN = 0.8f

        /** How close to the best correlation a shorter lag must come to be taken as the period. */
        const val PEAK_SHARE = 0.9f
    }
}
