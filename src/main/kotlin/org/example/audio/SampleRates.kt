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

/**
 * Choosing one sample rate for two pieces of hardware that may not agree.
 *
 * The rule, and it is deliberate: **offer only rates both endpoints accept, and never resample.**
 * A resampler in the signal path costs latency and quality, which is precisely what this backend
 * exists to buy back — converting 44.1 to 48 kHz to paper over a mismatch would give away more than
 * the whole move from Java Sound gained. So when there is no overlap the honest answer is to say
 * which device wants what, not to quietly fix it.
 *
 * Single-device backends (ASIO, and JACK later) never reach this: one driver owns both directions,
 * so there is one rate and nothing to reconcile.
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
     * Which of [available] to select by default.
     *
     * The endpoints' own preferences come first, because those are not guesses: for ASIO it is the
     * rate set in the driver's control panel, and for WASAPI it is the rate Windows is configured
     * for. Honouring that means the picker opens on the value the user already chose elsewhere, and
     * it avoids forcing a rate switch the hardware would have to do at open.
     *
     * Only when neither preference survives the intersection does policy apply, and then the
     * highest common rate wins — the period shortens as the rate rises, and this program's whole
     * objective is the shortest period the hardware will hold.
     */
    fun recommended(available: List<Int>, inputPreferred: Int? = null, outputPreferred: Int? = null): Int? {
        if (available.isEmpty()) return null
        inputPreferred?.let { if (it in available) return it }
        outputPreferred?.let { if (it in available) return it }
        return available.max()
    }

    /**
     * The sentence shown when the two endpoints share no rate.
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
        "${describe(outputRates)}, so there is no rate they share. Choose a different pair — " +
        "Omega will not resample between them, because that would cost more latency than this " +
        "backend saves."

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
