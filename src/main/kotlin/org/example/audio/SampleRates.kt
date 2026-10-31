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

import org.example.dsp.Resampler

/**
 * Choosing one sample rate for two pieces of hardware that may not agree.
 *
 * The rule: **the engine runs at the output's rate, and only the input is ever converted.** On a
 * two-clock backend the render endpoint drives the engine, so it stays native and every block is
 * exactly its period; a capture endpoint that cannot do that rate opens at its own and its samples
 * go through the chain's fixed-ratio `Resampler` on the way into the ring, which already absorbs
 * uneven counts. The conversion costs half the filter — 32 frames at the input's rate, 0.7 ms at
 * 44.1 kHz — and is declared in `StreamFormat.adapterLatencyFrames` and said in the status line.
 * Converting the output instead would buy nothing: the round trip is set by the hardware periods
 * in milliseconds, and the engine's rate only changes how many frames that is.
 *
 * Up to 1.5.x the rule was "offer only rates both accept, never resample", on the belief that a
 * converter would cost more than the backend saved. Measured against this filter it is a small
 * fraction of the ring hop that was already there, and the pair it refused — a 44.1 kHz-only USB
 * interface into a 48 kHz-only HDMI output — is an ordinary desk.
 *
 * Not every backend converts. [AudioBackend.convertsInput] says which; the rest still get the
 * intersection and the honest refusal. Single-device backends (ASIO, JACK) never reach any of
 * this: one driver owns both directions, so there is one rate and nothing to reconcile.
 *
 * Rate is also a latency lever, which is why the picker is worth having rather than a constant.
 * The period is `frames / rate`, so at a fixed 128 frames:
 *
 * | Rate | Period |
 * |---|---:|
 * | 44 100 | 2.90 ms |
 * | 48 000 | 2.67 ms |
 * | 96 000 | 1.33 ms |
 *
 * Pure functions with no platform in them, so a Linux backend gets the same behaviour for free.
 */
object SampleRates {

    /**
     * Rates both endpoints will take, ascending.
     *
     * An empty list from either side means "this endpoint did not say", which is treated as
     * "imposes no constraint" rather than "supports nothing" — a backend that cannot enumerate its
     * rates must not be able to empty the picker.
     */
    fun common(inputRates: List<Int>, outputRates: List<Int>): List<Int> = when {
        inputRates.isEmpty() && outputRates.isEmpty() -> emptyList()
        inputRates.isEmpty() -> outputRates.distinct().sorted()
        outputRates.isEmpty() -> inputRates.distinct().sorted()
        else -> inputRates.filter { it in outputRates }.distinct().sorted()
    }

    /**
     * The rates to put in the picker.
     *
     * On a backend that converts its input, the output's rates — all of them, since the input can
     * be brought to any of them. On one that does not, the intersection. An output that reported
     * nothing constrains nothing, as in [common].
     */
    fun offer(inputRates: List<Int>, outputRates: List<Int>, convertsInput: Boolean): List<Int> = when {
        !convertsInput -> common(inputRates, outputRates)
        outputRates.isEmpty() -> inputRates.distinct().sorted()
        else -> outputRates.distinct().sorted()
    }

    /** Whether running the engine at [rate] means converting an input that offers [inputRates]. */
    fun needsConversion(rate: Int, inputRates: List<Int>): Boolean =
        inputRates.isNotEmpty() && rate !in inputRates

    /**
     * The rate the capture endpoint opens at when the engine runs at [rate]: [rate] itself when it
     * can, else the rate it is already set to, else the highest it offers. The rate a backend
     * probed its buffer sizes at has to agree with this, which is why it is one function.
     */
    fun captureRate(rate: Int, inputRates: List<Int>, inputPreferred: Int?): Int = when {
        !needsConversion(rate, inputRates) -> rate
        inputPreferred != null && inputPreferred in inputRates -> inputPreferred
        else -> inputRates.max()
    }

