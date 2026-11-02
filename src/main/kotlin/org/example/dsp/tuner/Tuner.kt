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

/**
 * The tuner: the dry guitar, before the boards, read for its pitch off the audio thread.
 *
 * Two sides that never meet on a lock. The audio thread calls [feed] once a block and does nothing
 * but copy the input into a ring and advance a counter — a 64-float copy, no allocation, no
 * analysis. A background thread calls [read] a few times a second: it takes the newest window out of
 * the ring and hands it to a [PitchTracker]. The ring is four windows long, so the writer would have
 * to get three windows ahead during one copy to tear it, and a copy takes microseconds.
 *
 * Not an `AudioProcessor`, like the metronome and the loop box, because it touches no audio: the
 * sound carries on through the pedals while it listens. The dry input rather than the output, because
 * an overdrive's harmonics and a reverb's tail are what make a pitch hard to read.
 */
class Tuner {

    /** Whether [feed] copies anything. One volatile store from the UI, like a pedal's switch. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Everything one sample rate needs, made together and replaced together (2.1.4). `prepare` runs
     * on the opening thread, and on a driver's own format change while the stream runs, and a read
     * on the strip's thread could be half-way through the old sizes when the new ones arrived: a
     * rate change mid-read threw out of bounds and the strip's thread died with it. A read now takes
     * one state and finishes on it, whatever replaces it meanwhile.
     */
    private class State(rate: Float) {
        val tracker = PitchTracker(rate)
        val window = FloatArray(tracker.windowSize)
        val ring: FloatArray

        /** Samples ever written. Read as a whole by [read]; the ring's size is a power of two, so the index is a mask. */
        @Volatile
        var written = 0L

        init {
            var size = 1
            while (size < window.size * RING_WINDOWS) size = size shl 1
            ring = FloatArray(size)
        }
    }

    @Volatile
    private var state = State(DEFAULT_SAMPLE_RATE)

    val sampleRate: Float get() = state.tracker.sampleRate

    @Volatile
    private var previous: GuitarString? = null

    /** On the opening thread, with the rate the device granted. Sizes everything; the audio thread allocates nothing. */
    fun prepare(rate: Float) {
        state = State(rate)
        previous = null
    }

    /** The audio thread: [frames] of the dry input into the ring. Nothing else happens here. */
    fun feed(input: FloatArray, frames: Int) {
        if (!enabled) return
        val s = state
        val r = s.ring
        val mask = r.size - 1
        val start = (s.written and mask.toLong()).toInt()
        for (i in 0 until frames) r[(start + i) and mask] = input[i]
        s.written += frames
    }

    /** What the string is doing right now. */
    class Reading(
        val string: GuitarString,
        /** Positive sharp, negative flat, of [string]. */
        val cents: Float,
        /** How clear the note was, 0..1. */
        val clarity: Float,
        val hz: Float,
    )

    /**
     * The newest window, read for its pitch. Any thread but the audio thread. Null when less than a
     * window has arrived or nothing clear is sounding, which also forgets the string last heard, so
     * the next note is named by what it is nearest to rather than by what came before it.
     */
    @Synchronized
    fun read(): Reading? {
        // Synchronized among readers, which are never the audio thread: two strip threads can
        // overlap for a moment when the tuner is switched off and on again, and they share the
        // window and the tracker's arrays.
        val s = state
        val r = s.ring
        val w = s.window
        val n = w.size
        val end = s.written
        if (end < n || r.size < n) return null
        val mask = r.size - 1
        val start = end - n
        for (i in 0 until n) w[i] = r[((start + i) and mask.toLong()).toInt()]
        val hz = s.tracker.estimate(w, n)
        if (hz <= 0f) {
            previous = null
            return null
        }
        val string = GuitarString.choose(hz, previous)
        previous = string
        return Reading(string, string.cents(hz), s.tracker.lastClarity, hz)
    }

    /**
     * Runs [feed] over [blocks] blocks of [input] so the method is compiled before the stream starts,
     * then forgets what it wrote. On the opening thread, from the engine's warm-up.
     */
    fun warmUp(input: FloatArray, frames: Int, blocks: Int) {
        val was = enabled
        enabled = true
        try {
            repeat(blocks) { feed(input, frames) }
        } finally {
            enabled = was
            state.written = 0L
            previous = null
        }
    }

    private companion object {
        /** Ring length in windows; the reader is never this far behind the writer. */
        const val RING_WINDOWS = 4
    }
}
