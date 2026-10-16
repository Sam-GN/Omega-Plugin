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
package org.example.audio

import org.example.dsp.AudioProcessor
import org.example.dsp.DEFAULT_SAMPLE_RATE
import org.example.dsp.Parameter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * True cable-to-cable round-trip latency, measured rather than asked for.
 *
 * With a patch lead from the interface's output back into its input, this is the only number that
 * is not somebody's claim. Every driver figure — ASIO `getLatencies`, WASAPI `GetStreamLatency` —
 * excludes the converters and rests on the driver being honest about its own pipeline.
 *
 * ### Why the frame difference *is* the round trip
 *
 * The engine hands the chain one block per callback: the buffer arrives holding input frame `j` and
 * leaves as output frame `j`. Emit a burst at output frame `j`; it leaves the DAC at some real time,
 * travels the lead, and is digitised at input frame `j + D`. Since both streams are indexed by the
 * same callback, `D` is exactly the offset between the two streams' origins — which is precisely the
 * latency a player hears between striking a string and the processed note arriving. No arithmetic,
 * no driver's word for anything.
 *
 * ### Detection
 *
 * A bare amplitude threshold fails the moment the input has any signal on it — measured here, an
 * idle guitar input sat at -12 dBFS, which swamps a naive detector. So the burst is a tone and the
 * detector is a Goertzel filter at that tone's frequency, which rejects everything else. Once a
 * window trips, the onset is walked back inside it so the answer is pinned to a few samples rather
 * than to the window.
 *
 * Allocation-free once [prepare] has run, so it is safe in the callback like any other effect.
 */
class LoopbackProbe : AudioProcessor {

    override val name = "Loopback"
    override var enabled = true
    override val parameters = emptyList<Parameter>()

    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var burst = FloatArray(BURST_FRAMES)

    /** Goertzel state, reset every [WINDOW] samples. */
    private var coefficient = 0.0
    private var s1 = 0.0
    private var s2 = 0.0
    private var windowPosition = 0
    private var windowPeak = 0f

    private var frame = 0L
    private var emittedAt = -1L
    private var burstLeft = 0
    private var burstPosition = 0
    private var quietUntil = 0L

    private var baseline = 0.0
    private var threshold = 0.0

    /**
     * Peak input amplitude during the learning window.
     *
     * The Goertzel decides *whether* the burst arrived; this decides *where* it started. They need
     * separate floors: on a noisy input the walk-back threshold derived from the burst peak alone
     * sits below the noise, so the envelope never falls through it and the walk runs to the end of
     * the history, reporting an onset far earlier than the truth.
     */
    private var floorAmplitude = 0f

    /** Recent input, so the onset can be walked back once a window trips. Power of two: masked. */
    private val history = FloatArray(HISTORY)

    private val measured = IntArray(MAX_RESULTS)

    @Volatile
    var count: Int = 0
        private set

    /** Round trips in frames, oldest first. Read after the run, off the audio thread. */
    fun results(): IntArray = measured.copyOf(count)

    fun medianFrames(): Int? {
        if (count == 0) return null
        val sorted = results().sortedArray()
        return sorted[sorted.size / 2]
    }

    fun medianMillis(): Float? = medianFrames()?.let { it * 1000f / sampleRate }

