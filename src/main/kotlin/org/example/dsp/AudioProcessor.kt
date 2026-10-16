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

/**
 * Sample rate an effect assumes until [AudioProcessor.prepare] is called.
 *
 * Effects are constructed long before a device is chosen — and the headless DSP tests never open one
 * at all — so every implementation has to be usable straight from its constructor.
 */
const val DEFAULT_SAMPLE_RATE = 44_100f

/**
 * A single effect in the chain.
 *
 * [process] runs on the realtime audio thread and must not allocate, block, or lock. Implementations
 * pre-allocate their delay lines in [prepare] and read parameters as plain volatile fields.
 */
interface AudioProcessor {

    val name: String

    /** Toggled by the UI switch; the chain skips disabled effects unless [mutesWhenDisabled]. */
    var enabled: Boolean

    /**
     * When true the chain keeps calling [process] while the effect is disabled, letting the switch
     * silence the signal instead of bypassing it. Only the master volume needs this — for every
     * other pedal, off means "pass through untouched".
     */
    val mutesWhenDisabled: Boolean get() = false

    val parameters: List<Parameter>

    /**
     * How many of [parameters] are worth showing before the user asks for the rest.
     *
     * A module with more controls than this gets a `+` on its header that reveals the remainder, and
     * every hidden parameter is still allocated, still prepared and still processed — it is sitting
     * at a default that costs nothing, not absent. That is deliberate: expanding an [Eq] must not
     * construct a filter, because the button can be pressed while the stream is running and building
     * anything on that path is what the threading contract forbids.
     *
     * Defaults to "all of them", so every existing module is unaffected and gets no button.
     */
    val compactParameters: Int get() = parameters.size

    /**
     * Other processors this one may start running without warning — an [FxSlot]'s unselected types.
     *
     * The warm-up pass has to visit these too, and that is the whole reason the interface knows
     * about them. Warm-up exists to force class loading and JIT compilation off the audio thread;
     * an effect that first runs when a dropdown is changed mid-stream would do both *on* it, at
     * which point the pass has protected everything except the one path that arrives unannounced.
     *
     * Empty for every module that is only ever itself.
     */
    val alternates: List<AudioProcessor> get() = emptyList()

    /**
     * Binds the effect to a device rate and block size. Called off the audio thread before the
     * stream starts, and again whenever either changes.
     *
     * Size every rate-dependent buffer and coefficient here. Anything derived from the sample rate
     * and left baked in at 44.1 kHz turns into a control that lies: a 1000 ms delay knob against a
     * line sized for 44.1 kHz silently tops out near 500 ms at 96 kHz.
     *
     * Implementations must tolerate never being called — the constructor assumes
     * [DEFAULT_SAMPLE_RATE] until told otherwise.
     */
    fun prepare(sampleRate: Float, maxBlockFrames: Int)

    /** Processes the first [count] mono samples of [buffer] in place. */
    fun process(buffer: FloatArray, count: Int)

    /** Clears internal state (delay lines, filter memory) before the stream starts. */
    fun reset()
}
