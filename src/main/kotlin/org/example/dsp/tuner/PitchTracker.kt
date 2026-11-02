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
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The six strings of standard tuning, low to high, at A = 440 Hz.
 *
 * Named by pitch class, not by octave: a bass's E is an octave under the guitar's and a five-string
 * bass's B three octaves under the guitar's, and [cents] folds the note heard into the string's own
 * octave before measuring. So one table names a guitar, a seven-string, a four-string bass and a
 * five-string bass, and nobody has to say which they are holding.
 */
enum class GuitarString(val label: String, val hz: Float) {
    E2("E", 82.4069f),
    A2("A", 110f),
    D3("D", 146.8324f),
    G3("G", 195.9977f),
    B3("B", 246.9417f),
    E4("E", 329.6276f);

    /**
     * Cents from this string to [hz], folded into one octave: positive when sharp, negative when
     * flat, never beyond ±600. A note any number of octaves away reads as the same string.
     */
    fun cents(hz: Float): Float {
        var c = 1200.0 * ln(hz.toDouble() / this.hz) / ln(2.0)
        c -= 1200.0 * Math.round(c / 1200.0)
        return c.toFloat()
    }

    companion object {
        /** The string nearest to [hz] by pitch class; between the two Es, the one in the nearer octave. */
        fun nearest(hz: Float): GuitarString =
            entries.minWith(compareBy({ abs(it.cents(hz)) }, { abs(ln(hz.toDouble() / it.hz)) }))

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
 * For every lag between the highest and lowest pitch tracked, the window is correlated with itself
 * shifted by that lag and the result normalised by the energy of both halves, so a periodic signal
 * reads 1 at its period whatever its level. The period is the **first** lag whose correlation is
 * within [PEAK_SHARE] of the best, because a signal correlates with itself at every multiple of its
 * period too, and the smallest of those is the fundamental. A window quieter than [GATE_RMS], or one
 * with no lag clearer than [CLARITY_MIN], reads as nothing.
 *
 * Two rates. The search runs on the input reduced to about [ANALYSIS_HZ], because the lowest note,
 * a five-string bass's B at 31 Hz, needs a window of [WINDOW_PERIODS] of its period and a search
 * over every lag up to it, and at 96 kHz that is tens of millions of multiplications a reading. Then
 * the lag found is refined at the **full** rate: the correlation is recomputed for the few full-rate
 * lags around it and a parabola fitted through the best and its neighbours, which is what gets the
 * high E inside a cent. The reduction is a plain average of each group of samples; a tuner wants
 * nothing above a few hundred hertz, and the harmonics the average blurs are the ones that mislead.
 *
 * Never on the audio thread: a reading is still a few million multiplications, nothing to a
 * background thread and everything to a 1.45 ms deadline. Everything rate-dependent is sized in
 * [prepare], as every module here does.
 */
class PitchTracker(sampleRate: Float = DEFAULT_SAMPLE_RATE) {

    var sampleRate: Float = sampleRate
        private set

    /** Input samples averaged into one analysis sample. */
    var factor: Int = 1
        private set

    private var minLag = 0
    private var maxLag = 0
    private var correlation = FloatArray(0)
    private var reduced = FloatArray(0)
    private var full = FloatArray(0)

    /** How clear the last estimate was, 0..1: the correlation at the period it chose. */
    var lastClarity: Float = 0f
        private set

    /** Input samples an estimate needs: [WINDOW_PERIODS] of the lowest pitch tracked. */
    val windowSize: Int get() = maxLag * WINDOW_PERIODS * factor

    init {
        prepare(sampleRate)
    }

    fun prepare(rate: Float) {
        sampleRate = rate
        factor = ceil(rate / ANALYSIS_HZ).toInt().coerceAtLeast(1)
        val analysisRate = rate / factor
        minLag = (analysisRate / MAX_HZ).toInt().coerceAtLeast(2)
        maxLag = (analysisRate / MIN_HZ).toInt() + 1
        correlation = FloatArray(maxLag + 2)
        reduced = FloatArray(maxLag * WINDOW_PERIODS)
        full = FloatArray(windowSize)
    }

    /**
     * The fundamental in the first [count] samples of [samples], in hertz, or 0 when nothing clear
     * is sounding. [count] must be at least [windowSize].
     */
    fun estimate(samples: FloatArray, count: Int): Float {
        lastClarity = 0f
        val n = minOf(count, full.size)
        if (n < windowSize) return 0f

        // Mean removed at the full rate: an interface's DC offset must not read as a very low note.
        var sum = 0.0
        for (i in 0 until n) sum += samples[i]
        val mean = (sum / n).toFloat()
        val x = full
        var energy = 0.0
        for (i in 0 until n) {
            val v = samples[i] - mean
            x[i] = v
            energy += v * v
        }
        if (sqrt(energy / n) < GATE_RMS) return 0f

        // Reduced to the analysis rate, each sample the average of `factor` full-rate ones.
        val y = reduced
        val m = n / factor
        val f = factor
        for (i in 0 until m) {
            var acc = 0f
            val base = i * f
            for (k in 0 until f) acc += x[base + k]
            y[i] = acc / f
        }

        val r = correlation
        var best = 0f
        for (lag in minLag..maxLag) {
            r[lag] = correlate(y, m, lag)
            if (r[lag] > best) best = r[lag]
        }
        if (best < CLARITY_MIN) return 0f

        // The first local maximum that comes within PEAK_SHARE of the best: the fundamental's own
        // peak, not the equally tall one at twice its period.
        val threshold = best * PEAK_SHARE
        var coarse = -1
        for (candidate in (minLag + 1) until maxLag) {
            val v = r[candidate]
            if (v >= threshold && v >= r[candidate - 1] && v >= r[candidate + 1]) {
                coarse = candidate
                break
            }
        }
        if (coarse < 0) return 0f
        lastClarity = r[coarse]

        // Refined at the full rate: the best of the full-rate lags the coarse one could stand for,
        // then a parabola through it and its neighbours.
        val low = (coarse * f - f).coerceAtLeast(1)
        val high = (coarse * f + f).coerceAtMost(n - 2)
        var lag = low
        var peak = -2f
        for (candidate in low..high) {
            val v = correlate(x, n, candidate)
            if (v > peak) {
                peak = v
                lag = candidate
            }
        }
        val left = correlate(x, n, lag - 1)
        val right = correlate(x, n, lag + 1)
        val denominator = left - 2f * peak + right
        val delta = if (denominator != 0f) (left - right) / (2f * denominator) else 0f
        return sampleRate / (lag + delta.coerceIn(-0.5f, 0.5f))
    }

    /** Normalised autocorrelation of the first [n] samples of [x] at [lag]. */
    private fun correlate(x: FloatArray, n: Int, lag: Int): Float {
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
        return if (e0 > 0.0 && e1 > 0.0) (dot / sqrt(e0 * e1)).toFloat() else 0f
    }

    companion object {
        /** Under a five-string bass's low B at 30.87 Hz, with most of a semitone of room to tune up to it. */
        const val MIN_HZ = 28f

        /** Above the high E with a semitone and a half of room; the harmonics are not what is tracked. */
        const val MAX_HZ = 360f

        /** The rate the search runs at, or the next below it that divides the input's. */
        const val ANALYSIS_HZ = 32_000f

        /** Periods of the lowest pitch in one window: about 180 ms. */
        const val WINDOW_PERIODS = 5

        /** -50 dBFS. Pickup hum sits well below this; a plucked string well above it. */
        const val GATE_RMS = 0.00316f

        /** A correlation below this at every lag is noise, or two notes at once. */
        const val CLARITY_MIN = 0.8f

        /** How close to the best correlation a shorter lag must come to be taken as the period. */
        const val PEAK_SHARE = 0.9f
    }
}
