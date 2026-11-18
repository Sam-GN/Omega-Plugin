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
package org.example.audio.wasapi

/**
 * How full the WASAPI hybrid keeps the ring between its two clocks (2.2.0).
 *
 * The output wakes every couple of milliseconds and takes a few dozen frames; the input lands a
 * whole exclusive packet at a time. So the level seen at a wake swings by most of a packet with
 * nothing wrong, and a rule read off one wake corrects at the wrong moment: the exclusive backend's
 * rule let the low point sink until 2.6 ms of capture jitter emptied the ring.
 *
 * This steers the **lowest** level over a window — at least 50 ms and two packets — to [margin]
 * above what the wake needs, one frame a wake. The margin starts at 2 ms and grows half a
 * millisecond after each run of windows that underran, up to 10: once per run rather than once per
 * underrun, because a single long stall upstream is dozens of empty wakes across several windows,
 * and charging each a step left 6 ms of latency behind one hiccup. More than 10 ms over is a backlog — an
 * input that stalled and then delivered everything at once — and is cut in one go, since the stall
 * already cost a dropout and a frame a wake would take seconds.
 *
 * Called once per render wake, on the render thread: no allocation, no locks. The outcome of a call
 * is in [drop], [read], [hold] and [underrun] until the next. `LowPointRegulatorTest` drives it with
 * simulated clocks.
 */
internal class LowPointRegulator(rate: Int, capturePacketFrames: Int) {

    private val step = maxOf(rate / 2000, 1)
    private val cap = rate / 100
    private val packet = capturePacketFrames
    private val windowFrames = maxOf(rate / 20, 2 * capturePacketFrames)

    /** Frames kept above what a wake takes, at the ring's low point. */
    var margin = rate / 500
        private set

    /** False until the ring first holds a whole wake; nothing before that is a dropout. */
    var primed = false
        private set

    /** Frames to discard before reading this wake. */
    var drop = 0
        private set

    /** Read one frame fewer than taken this wake, so it is read again next time. */
    var hold = false
        private set

    /** The ring held less than this wake takes. */
    var underrun = false
        private set

    /**
     * Frames to read from the ring this wake, after [drop]; the rest of the wake is silence. The
     * whole wake normally. Nothing until [primed]. On an [underrun], what lies above a packet and the
     * margin (2.2.4).
     */
    var read = 0
        private set

    private var windowTaken = 0
    private var windowMin = Int.MAX_VALUE
    private var windowUnderran = false
    private var previousUnderran = false
    private var pending = 0   // > 0 frames still to drop, < 0 frames still to hold back

    fun decide(available: Int, want: Int) {
        drop = 0
        hold = false
        underrun = false
        read = want
        var avail = available
        val start = want + packet + margin
        if (!primed) {
            // Nothing is read until the ring holds where it is to start from. Reading what there was
            // at each wake, as until 2.2.4, kept it from filling: it primed at the first wake that
            // happened to find a wake's worth, 87 frames over on the ordinary shared period, and the
            // next wake with one packet behind it was 90 short.
            if (avail < start) { read = 0; return }
            // Whatever the input queued before the output started, cut to where it would be held
            // anyway. Nothing has been heard yet.
            if (avail > start) { drop = avail - start; avail = start }
            primed = true
        }
        if (avail < want) {
            underrun = true
            windowUnderran = true
            // The gap is happening either way. Reading the ring to empty, as until 2.2.4, put the
            // next wake on the same packet pattern the same distance short, and the frames held back
            // since were thrown away with it: a dropout every third wake until the clocks drifted.
            // Leaving a packet and the margin makes this one gap, no longer than the wake.
            read = maxOf(avail - (packet + margin), 0)
        }
        val slack = avail - want
        if (slack < windowMin) windowMin = slack
        windowTaken += want
        if (windowTaken >= windowFrames) {
            // One step per run of underrunning windows: a long stall spans several.
            if (windowUnderran && !previousUnderran) margin = minOf(margin + step, cap)
            previousUnderran = windowUnderran
            windowUnderran = false
            // Aim the low point at the margin; inside a millisecond of it, leave it be.
            val error = windowMin - margin
            pending = when {
                error > 2 * step -> error - step
                error < 0 -> error
                else -> 0
            }
            windowTaken = 0
            windowMin = Int.MAX_VALUE
            if (pending > cap && slack - pending >= margin) {
                drop += pending
                avail -= pending
                pending = 0
            }
        }
        if (pending > 0 && avail - want > margin) {
            drop++
            pending--
        } else if (pending < 0 && avail >= want) {
            hold = true
            pending++
        }
    }
}
