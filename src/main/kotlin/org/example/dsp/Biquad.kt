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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR section — the building block every filter in the app is made of.
 *
 * Coefficients come from the Audio EQ Cookbook (Robert Bristow-Johnson), which is the standard
 * derivation for these five shapes and the one every other implementation is comparable against.
 *
 * Two decisions worth stating:
 *
 * **Transposed direct form II**, not direct form I. It carries two state variables instead of four,
 * and — the reason that matters here — its state holds a *filtered* quantity rather than raw input
 * history, so at the low corner frequencies a guitar high-pass actually uses it loses far less to
 * `Float` rounding. Direct form I audibly grits up a 30 Hz high-pass at single precision.
 *
 * **Coefficients are computed in `Double` and stored as `Float`.** The trigonometry runs once per
 * block off the back of a knob value; the per-sample arithmetic runs 44 100 times a second. Doing
 * the design in single precision costs accuracy exactly where the filter is most sensitive to it,
 * and costs nothing to avoid, because it does not happen per sample.
 *
 * Designing a section allocates nothing — every intermediate is a local primitive — so it is safe to
 * re-derive on the audio thread when a knob has moved.
 */
class Biquad {

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var s1 = 0f
    private var s2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + s1
        s1 = b1 * x - a1 * y + s2
        s2 = b2 * x - a2 * y
        return y
    }

    /** Clears filter memory. Coefficients are left alone — they are a setting, not state. */
    fun reset() {
        s1 = 0f
        s2 = 0f
    }

    /** Passes everything through untouched. */
    fun bypass() {
        b0 = 1f
        b1 = 0f
        b2 = 0f
        a1 = 0f
        a2 = 0f
    }

    fun lowPass(sampleRate: Float, frequencyHz: Float, q: Float) {
        val w = omega(sampleRate, frequencyHz)
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * q.coerceAtLeast(MIN_Q))
        set(
            b0 = (1.0 - cosW) / 2.0,
            b1 = 1.0 - cosW,
            b2 = (1.0 - cosW) / 2.0,
            a0 = 1.0 + alpha,
            a1 = -2.0 * cosW,
            a2 = 1.0 - alpha,
        )
    }

    fun highPass(sampleRate: Float, frequencyHz: Float, q: Float) {
        val w = omega(sampleRate, frequencyHz)
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * q.coerceAtLeast(MIN_Q))
        val a0 = 1.0 + alpha
        set(
            b0 = (1.0 + cosW) / 2.0,
            b1 = -(1.0 + cosW),
            b2 = (1.0 + cosW) / 2.0,
            a0 = a0,
            a1 = -2.0 * cosW,
            a2 = 1.0 - alpha,
        )
    }

    fun peaking(sampleRate: Float, frequencyHz: Float, gainDb: Float, q: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(sampleRate, frequencyHz)
        val cosW = cos(w)
        val alpha = sin(w) / (2.0 * q.coerceAtLeast(MIN_Q))
        set(
            b0 = 1.0 + alpha * a,
            b1 = -2.0 * cosW,
            b2 = 1.0 - alpha * a,
            a0 = 1.0 + alpha / a,
            a1 = -2.0 * cosW,
            a2 = 1.0 - alpha / a,
        )
    }

    fun lowShelf(sampleRate: Float, frequencyHz: Float, gainDb: Float, slope: Float = 1f) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(sampleRate, frequencyHz)
        val cosW = cos(w)
        val beta = sqrt(a) * shelfAlpha(sin(w), a, slope)
        set(
            b0 = a * ((a + 1.0) - (a - 1.0) * cosW + beta),
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW),
            b2 = a * ((a + 1.0) - (a - 1.0) * cosW - beta),
            a0 = (a + 1.0) + (a - 1.0) * cosW + beta,
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW),
            a2 = (a + 1.0) + (a - 1.0) * cosW - beta,
        )
    }

    fun highShelf(sampleRate: Float, frequencyHz: Float, gainDb: Float, slope: Float = 1f) {
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(sampleRate, frequencyHz)
        val cosW = cos(w)
        val beta = sqrt(a) * shelfAlpha(sin(w), a, slope)
        set(
            b0 = a * ((a + 1.0) + (a - 1.0) * cosW + beta),
            b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW),
            b2 = a * ((a + 1.0) + (a - 1.0) * cosW - beta),
            a0 = (a + 1.0) - (a - 1.0) * cosW + beta,
            a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW),
            a2 = (a + 1.0) - (a - 1.0) * cosW - beta,
        )
    }

    private fun set(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        val n = 1.0 / a0
        this.b0 = (b0 * n).toFloat()
        this.b1 = (b1 * n).toFloat()
        this.b2 = (b2 * n).toFloat()
        this.a1 = (a1 * n).toFloat()
        this.a2 = (a2 * n).toFloat()
    }

    private companion object {
        /** A Q of zero is a division by zero, and a knob can be turned there. */
        const val MIN_Q = 0.05f

        /**
         * Corner frequency in radians per sample, clamped below Nyquist.
         *
         * The clamp is not cosmetic. A shelf asked for 12 kHz on a 22.05 kHz-capable device would be
         * designed above Nyquist, where the cookbook formulas produce an unstable section — and an
         * unstable filter in the callback is a burst of full-scale noise into whatever the output is
         * plugged into. The cap is generous enough never to be reached at 44.1 kHz and up.
         */
        fun omega(sampleRate: Float, frequencyHz: Float): Double {
            val nyquist = sampleRate / 2.0
            val f = frequencyHz.toDouble().coerceIn(10.0, nyquist * 0.95)
            return 2.0 * PI * f / sampleRate
        }

        fun shelfAlpha(sinW: Double, a: Double, slope: Float): Double {
            val s = slope.toDouble().coerceIn(0.1, 2.0)
            return sinW * sqrt((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0)
        }
    }
}
