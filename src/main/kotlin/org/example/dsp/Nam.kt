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

import org.example.dsp.nam.NamFile
import org.example.dsp.nam.WaveNet
import kotlin.math.abs

/**
 * Neural Amp Modeler — a captured amplifier, run as a neural network.
 *
 * With nothing loaded it passes audio through, so switching it on before choosing a model is silent
 * rather than surprising.
 *
 * **This is the expensive module.** A standard NAM capture is on the order of thirteen thousand
 * multiply-adds per sample, which is roughly a hundred times the rest of the chain put together.
 * [operationsPerSample] reports what the loaded model actually costs so the UI can say so, because
 * the honest answer to "will this run" depends entirely on which model you loaded.
 *
 * **[size] is the answer to that cost.** A slimmable `.nam` carries the same amp captured at several
 * widths — 3 channels and 8 channels on the files this was built against, which is roughly 1 800
 * against 11 800 multiply-adds a sample. The knob picks between them by the reference's own rule:
 * the first submodel whose `max_value` the setting falls below, and the largest when none does, so
 * 1.0 always means the full-size capture. On a file carrying one model it does nothing.
 *
 * Loading happens off the audio thread; the network is published through a volatile field so the
 * audio thread only ever sees a complete one.
 */
class Nam : AudioProcessor {

    override val name = "NAM"

    @Volatile
    override var enabled = false

    val size = Parameter("Size", 0f, 1f, 1f)
    val level = Parameter("Level", -24f, 24f, 0f, "dB")
    val mix = Parameter("Mix", 0f, 1f, 1f)

    override val parameters = listOf(size, level, mix)

    @Volatile
    private var network: WaveNet? = null

    @Volatile
    private var submodels: Array<NamFile.Submodel> = emptyArray()

    /** Which submodel [network] currently holds, and the [size] that chose it. */
    @Volatile
    private var activeIndex = -1

    @Volatile
    private var appliedSize = Float.NaN

    /** How many widths the loaded file carries. One means [size] has nothing to choose between. */
    val submodelCount: Int get() = submodels.size

    /**
     * Gain that brings the active capture to [TARGET_LOUDNESS], or 1 when the file does not say how
     * loud it is.
     *
     * Captures are not level-matched to anything. The models this was built against measure around
     * **-43 dB**, so playing one back raw is very nearly silent — and no trim control with a usable
     * range could fix that, which is why NAM records `metadata.loudness` and why every host that
     * plays these files applies it. Doing it here means loading a model sounds like the amplifier
     * rather than like a fault, and leaves [level] to do what a trim should.
     */
    @Volatile
    var normalization = 1f
        private set

    /** Name of the loaded model, for the UI. Null when there is none. */
    @Volatile
    var modelName: String? = null
        private set

    /** Rate the model was captured at, for warning when it does not match the device. */
    @Volatile
    var modelRate: Float = 0f
        private set

    /**
     * Whether a capture is run at its own rate rather than the device's.
     *
     * A NAM model has no clock: it assumes each sample it is handed is one period of the rate it was
     * trained at. Feed a 48 kHz capture at 44.1 kHz and it is wrong about how fast time passes by
     * 8 %, so every frequency-dependent feature — where it breaks up, the presence peak, the top-end
     * roll-off — lands about 8 % low, and the amp sounds darker than it is.
     *
     * On by default, because that shift is a defect and the fix should not have to be found. It is
     * switchable because the correction is not free: it costs about [rateLatencyFrames] frames of
     * delay, and whether that trade is worth it is a judgement about the room, the buffer size and
     * the player — not one this code can make.
     */
    @Volatile
    var matchRates = true
        private set

    /** True when [matchRates] would actually do something: a model is loaded and the rates differ. */
    val rateMismatch: Boolean
        get() = modelRate > 0f && abs(modelRate - deviceRate) > 0.5f

    /** What the conversion is adding, in device frames. Zero when it is not running. */
    val rateLatencyFrames: Int get() = adapter?.latencyFrames ?: 0

    @Volatile
    private var adapter: RateAdapter? = null

