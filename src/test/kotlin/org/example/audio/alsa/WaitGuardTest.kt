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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The converting loop's wait against an output that behaves as PipeWire's ALSA plugin was measured
 * to: `snd_pcm_wait` reports ready at once, whatever `avail_min` says. Before 2.2.3 the loop spun on
 * it until the kernel killed the process; the numbers here are worked out from the rate, not read
 * from the code.
 */
class WaitGuardTest {

    private val rate = 48_000
    private val period = 256
    private val readyAt = 768L // a 1024-frame buffer, one period left queued

    /** Playback drains at [rate] in simulated time; [now] moves only when the guard naps. */
    private inner class FakeOutput {
        var nowMicros = 0L
        var queued = 1024L
        private var drainedAt = 0L
        fun avail(): Long {
            val drained = (nowMicros - drainedAt) * rate / 1_000_000L
            if (drained > 0) {
                queued = maxOf(0L, queued - drained)
                drainedAt += drained * 1_000_000L / rate
            }
            return 1024L - queued
        }
        fun write(frames: Int) { queued += frames }
    }

    @Test
    fun `a wait that never sleeps costs a handful of wakes per period, not a spin`() {
        val out = FakeOutput()
        val guard = WaitGuard(rate, timeoutMillis = 21) { out.nowMicros += it }
        var wakes = 0
        var periods = 0
        while (periods < 1000) {
            val room = out.avail()
            if (room < readyAt) {
                wakes++
                guard.after(result = 1, tookNanos = 1_000, missingFrames = readyAt - room)
                continue
            }
            out.write(period)
            periods++
        }
        // 1000 periods of 256 frames at 48 kHz take 5.33 s; the guard must have slept through it.
        assertTrue(out.nowMicros in 5_300_000L..5_400_000L, "simulated time ${out.nowMicros} us")
        // Each period: one nap for the shortfall, occasionally one more for a rounding remainder.
        assertTrue(wakes <= 2 * periods, "$wakes wakes for $periods periods")
        assertEquals(wakes.toLong(), guard.earlyWakes)
    }

    @Test
    fun `a wait that blocked is left alone`() {
        var naps = 0
        val guard = WaitGuard(rate, timeoutMillis = 21) { naps++ }
        guard.after(result = 1, tookNanos = 5_000_000, missingFrames = 256)
        guard.after(result = 0, tookNanos = 21_000_000, missingFrames = 256) // a timeout
        assertEquals(0, naps)
        assertEquals(0L, guard.earlyWakes)
    }

    @Test
    fun `an error is the caller's, not a wake`() {
        var naps = 0
        val guard = WaitGuard(rate, timeoutMillis = 21) { naps++ }
        guard.after(result = -32, tookNanos = 1_000, missingFrames = 256)
        assertEquals(0, naps)
    }

    @Test
    fun `the nap is the time the missing frames take, clamped`() {
        // 256 frames at 48 kHz: 5333.3 us, rounded up so the level is reached on waking.
        assertEquals(5334, WaitGuard.napMicros(256, 48_000, 21))
        // 441 frames at 44.1 kHz is exactly 10 ms.
        assertEquals(10_000, WaitGuard.napMicros(441, 44_100, 21))
        // One frame is 21 us at 48 kHz, which would be a near-spin of tiny sleeps.
        assertEquals(WaitGuard.MIN_NAP_MICROS, WaitGuard.napMicros(1, 48_000, 21))
        assertEquals(WaitGuard.MIN_NAP_MICROS, WaitGuard.napMicros(0, 48_000, 21))
        // Never past the wait's own timeout, so the loop still notices Stop.
        assertEquals(21_000, WaitGuard.napMicros(48_000, 48_000, 21))
    }
}
