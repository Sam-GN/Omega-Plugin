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

    var sampleRate: Float = DEFAULT_SAMPLE_RATE
        private set

    private val tracker = PitchTracker()

    @Volatile
    private var ring = FloatArray(0)

    /** Samples ever written. Read as a whole by [read]; the ring's size is a power of two, so the index is a mask. */
    @Volatile
    private var written = 0L

    private var window = FloatArray(0)
    private var previous: GuitarString? = null

    init {
        prepare(DEFAULT_SAMPLE_RATE)
    }

    /** On the opening thread, with the rate the device granted. Sizes everything; the audio thread allocates nothing. */
    fun prepare(rate: Float) {
        sampleRate = rate
        tracker.prepare(rate)
        val needed = tracker.windowSize
        var size = 1
        while (size < needed * RING_WINDOWS) size = size shl 1
        window = FloatArray(needed)
        previous = null
        written = 0L
        ring = FloatArray(size)
    }

    /** The audio thread: [frames] of the dry input into the ring. Nothing else happens here. */
    fun feed(input: FloatArray, frames: Int) {
        if (!enabled) return
        val r = ring
        val mask = r.size - 1
        val start = (written and mask.toLong()).toInt()
        for (i in 0 until frames) r[(start + i) and mask] = input[i]
        written += frames
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
    fun read(): Reading? {
        val r = ring
        val w = window
        val n = w.size
        val end = written
        if (end < n || r.size < n) return null
        val mask = r.size - 1
        val start = end - n
        for (i in 0 until n) w[i] = r[((start + i) and mask.toLong()).toInt()]
        val hz = tracker.estimate(w, n)
        if (hz <= 0f) {
            previous = null
            return null
        }
        val string = GuitarString.choose(hz, previous)
        previous = string
        return Reading(string, string.cents(hz), tracker.lastClarity, hz)
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
            written = 0L
            previous = null
        }
    }

    private companion object {
        /** Ring length in windows; the reader is never this far behind the writer. */
        const val RING_WINDOWS = 4
    }
}