    private var deviceRate = DEFAULT_SAMPLE_RATE

    /**
     * Starts at a plausible block size rather than zero.
     *
     * [prepare] is what knows the real one, and it does not run until the stream opens — so a
     * converter built only from there does not exist while the user is choosing a capture, and
     * [rateLatencyFrames] would report zero for a cost that is really there. Building one
     * speculatively costs a few kilobytes and makes the figure honest at the moment it is read.
     */
    private var maxBlock = DEFAULT_MAX_BLOCK

    /** Multiply-adds per sample of the loaded model, or zero when there is none. */
    val operationsPerSample: Int get() = network?.operationsPerSample ?: 0

    private var scratch = FloatArray(0)

    fun load(loaded: NamFile.Loaded) {
        val models = loaded.submodels.toTypedArray()
        for (model in models) warmUp(model.network)

        // Publish the full-size model first so a block landing mid-load sees a usable network, then
        // let the audio thread narrow it to whatever the knob asks for.
        val full = models.size - 1
        normalization = gainFor(models[full])
        network = models[full].network
        submodels = models
        activeIndex = full
        appliedSize = Float.NaN

        modelName = loaded.name
        modelRate = loaded.sampleRate
        // The rate is only known now, and prepare() may have run long before any model existed, so
        // the converter has to be (re)built from both directions.
        rebuildAdapter()
    }

    /** Turns rate matching on or off. Off the audio thread — it rebuilds the converter. */
    fun setMatchRates(on: Boolean) {
        if (matchRates == on) return
        matchRates = on
        rebuildAdapter()
    }

    private fun rebuildAdapter() {
        adapter = if (matchRates && rateMismatch && maxBlock > 0) {
            RateAdapter(deviceRate, modelRate, maxBlock)
        } else {
            null
        }
    }

    fun clear() {
        network = null
        submodels = emptyArray()
        activeIndex = -1
        appliedSize = Float.NaN
        normalization = 1f
        modelName = null
        modelRate = 0f
        rebuildAdapter()
    }

    /**
     * Runs a freshly built network until the JIT has compiled it, on whichever thread loaded it.
     *
     * **This is not an optimisation; without it the audio breaks up every time a model is loaded
     * mid-stream.** A network that has never executed runs interpreted, and measured on the models
     * this was built against that is **0.1x realtime against 2.5x compiled — a 25-fold penalty, or
     * fifteen cores' worth of CPU where there is one**. Every callback in that window misses its
     * deadline. On a two-clock backend the damage outlives the stall: the ring between capture and
     * render settles at a deeper fill and stays there, so the latency is permanently worse until the
     * stream is restarted.
     *
     * [org.example.audio.AudioEngine.warmUp] does the same thing for the rest of the chain before
     * the stream starts, and cannot help here — it runs at stream start, and until a model is loaded
     * [process] returns before ever reaching the network, so none of this code is on the path to be
     * warmed.
     *
     * Fresh noise each block rather than the network's own output, which would drift into silence or
     * saturation and stop exercising the arithmetic. Bounded by time as well as by block count,
     * because it is the interpreted passes that are slow and a large model must not lock the caller
     * up for seconds. Reset afterwards so the model still starts from silence.
     */
    private fun warmUp(network: WaveNet) {
        val block = FloatArray(WARM_UP_FRAMES)
        var seed = 0x9E3779B97F4A7C15uL
        val deadline = System.nanoTime() + WARM_UP_LIMIT_NANOS
        var pass = 0
        while (pass < WARM_UP_BLOCKS) {
            for (i in block.indices) {
                seed = seed * 6364136223846793005uL + 1442695040888963407uL
                block[i] = (((seed shr 41).toInt() and 0x7FFFFF) / 8_388_608f - 0.5f) * 0.25f
            }
            network.process(block, block.size)
            pass++
            // Checked after a minimum, so a slow machine still gets enough passes to compile.
            if (pass >= WARM_UP_MINIMUM && System.nanoTime() > deadline) break
        }
        network.reset()
    }

    private fun gainFor(model: NamFile.Submodel): Float {
        val loudness = model.loudness ?: return 1f
        return 10f.pow((TARGET_LOUDNESS - loudness) / 20f)
    }

