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

import kotlin.math.abs
import kotlin.math.exp

/**
 * A smoothed level with a mute: the shape the master and each board's LEVEL share.
 *
 * Unlike a pedal its switch is a **mute**, not a bypass: switching it off drives the signal to
 * silence rather than passing it through untouched. [mutesWhenDisabled] tells [EffectChain] to keep
 * calling [process] while disabled, so the mute can ramp over a few milliseconds instead of cutting
 * the waveform mid-cycle, which would click.
 *
 * [active] is what lets a stage sit in a chain permanently and cost nothing while it is not wanted.
 * A board's LEVEL only means anything in split mode, and the rule that a chain's membership never
 * changes while the stream runs means it cannot be added for the occasion — so it is always there,
 * and inactive it ramps to unity and then leaves the buffer alone entirely. That last part is
 * checked bit-for-bit: a stage that merely got within a rounding error of unity would still be a
 * stage you could not leave in the chain.
 */
open class VolumeStage(override val name: String, defaultPercent: Float) : AudioProcessor {

    @Volatile
    override var enabled = true

    override val mutesWhenDisabled = true

    val volume = Parameter("Volume", 0f, 100f, defaultPercent, "%")

    override val parameters = listOf(volume)

    /**
     * False puts the stage at unity whatever the knob and the switch say, after the same ramp a
     * change of level takes. Written by the UI, read once per block on the audio thread.
     */
    @Volatile
    var active = true

    private var smoothing = smoothingFor(DEFAULT_SAMPLE_RATE)
    private var current = 0f

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        smoothing = smoothingFor(sampleRate)
    }

    override fun process(buffer: FloatArray, count: Int) {
        val target = targetGain()
        var g = current
        // An exponential approach never quite arrives, so it is snapped once it is close enough not
        // to be heard: -80 dB of the remaining distance. That is what makes unity *exactly* unity,
        // which is the fast path below, and what makes zero exactly zero rather than merely quiet.
        if (abs(target - g) < SNAP) g = target
        if (g == target && g == 1f) {
            current = g
            return
        }
        val s = smoothing
        for (i in 0 until count) {
            g += (target - g) * s
            buffer[i] *= g
        }
        current = g
    }

    override fun reset() {
        current = targetGain()
    }

    private fun targetGain(): Float = when {
        !active -> 1f
        enabled -> taper(volume.value)
        else -> 0f
    }

    private companion object {
        /**
         * ~11 ms, so a mute lands fast enough to feel instant but without a step. Held in seconds
         * rather than as a per-sample coefficient so the ramp takes the same *time* on every device.
         */
        const val SMOOTHING_SECONDS = 500f / 44_100f

        /** How close the ramp has to get before it is called arrived: -80 dB of the distance. */
        const val SNAP = 1e-4f

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
