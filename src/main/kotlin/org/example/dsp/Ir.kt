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
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Impulse response convolution — a speaker cabinet, in practice.
 *
 * Direct convolution rather than partitioned FFT. At the cap of [MAX_TAPS] that is 2048 multiply-adds
 * per sample, which the measured headroom absorbs comfortably, and it avoids an FFT implementation
 * and the latency a partitioned scheme adds. The cap is 46 ms at 44.1 kHz: a cabinet IR is usually
 * longer than that, but everything past the first few tens of milliseconds is room tail, and cutting
 * it costs far less than the reverb already in the chain provides.
 *
 * With nothing loaded the module passes audio straight through, so switching it on before choosing a
 * cabinet is silent rather than surprising.
 *
 * Loading happens off the audio thread. The taps and their history are therefore built completely
 * and then published as one object through a volatile field: the audio thread reads that reference
 * once per block and works from it, so it can never see a new impulse against an old history.
 */
class Ir : AudioProcessor {

    override val name = "IR"

    @Volatile
    override var enabled = false

    val mix = Parameter("Mix", 0f, 1f, 1f)
    val level = Parameter("Level", -24f, 24f, 0f, "dB")

    override val parameters = listOf(mix, level)

    /** The file's own samples, kept so [prepare] can re-derive taps when the device rate changes. */
    private var source: FloatArray? = null
    private var sourceRate = 0f
    private var sampleRate = DEFAULT_SAMPLE_RATE

    @Volatile
    private var kernel: Kernel? = null

    /** Name of the loaded impulse, for the UI. Null when there is none. */
    @Volatile
    var impulseName: String? = null
        private set

    /** Replaces the impulse. Call off the audio thread. */
    fun loadImpulse(samples: FloatArray, rate: Float, name: String) {
        source = samples
        sourceRate = rate
        impulseName = name
        rebuild()
    }

    fun clearImpulse() {
        source = null
        impulseName = null
        kernel = null
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        if (this.sampleRate == sampleRate) return
        this.sampleRate = sampleRate
        rebuild()
    }

    override fun process(buffer: FloatArray, count: Int) {
        val current = kernel ?: return // nothing loaded: pass through
        val taps = current.taps
        val history = current.history
        val length = taps.size

        val wet = mix.value
        val dry = 1f - wet
        val gain = 10f.pow(level.value / 20f)
        var write = current.index

        for (i in 0 until count) {
            val x = buffer[i]
            // Written twice, so the tap loop below reads a contiguous descending run and never has
            // to test for the wrap.
            history[write] = x
            history[write + length] = x

            var sum = 0f
            val base = write + length
            for (tap in 0 until length) sum += taps[tap] * history[base - tap]

            write++
            if (write >= length) write = 0
            buffer[i] = (x * dry + sum * wet) * gain
        }
        current.index = write
    }

    override fun reset() {
        kernel?.let {
            it.history.fill(0f)
            it.index = 0
        }
    }

    private fun rebuild() {
        val samples = source
        if (samples == null || samples.isEmpty()) {
            kernel = null
            return
        }
        val resampled = resample(samples, sourceRate, sampleRate)
        val taps = if (resampled.size > MAX_TAPS) resampled.copyOf(MAX_TAPS) else resampled
        normalise(taps)
        kernel = Kernel(taps, FloatArray(taps.size * 2))
    }

    /** Linear interpolation. Good enough for an impulse; anything better is inaudible here. */
    private fun resample(samples: FloatArray, from: Float, to: Float): FloatArray {
        if (from <= 0f || abs(from - to) < 0.5f) return samples.copyOf()
        val step = from / to
        val length = (samples.size / step).toInt().coerceAtLeast(1)
        val out = FloatArray(length)
        for (i in out.indices) {
            val position = i * step
            val index = position.toInt()
            val fraction = position - index
            val a = samples.getOrElse(index) { 0f }
            val b = samples.getOrElse(index + 1) { 0f }
            out[i] = a + (b - a) * fraction
        }
        return out
    }

    /**
     * Scales the impulse to unit energy.
     *
     * Convolution sums every tap, so an unnormalised impulse changes the level by however much
     * energy it happens to contain — swapping cabinets would be a volume change first and a tone
     * change second. Unit energy keeps loudness roughly constant across impulses and leaves [level]
     * to do the trimming.
     */
    private fun normalise(taps: FloatArray) {
        var energy = 0f
        for (tap in taps) energy += tap * tap
        val norm = sqrt(energy)
        if (norm < 1e-9f) return
        for (i in taps.indices) taps[i] /= norm
    }

    /** Taps and their history, replaced as a unit so the audio thread never sees a half-swap. */
    private class Kernel(val taps: FloatArray, val history: FloatArray) {
        @Volatile
        var index = 0
    }

    companion object {
        /** 46 ms at 44.1 kHz — past this an impulse is room, and the reverb does rooms. */
        const val MAX_TAPS = 2048
    }
}