    /**
     * Points [network] at the submodel [size] asks for.
     *
     * Called once per block rather than per sample, and it does nothing at all in the common case
     * where the knob has not moved. When it does move, the incoming model is cleared **before** it
     * is published — the reference does the same — so the switch starts from silence instead of
     * from whatever state that model was left in the last time it ran. Clearing is a run of
     * `Arrays.fill` over already-allocated buffers: it takes tens of microseconds and allocates
     * nothing, which is what makes it safe to do here rather than needing a thread of its own.
     */
    private fun select() {
        val models = submodels
        if (models.isEmpty()) return

        val wanted = size.value
        if (wanted == appliedSize) return
        appliedSize = wanted

        var index = models.size - 1
        for (i in models.indices) {
            if (wanted < models[i].maxValue) {
                index = i
                break
            }
        }
        if (index == activeIndex) return

        val chosen = models[index]
        chosen.network.reset()
        activeIndex = index
        // Each width measured its own loudness, so re-deriving the gain here keeps the level
        // matched across a size change instead of stepping by the difference between them.
        normalization = gainFor(chosen)
        network = chosen.network
    }

    companion object {
        /**
         * What a capture is normalised to, in dB — the same target the reference plugin uses.
         *
         * It is not arbitrary: it leaves enough headroom for a hot capture to stay well clear of
         * clipping, while putting a typical one close enough to unity that the rest of the chain
         * behaves the same whether the module is in or out.
         */
        const val TARGET_LOUDNESS = -18f

        /** Block size the rate converter is sized for until [prepare] says otherwise. */
        private const val DEFAULT_MAX_BLOCK = 1024

        /**
         * The warm-up pass. See [Nam.warmUp] for why it is mandatory.
         *
         * 64-frame blocks so the hot methods are entered as often per pass as they will be in the
         * callback — it is the invocation count that triggers compilation, not the elapsed audio.
         * The minimum is what gets a model through the interpreted phase; the limit stops a very
         * large one from locking up the caller.
         */
        private const val WARM_UP_FRAMES = 64
        private const val WARM_UP_BLOCKS = 400
        private const val WARM_UP_MINIMUM = 120
        private const val WARM_UP_LIMIT_NANOS = 900_000_000L
    }

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        // The dry path needs a copy of the input, because the network overwrites in place.
        if (scratch.size < maxBlockFrames) scratch = FloatArray(maxBlockFrames)
        deviceRate = sampleRate
        maxBlock = maxBlockFrames
        rebuildAdapter()
    }

    override fun process(buffer: FloatArray, count: Int) {
        select()
        val model = network ?: return // nothing loaded: pass through

        val trim = 10f.pow(level.value / 20f)
        val mixed = mix.value
        // Normalisation is a property of the capture, so it belongs to the wet path alone. Applying
        // it to the dry signal as well would turn Mix into a second volume control, and the two
        // paths would no longer meet at the same level when Mix is halfway.
        val wet = mixed * normalization * trim
        val dry = (1f - mixed) * trim

        if (dry > 0f) {
            if (scratch.size < count) scratch = FloatArray(count)
            System.arraycopy(buffer, 0, scratch, 0, count)
        }

        // At the model's own rate when the converter is in, at the device's when it is not. The
        // null case is the whole fast path: rates agreeing costs nothing, not even a branch per
        // sample.
        val rates = adapter
        if (rates == null) {
            model.process(buffer, count)
        } else {
            val inner = rates.toInner(buffer, count)
            model.process(rates.innerBuffer, inner)
            rates.fromInner(inner, buffer, count)
        }

        if (dry > 0f) {
            for (i in 0 until count) buffer[i] = buffer[i] * wet + scratch[i] * dry
        } else {
            for (i in 0 until count) buffer[i] *= wet
        }
    }

    override fun reset() {
        network?.reset()
        adapter?.reset()
    }
}

private fun Float.pow(exponent: Float): Float = Math.pow(this.toDouble(), exponent.toDouble()).toFloat()