    /** The learned noise level the detector is working against. Useful when nothing is detected. */
    val noiseBaseline: Double get() = baseline

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        this.sampleRate = sampleRate
        burst = FloatArray(BURST_FRAMES) {
            // Raised-cosine edges: a hard-gated tone splatters across the spectrum and rings the
            // input's anti-alias filter, which blurs exactly the onset being measured.
            val envelope = when {
                it < EDGE -> 0.5f * (1f - cos(Math.PI * it / EDGE).toFloat())
                it >= BURST_FRAMES - EDGE -> 0.5f * (1f - cos(Math.PI * (BURST_FRAMES - 1 - it) / EDGE).toFloat())
                else -> 1f
            }
            (AMPLITUDE * envelope * sin(2.0 * Math.PI * TONE_HZ * it / sampleRate)).toFloat()
        }
        coefficient = 2.0 * cos(2.0 * Math.PI * TONE_HZ / sampleRate)
        reset()
    }

    override fun reset() {
        s1 = 0.0; s2 = 0.0
        windowPosition = 0; windowPeak = 0f
        frame = 0; emittedAt = -1; burstLeft = 0; burstPosition = 0; quietUntil = 0
        baseline = 0.0; threshold = 0.0; floorAmplitude = 0f
        count = 0
        java.util.Arrays.fill(history, 0f)
    }

    override fun process(buffer: FloatArray, count: Int) {
        val learnUntil = (sampleRate * LEARN_SECONDS).toLong()
        for (i in 0 until count) {
            val x = buffer[i]
            history[(frame and HISTORY_MASK).toInt()] = x
            val ax = abs(x)
            if (ax > windowPeak) windowPeak = ax

            // Goertzel: magnitude of TONE_HZ over the current window.
            val s0 = x + coefficient * s1 - s2
            s2 = s1
            s1 = s0
            if (++windowPosition == WINDOW) {
                val magnitude = s1 * s1 + s2 * s2 - coefficient * s1 * s2
                if (frame < learnUntil) {
                    if (magnitude > baseline) baseline = magnitude
                    if (windowPeak > floorAmplitude) floorAmplitude = windowPeak
                    threshold = (baseline * REJECTION).coerceAtLeast(FLOOR)
                } else if (emittedAt >= 0 && magnitude > threshold) {
                    detect()
                }
                s1 = 0.0; s2 = 0.0
                windowPosition = 0

                windowPeak = 0f
            }

            // Emit. Nothing goes out during the learning period, so the floor is learned against
            // what is really there rather than against our own tone.
            var out = 0f
            if (burstLeft > 0) {
                out = burst[burstPosition++]
                burstLeft--
            } else if (frame >= learnUntil && emittedAt < 0 && frame > quietUntil) {
                emittedAt = frame
                burstPosition = 0
                burstLeft = BURST_FRAMES
                out = burst[burstPosition++]
                burstLeft--
            }
            buffer[i] = out
            frame++
        }
    }

    /**
     * Walks back from the window that tripped to where the burst actually begins.
     *
     * Not on `|x|` directly: at 1470 Hz the tone crosses zero every eight samples, so a bare
     * amplitude walk stops at the first zero crossing and reports an onset most of a cycle late.
     * The walk is over a short sliding envelope instead, which only falls away at the real edge.
     */
    private fun detect() {
        // Whichever is higher: a fraction of the burst, or comfortably above the noise the input
        // was already carrying before we emitted anything.
        val onsetThreshold = maxOf(windowPeak * ONSET_FRACTION, floorAmplitude * FLOOR_MARGIN)
        var at = frame
        val limit = maxOf(ENVELOPE.toLong(), frame - HISTORY + ENVELOPE)
        while (at > limit) {
            var envelope = 0f
            for (k in 0 until ENVELOPE) {
                val v = abs(history[((at - k) and HISTORY_MASK).toInt()])
                if (v > envelope) envelope = v
            }
            if (envelope < onsetThreshold) break
            at--
        }
        val onset = at + 1
        val trip = onset - emittedAt
        // A trip below one burst length would mean we detected our own emission still going out,
        // and one above a second is not a round trip, it is a room.
        if (trip in BURST_FRAMES.toLong()..sampleRate.toLong() && count < MAX_RESULTS) {
            measured[count] = trip.toInt()
            count++
        }
        emittedAt = -1
        quietUntil = onset + (sampleRate * QUIET_SECONDS).toLong()
    }

    private companion object {
        /** Not a harmonic of 50/60 Hz mains, nor of any common block size, so it cannot hide in one. */
        const val TONE_HZ = 1470.0
        const val BURST_FRAMES = 128
        const val EDGE = 16
        const val AMPLITUDE = 0.7
        const val WINDOW = 64
        const val HISTORY = 512
        const val HISTORY_MASK = (HISTORY - 1).toLong()

        /** Just over one cycle of the tone at 44.1 kHz, so the walk-back ignores zero crossings. */
        const val ENVELOPE = 32
        const val LEARN_SECONDS = 1.0f
        const val QUIET_SECONDS = 0.15f

        /** The burst has to beat the learned noise by this much before it counts as detected. */
        const val REJECTION = 25.0

        /** Absolute floor, so a perfectly silent input does not give a zero threshold. */
        const val FLOOR = 1e-4

        const val ONSET_FRACTION = 0.15f

        /** How far above the learned noise peak the walk-back has to fall before it stops. */
        const val FLOOR_MARGIN = 1.5f
        const val MAX_RESULTS = 2048
    }
}
