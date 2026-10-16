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

import kotlin.math.exp

/**
 * Master level, first in the chain.
 *
 * Unlike the other pedals its switch is a **mute**, not a bypass: switching it off drives the signal
 * to silence rather than passing it through untouched. [mutesWhenDisabled] tells [EffectChain] to
 * keep calling [process] while disabled, so the mute can ramp over a few milliseconds instead of
 * cutting the waveform mid-cycle, which would click.
 */
class MasterVolume : AudioProcessor {

    override val name = "Master"

    @Volatile
    override var enabled = true

    override val mutesWhenDisabled = true

    val volume = Parameter("Volume", 0f, 100f, 80f, "%")

    override val parameters = listOf(volume)

    private var smoothing = smoothingFor(DEFAULT_SAMPLE_RATE)
    private var current = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        smoothing = smoothingFor(sampleRate)
    }

    override fun process(buffer: FloatArray, count: Int) {
        val target = if (enabled) taper(volume.value) else 0f
        val s = smoothing
        var g = current
        for (i in 0 until count) {
            g += (target - g) * s
            buffer[i] *= g
        }
        current = g
    }

    override fun reset() {
        current = if (enabled) taper(volume.value) else 0f
    }

    private companion object {
        /**
         * ~11 ms, so a mute lands fast enough to feel instant but without a step. Held in seconds
         * rather than as a per-sample coefficient so the ramp takes the same *time* on every device.
         */
        const val SMOOTHING_SECONDS = 500f / 44_100f

        fun smoothingFor(sampleRate: Float): Float = 1f - exp(-1f / (sampleRate * SMOOTHING_SECONDS))

        /**
         * Loudness is not linear in knob travel. Squaring puts half travel near -12 dB, which is
         * where a volume control feels halfway, and keeps 0 % at exact silence rather than merely
         * quiet — so the bottom of the knob and the mute switch agree.
         */
        fun taper(percent: Float): Float {
            val x = percent / 100f
            return x * x
        }
    }
}
