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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixed-ratio polyphase resampler.
 *
 * **Synchronous, not asynchronous.** Both rates come off one clock, so the ratio is a fixed rational
 * number — 44.1 kHz to 48 kHz is exactly 147:160 — and stays exact for ever. That is what makes this
 * a table of coefficients rather than a control loop: there is no drift to track, no fractional
 * phase to interpolate, and the output is bit-repeatable. A converter joining two *devices* would be
 * a different and much harder object, because two crystals disagree by tens of parts per million and
 * the ratio has to be measured and steered. This is not that; do not grow it into that.
 *
 * The polyphase identity: with an interpolation factor `L` and decimation `M`, output `n` is
 *
 * ```
 * y[n] = sum_i h[phase + i*L] * x[floor(n*M/L) - i],   phase = (n*M) mod L
 * ```
 *
 * so only one in `L` of the prototype's taps is ever needed per output, and the zero-stuffed signal
 * is never built. Cost is [taps] multiply-adds per output sample and nothing else.
 *
 * Nothing here allocates after construction. The prototype is designed in the constructor, which
 * runs on whichever thread is preparing the chain, never on the audio thread.
 */
class Resampler(
    private val fromRate: Float,
    toRate: Float,
    maxInputFrames: Int,
    private val taps: Int = DEFAULT_TAPS,
) {

    /** Interpolation factor: the reduced numerator of `toRate / fromRate`. */
    private val up: Int

    /** Decimation factor: the reduced denominator. */
    private val down: Int

    /** The prototype in polyphase order: `[phase * taps + i]`. */
    private val coefficients: FloatArray

    /** Doubled so the newest [taps] samples are always contiguous — no modulo in the inner loop. */
    private val history: FloatArray

    private var position = 0
    private var phase = 0

    /** The most output frames [process] can produce from a full input block. */
    val maxOutputFrames: Int

    /** Group delay, in **input** frames. Half the prototype, by symmetry. */
    val latencyInputFrames: Float get() = taps / 2f

    init {
        require(taps >= 8) { "a resampler needs a usable number of taps" }
        val from = fromRate.roundToInt()
        val to = toRate.roundToInt()
        require(from > 0 && to > 0) { "rates must be positive" }
        val divisor = gcd(from, to)
        up = to / divisor
        down = from / divisor

        coefficients = design(toRate)
        history = FloatArray(2 * taps)
        maxOutputFrames = (maxInputFrames.toLong() * up / down).toInt() + 2
    }

    /**
     * Resamples [count] input frames, returning how many output frames were written to [output].
     *
     * The count varies from call to call and that is not a defect: 64 frames at 44.1 kHz is 69.66 at
     * 48 kHz, so blocks come out at 69 or 70 and average exactly right. Callers that must hand on a
     * fixed block size need a reservoir — see [RateAdapter].
     */
    fun process(input: FloatArray, count: Int, output: FloatArray): Int {
        var produced = 0
        val width = taps
        for (n in 0 until count) {
            val at = position
            val sample = input[n]
            history[at] = sample
            history[at + width] = sample
            position = if (at + 1 == width) 0 else at + 1

            // Newest sample first, walking backwards through the contiguous copy.
            val base = at + width
            while (phase < up) {
                val row = phase * width
                var sum = 0f
                for (i in 0 until width) sum += coefficients[row + i] * history[base - i]
                output[produced++] = sum
                phase += down
            }
            phase -= up
        }
        return produced
    }

    fun reset() {
        history.fill(0f)
        position = 0
        phase = 0
    }

    /**
     * Builds the anti-imaging prototype: a windowed sinc, cut so its stopband starts at the lower of
     * the two Nyquist limits.
     *
     * The transition band has to fit *below* that limit or images fold back into the audio, and its
     * width is set by the tap count — roughly `5 * fromRate / taps` for this window. That is the
     * whole trade: [DEFAULT_TAPS] taps put the passband flat to about 18.6 kHz with images 80 dB
     * down, and cost half a tap-count of delay. Fewer taps would be cheaper and duller, and dulling
     * the top is the exact complaint this converter exists to fix.
     */
    private fun design(toRate: Float): FloatArray {
        val length = up * taps
        val nyquist = min(fromRate, toRate) / 2f
        val transition = KAISER_TRANSITION * fromRate / taps
        // Never let a pathological ratio push the cutoff to nothing.
        val cutoff = (nyquist - transition / 2f).coerceAtLeast(nyquist * 0.5f)
        val normalised = cutoff / (fromRate * up)
        val centre = (length - 1) / 2.0
        val bessel = besselI0(KAISER_BETA)

        val prototype = DoubleArray(length)
        var sum = 0.0
        for (j in 0 until length) {
            val t = j - centre
            val ideal = if (abs(t) < 1e-9) {
                2.0 * normalised
            } else {
                sin(2.0 * PI * normalised * t) / (PI * t)
            }
            // Kaiser: the best stopband for a given transition width, and the reason this is only
            // 64 taps rather than the couple of hundred a plain window would need.
            val ratio = 2.0 * j / (length - 1) - 1.0
            val window = besselI0(KAISER_BETA * sqrt((1.0 - ratio * ratio).coerceAtLeast(0.0))) / bessel
            prototype[j] = ideal * window
            sum += prototype[j]
        }

        // Scale so unity DC in gives unity DC out, exactly, rather than approximately: the sinc's
        // truncated tails leave a small error that would otherwise read as a level change when the
        // converter is switched in.
        val scale = up / sum
        val polyphase = FloatArray(length)
        for (p in 0 until up) {
            for (i in 0 until taps) {
                polyphase[p * taps + i] = (prototype[p + i * up] * scale).toFloat()
            }
        }
        return polyphase
    }

    companion object {

        /**
         * Taps per phase.
         *
         * 64 puts the passband flat to about 18.6 kHz at 44.1 kHz with images 80 dB down, for 32
         * frames of delay. A guitar rig has nothing above 10 kHz worth keeping, so this is
         * comfortably transparent; halving it would halve the delay and pull the passband down to
         * 11 kHz, which would dull the top — the very thing being fixed.
         */
        const val DEFAULT_TAPS = 64

        /** Kaiser beta for roughly 80 dB of stopband: `0.1102 * (A - 8.7)`. */
        private const val KAISER_BETA = 7.86

        /** Transition width as a multiple of `fromRate / taps`, for that beta. */
        private const val KAISER_TRANSITION = 5.02f

        private fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (k < 64) {
                val half = x / 2.0 / k
                term *= half * half
                sum += term
                if (term < 1e-14 * sum) break
                k++
            }
            return sum
        }
    }
}