    /**
     * Which of [available] to select by default.
     *
     * A rate the input can do natively beats one it would have to be converted to, whatever
     * anybody prefers: the conversion is cheap, and still not free. Within that, the endpoints'
     * own preferences come first, because those are not guesses: for ASIO it is the rate set in
     * the driver's control panel, and for WASAPI it is the rate Windows is configured for.
     * Honouring that means the picker opens on the value the user already chose elsewhere, and
     * it avoids forcing a rate switch the hardware would have to do at open.
     *
     * Only when neither preference survives does policy apply, and then the highest rate wins —
     * the period shortens as the rate rises, and this program's whole objective is the shortest
     * period the hardware will hold.
     */
    fun recommended(
        available: List<Int>,
        inputPreferred: Int? = null,
        outputPreferred: Int? = null,
        inputRates: List<Int> = emptyList(),
    ): Int? {
        if (available.isEmpty()) return null
        val native = available.filter { !needsConversion(it, inputRates) }
        val pool = native.ifEmpty { available }
        inputPreferred?.let { if (it in pool) return it }
        outputPreferred?.let { if (it in pool) return it }
        return pool.max()
    }

    /**
     * The sentence shown when the two endpoints share no rate and the backend does not convert.
     *
     * It has to name the real constraint. The failure this replaces told the user to "select
     * 44100 Hz in Windows sound settings" for an HDMI output that is physically incapable of
     * 44.1 kHz — advice that cannot be followed and is not true. Naming both devices and what each
     * actually supports at least points at the pair that has to change.
     */
    fun noOverlapMessage(
        inputName: String,
        inputRates: List<Int>,
        outputName: String,
        outputRates: List<Int>,
    ): String = "$inputName supports ${describe(inputRates)} and $outputName supports " +
        "${describe(outputRates)}, so there is no rate they share, and this driver path does not " +
        "convert between them. Choose a different pair, or a driver that does."

    /** What the converter adds, in milliseconds: half its filter, at the input's rate. */
    fun conversionMs(inputRate: Int): Float =
        if (inputRate <= 0) 0f else Resampler.DEFAULT_TAPS / 2f * 1000f / inputRate

    /** The converter's delay in frames **at the engine's rate**, for `adapterLatencyFrames`. */
    fun conversionFrames(inputRate: Int, rate: Int): Int =
        if (inputRate <= 0) 0 else Math.round(Resampler.DEFAULT_TAPS / 2f * rate / inputRate)

    /**
     * The sentence that explains a `C` beside the rate: which device is being converted, from what,
     * to what, and the cost. Shown before Start when a converted rate is picked and again when the
     * stream is running.
     */
    fun conversionNote(inputName: String, inputRate: Int, rate: Int): String =
        "$inputName runs at ${format(inputRate)} and is converted to ${format(rate)} on the way in, " +
            "adding ${String.format("%.1f", conversionMs(inputRate))} ms."

    /** "48 kHz", or "48 kHz C" when reaching it means converting the input. */
    fun label(rate: Int, converted: Boolean): String = if (converted) "${format(rate)} C" else format(rate)

    /** "48 kHz", "44.1 kHz", "44.1 or 48 kHz", "44.1, 48 or 96 kHz". */
    fun describe(rates: List<Int>): String {
        val sorted = rates.distinct().sorted()
        return when (sorted.size) {
            0 -> "no rate this backend could identify"
            1 -> "only ${format(sorted[0])}"
            else -> sorted.dropLast(1).joinToString(", ") { short(it) } + " or ${format(sorted.last())}"
        }
    }

    /** "44.1 kHz" — trailing ".0" dropped, because "48.0 kHz" reads as a measurement, not a rate. */
    fun format(rate: Int): String = "${short(rate)} kHz"

    private fun short(rate: Int): String =
        if (rate % 1000 == 0) "${rate / 1000}" else String.format("%.1f", rate / 1000f)

    /** Callback period in milliseconds — the number the buffer picker shows beside the frames. */
    fun periodMs(frames: Int, rate: Int): Float =
        if (rate <= 0) 0f else frames * 1000f / rate
}
