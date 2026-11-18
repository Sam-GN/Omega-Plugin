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
package org.example.audio.alsa

/**
 * What the stream loop does after `snd_pcm_wait` returns without having blocked (2.2.3).
 *
 * The loop waits for a PCM to reach a level — the output down to one queued period, the input up
 * to a whole one — and trusts the wait to sleep until then. PipeWire's ALSA plugin does not honour
 * `avail_min`: measured on this program's own settings (48 kHz, 256-frame periods, `avail_min`
 * 768), its `snd_pcm_wait` returned in under 0.2 ms 1 388 342 times in three seconds while 559
 * periods were written. The loop went round without ever sleeping, and it is a `SCHED_FIFO` thread
 * under the 200 ms `RLIMIT_RTTIME` rtkit requires, so the kernel killed the whole process with
 * SIGKILL within a second of Start — nothing logged, exit status 9. Only the converting loop waits
 * on the output, so only a converted rate (`C` in the picker) into the PipeWire `Default` output
 * did it; a `hw:` output honours `avail_min` and never needed this.
 *
 * So the loop no longer trusts the wait. A wait that came back in under [EARLY_NANOS] without the
 * level being reached is followed by a real sleep for as long as the missing frames take to arrive
 * at the stream's rate, clamped to [MIN_NAP_MICROS]..the wait's own timeout. A sleep blocks, which
 * also resets the kernel's realtime count. A wait that did block costs nothing extra, so `hw:`
 * devices behave exactly as before.
 *
 * Allocation-free: [Nap] is a `fun interface` over a primitive, so calling it boxes nothing.
 */
internal class WaitGuard(
    private val rate: Int,
    private val timeoutMillis: Int,
    private val nap: Nap,
) {
    fun interface Nap {
        fun micros(us: Int)
    }

    /** Waits that returned without blocking and were followed by a nap. Read by the monitor. */
    @Volatile
    var earlyWakes: Long = 0
        private set

    /**
     * Call after `snd_pcm_wait` returned [result] having taken [tookNanos], when the loop still
     * lacks [missingFrames]. An error result is left for the caller; it is not a wake.
     */
    fun after(result: Int, tookNanos: Long, missingFrames: Long) {
        if (result < 0 || tookNanos >= EARLY_NANOS) return
        earlyWakes++
        nap.micros(napMicros(missingFrames, rate, timeoutMillis))
    }

    companion object {
        /** A wait shorter than this did not sleep: a scheduler wake alone takes longer. */
        const val EARLY_NANOS = 200_000L

        /** Never less: a frame or two short would otherwise be a near-spin of tiny sleeps. */
        const val MIN_NAP_MICROS = 100

        /** How long [missingFrames] take to arrive at [rate], clamped to a sane sleep. */
        fun napMicros(missingFrames: Long, rate: Int, timeoutMillis: Int): Int {
            val max = timeoutMillis * 1000L
            if (missingFrames <= 0 || rate <= 0) return MIN_NAP_MICROS
            val us = (missingFrames * 1_000_000L + rate - 1) / rate
            return us.coerceIn(MIN_NAP_MICROS.toLong(), max).toInt()
        }
    }
}