/**
 * Runs a fixed-rate process inside a chain running at a different rate.
 *
 * Converts up on the way in, hands the caller a block at the inner rate, and converts back down —
 * while still returning exactly the block size the chain asked for, which the resamplers themselves
 * cannot promise. That is what the reservoir is for: 64 frames in gives 69 or 70 at 48 kHz and 63,
 * 64 or 65 back, averaging exactly right but never matching block by block. A few frames of priming
 * absorb the difference, and are declared in [latencyFrames] rather than hidden.
 */
class RateAdapter(
    private val outerRate: Float,
    innerRate: Float,
    maxBlockFrames: Int,
) {

    private val upward = Resampler(outerRate, innerRate, maxBlockFrames)

    /** The block at the inner rate. Filled by [toInner], processed in place by the caller. */
    val innerBuffer = FloatArray(upward.maxOutputFrames)

    private val downward = Resampler(innerRate, outerRate, innerBuffer.size)
    private val returned = FloatArray(downward.maxOutputFrames)

    private val reservoir = FloatArray(downward.maxOutputFrames + maxBlockFrames + PRIME + 4)
    private var read = 0
    private var fill = PRIME

    /**
     * Everything this adds, in outer frames: both group delays plus the priming.
     *
     * Declared because a converter that hides its latency makes every comparison a lie — the same
     * rule `StreamFormat.adapterLatencyFrames` exists for on the backend side.
     */
    val latencyFrames: Int = (
        upward.latencyInputFrames +
            downward.latencyInputFrames * outerRate / innerRate +
            PRIME
        ).toInt()

    /**
     * Blocks the reservoir could not fill, and frames it had nowhere to put.
     *
     * Both should stay at zero, because the two ratios are exact inverses and the reservoir is sized
     * for the worst block. They are counted rather than assumed away: a reservoir that quietly
     * overwrites itself sounds like a bad capture, not like a bug, which is a long way to walk
     * before finding the cause.
     */
    var starved = 0
        private set

    var overflowed = 0
        private set

    /** Converts a block up. Returns how many frames of [innerBuffer] are live. */
    fun toInner(buffer: FloatArray, count: Int): Int = upward.process(buffer, count, innerBuffer)

    /** Converts [innerCount] frames back down and writes exactly [count] frames into [buffer]. */
    fun fromInner(innerCount: Int, buffer: FloatArray, count: Int) {
        val produced = downward.process(innerBuffer, innerCount, returned)
        for (i in 0 until produced) {
            if (fill == reservoir.size) {
                // Drop rather than wrap over unread frames: losing the newest is recoverable, and
                // corrupting the oldest is not.
                overflowed++
                continue
            }
            reservoir[(read + fill) % reservoir.size] = returned[i]
            fill++
        }
        if (fill < count) {
            starved++
            for (i in 0 until count) buffer[i] = 0f
            return
        }
        for (i in 0 until count) {
            buffer[i] = reservoir[read]
            read = if (read + 1 == reservoir.size) 0 else read + 1
            fill--
        }
    }

    fun reset() {
        upward.reset()
        downward.reset()
        reservoir.fill(0f)
        read = 0
        fill = PRIME
        starved = 0
    }

    private companion object {
        /**
         * Frames of silence the reservoir starts with.
         *
         * The block-to-block deviation is a frame or two, so this only has to cover the first block;
         * making it larger would buy nothing and cost latency directly.
         */
        const val PRIME = 4
    }
}
