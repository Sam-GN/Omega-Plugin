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

import org.example.dsp.EffectChain
import org.example.dsp.rhythm.Feels
import org.example.dsp.rhythm.Transport
import org.example.util.Log
import kotlin.math.abs
import kotlin.math.exp

/**
 * The callback body, and nothing else.
 *
 * Every device concern now lives behind [AudioBackend]: this class is handed a block of mono floats
 * and hands one back. That is what makes the same code correct on a 1.45 ms ASIO callback, a 3 ms
 * WASAPI-exclusive period and a 5.8 ms Java Sound block, and it is why `dsp/` needed no change at
 * all to reach low latency.
 *
 * The UI talks to it exactly as before: `Parameter.value` and `AudioProcessor.enabled` are plain
 * volatile fields, so a knob turn is one unsynchronised store picked up on the next block, and every
 * figure travelling the other way ([outputLevel], [loadPercent], [deadlineMisses] …) is a volatile
 * scalar polled at 30 Hz. No lock crosses this boundary in either direction; at a 1.45 ms deadline
 * one that was merely contended would be audible.
 */
class AudioEngine(
    private val chain: EffectChain,
    /**
     * The metronome and drum machine, mixed into the monitor path *after* the chain.
     *
     * Injected rather than constructed here so the headless tests can drive it directly, and taken
     * as a property rather than hidden because the UI owns every one of its controls.
     */
    val rhythm: Transport = Transport(),
    /**
     * The second board, fed the same input as [chain] and summed with it — or null where there is
     * only one board, which is every headless caller.
     *
     * Always prepared and warmed when present, whether or not [split] is on: split is switched while
     * the stream runs, and a board that was first prepared at that moment would be class loading
     * against the deadline. Its cost while off is nothing — it is not run at all once its fade has
     * finished.
     */
    private val second: EffectChain? = null,
    /**
     * What runs on the sum of the boards: the master. After the fork rather than before it, so that
     * a master is a master and not an input trim, and so that one knob covers both boards.
     */
    private val post: EffectChain? = null,
) : AudioCallback {

    private val chains: List<EffectChain> = listOfNotNull(chain, second, post)

    /**
     * Whether the second board is heard.
     *
     * One volatile store from the UI, like a pedal's switch. The audio thread does not step on it:
     * [blend] ramps the second board's share of the output over about [BLEND_SECONDS] in either
     * direction, so switching split on or off is a short fade rather than a click. Off, the second
     * board keeps being run until its fade has finished and is then left alone.
     */
    @Volatile
    var split = false

    /** The second board's share of the output, 0..1. Written by the audio thread, once per block. */
    @Volatile
    private var blend = 0f

    private var blendSmoothing = 1f - exp(-1f / (44_100f * BLEND_SECONDS))

    /**
     * Where the second board renders. Sized in [prepareChain]; the constructor's size is for callers
     * that never open a device, which is the headless tests.
     */
    private var scratch = FloatArray(DEFAULT_SCRATCH_FRAMES)

    /**
     * True while the second board is still being run: split on, or its fade-out not finished yet.
     *
     * What the UI consults before clearing the second board's state on the way *into* split mode —
     * a delay tail from the last time it was on is not part of the sound being switched to, and a
     * `reset` is only safe to call from another thread while the audio thread is not in `process`.
     */
    val secondRunning: Boolean get() = second != null && (split || blend > 0f)

    /**
     * Output meter, with real ballistics.
     *
     * A per-block peak polled at 30 Hz used to see one block in six; at 64 frames it would see one
     * in twenty-two and miss most transients outright — on a guitar, that is the interesting part of
     * the signal. So the audio thread keeps the value itself: instant attack, exponential release,
     * and a separate slow-decaying peak hold. Still two plain volatile floats to the UI.
     */
    @Volatile
    var outputLevel: Float = 0f
        private set

    @Volatile
    var outputPeakHold: Float = 0f
        private set

    /**
     * Where the output is being written, or null.
     *
     * Volatile and nothing more: the callback reads the reference once per block and copies into a
     * ring. Clearing this before calling [Recorder.stop] is what makes stopping safe — a callback
     * already holding the old reference writes into a ring nobody will drain, which harms nothing.
     */
    @Volatile
    var recorder: Recorder? = null

    /**
     * The optional second file, carrying the click and the kit alongside the guitar.
     *
     * Written *after* [rhythm] has mixed, where [recorder] is written before it, so one buffer
     * serves both: the dry take and the take with the rhythm in it differ only by which side of one
     * call they are read on.
     */
    @Volatile
    var rhythmRecorder: Recorder? = null

    /**
     * A take opened by the UI but not yet started, waiting for a downbeat.
     *
     * Opening the file is what has a disk behind it, so the UI does that up front and parks the
     * result here; the audio thread only swaps a reference. That is what lets a count-in put the
     * first sample of a take exactly on the beat without the callback ever touching a filesystem.
     */
    @Volatile
    var pendingRecorder: Recorder? = null

    @Volatile
    var pendingRhythmRecorder: Recorder? = null

    /** DSP time as a fraction of the callback period, 0..1+. The number that says whether a smaller buffer is available. */
    @Volatile
    var loadPercent: Float = 0f
        private set

    /** Callbacks whose body took longer than one period. Each one is a potential click. */
    @Volatile
    var deadlineMisses: Int = 0
        private set

    @Volatile
    var worstCallbackMicros: Int = 0
        private set

    /** Set by the callback when the chain threw; the monitor path turns it into a message. */
    @Volatile
    var faulted: String? = null
        private set

    @Volatile
    private var stream: AudioStream? = null

    @Volatile
    private var backend: AudioBackend? = null

    /** Invoked off the EDT when the stream dies unexpectedly. */
    var onError: ((String) -> Unit)? = null

    /** Invoked off the EDT when the driver changes the format under us (ASIO panel, JACK server). */
    var onFormatChanged: ((StreamFormat) -> Unit)? = null

    val isRunning: Boolean get() = stream != null

    val format: StreamFormat? get() = stream?.format

    val xruns: Int get() = stream?.xruns ?: 0

    /**
     * Frames the device actually got, against frames wall-clock time asked for.
     *
     * Below about 0.995 the device was starved and replayed stale audio, which is audible, and it
     * is the only counter that catches a collector pause — a pause stalls the callback thread
     * between callbacks, so no callback body is ever slow and [deadlineMisses] stays at zero.
     */
    val realtimeRatio: Float get() = stream?.realtimeRatio ?: 1f

    /** See [AudioStream.schedulingNote]. Null on Windows, where there is nothing new to say. */
    val schedulingNote: String? get() = stream?.schedulingNote

    val backendId: BackendId? get() = backend?.id

    /**
     * The driver's own round-trip claim, in milliseconds.
     *
     * Attributed rather than asserted: it includes the driver's safety offsets and excludes converter
     * latency, so it is the best number available without a patch lead between output and input.
     * `--measure` is what produces the other kind.
     */
    val reportedLatencyMs: Float get() = stream?.format?.reportedRoundTripMs ?: 0f

    private var releaseCoefficient = 0.9f
    private var holdCoefficient = 0.999f
    private var periodNanos = 1L

    /**
     * Where the pause behind the Start button actually went, in milliseconds.
     *
     * Worth recording rather than guessing at, because this is the one delay the user sees and
     * cannot explain: the window is unresponsive throughout, since [start] runs on the caller's
     * thread and the caller is the event thread. Which phase dominates decides whether that is worth
     * doing anything about, and the answer is not obvious — a driver can sit in `createBuffers` for
     * longer than the whole warm-up.
     */
    class StartTiming(
        val openMillis: Long,
        val prepareMillis: Long,
        val warmUpChainMillis: Long,
        val warmUpReserveMillis: Long,
        val startMillis: Long,
    ) {
        val totalMillis: Long
            get() = openMillis + prepareMillis + warmUpChainMillis + warmUpReserveMillis + startMillis

        override fun toString(): String =
            "$totalMillis ms total: open $openMillis, prepare $prepareMillis, " +
                "warm-up $warmUpChainMillis, reserve warm-up $warmUpReserveMillis, start $startMillis"
    }

    @Volatile
    var lastStartTiming: StartTiming? = null
        private set

    /**
     * Progress through [start], for a caller that has moved it off its own thread.
     *
     * Called on the **opening** thread, never the audio thread — by the time the audio thread
     * exists, [start] has already finished. A UI listening to this must therefore marshal onto its
     * own thread rather than touching components here.
     *
     * `fraction` is 0..1 where there is something real to count, and **negative where there is not**.
     * That distinction is the honest part: the warm-up is a loop with a known trip count, but a
     * driver open is a handful of calls of unpredictable length, and a percentage across it would be
     * invented. Callers should render a negative fraction as an unquantified stage.
     */
    @Volatile
    var onStartProgress: ((stage: String, fraction: Float) -> Unit)? = null

    private fun report(stage: String, fraction: Float) {
        runCatching { onStartProgress?.invoke(stage, fraction) }
    }

    /** Split out of the warm-up total, because it is the half that arrived with the FX slots. */
    private var warmUpReserveMillis = 0L

    @Synchronized
    fun start(backend: AudioBackend, request: StreamRequest) {
        if (stream != null) return

        val events = object : StreamEvents {
            override fun onFormatChanged(format: StreamFormat) {
                prepareChain(format)
                this@AudioEngine.onFormatChanged?.invoke(format)
            }

            override fun onDeviceLost(reason: String) {
                onError?.invoke(reason)
            }

            override fun onFatal(reason: String) {
                onError?.invoke(reason)
            }
        }

        report(OPENING, -1f)
        val beforeOpen = System.nanoTime()
        val opened = backend.open(request, this, events)
        val afterOpen = System.nanoTime()
        try {
            report(PREPARING, -1f)
            prepareChain(opened.format)
            val afterPrepare = System.nanoTime()
            warmUp(opened.format)
            val afterWarmUp = System.nanoTime()
            report(STARTING, -1f)
            deadlineMisses = 0
            worstCallbackMicros = 0
            faulted = null
            outputLevel = 0f
            outputPeakHold = 0f
            this.backend = backend
            this.stream = opened
            opened.start()
            val afterStart = System.nanoTime()

            lastStartTiming = StartTiming(
                openMillis = millisBetween(beforeOpen, afterOpen),
                prepareMillis = millisBetween(afterOpen, afterPrepare),
                warmUpChainMillis = millisBetween(afterPrepare, afterWarmUp) - warmUpReserveMillis,
                warmUpReserveMillis = warmUpReserveMillis,
                startMillis = millisBetween(afterWarmUp, afterStart),
            ).also { Log.info("audio", "Start took $it") }
        } catch (t: Throwable) {
            this.stream = null
            this.backend = null
            runCatching { opened.close() }
            throw t
        }
    }

    @Synchronized
    fun stop() {
        val s = stream ?: return
        stream = null
        backend = null
        runCatching { s.stop() }
        runCatching { s.close() }
        // The transport has no way to be heard without a stream, and leaving it "playing" behind a
        // stopped device would show a running PLAY button that produces nothing.
        rhythm.stop()
        outputLevel = 0f
        outputPeakHold = 0f
        loadPercent = 0f
    }

    /**
     * Binds the chain to the rate and block size the driver actually gave us.
     *
     * This is the reason the 44.1 kHz constants had to come out of `dsp/`: an ASIO device may hand
     * back 44 100 or 96 000 and the chain has to be right either way. It runs on the opening thread,
     * never on the audio thread — every allocation an effect needs happens here.
     */
    private fun prepareChain(format: StreamFormat) {
        for (c in chains) {
            c.prepare(format.sampleRate.toFloat(), format.bufferFrames)
            c.reset()
        }
        if (scratch.size < format.bufferFrames) scratch = FloatArray(format.bufferFrames)
        blendSmoothing = 1f - exp(-1f / (format.sampleRate * BLEND_SECONDS))
        // A fresh stream starts the second board from silence and fades it in if split is on, the
        // same way it would have arrived had the switch been pressed after Start.
        blend = 0f
        // Same rule as every effect: nothing in the rhythm section may derive a coefficient from a
        // hardcoded 44 100, so its tick oscillators and envelopes are rebuilt at the rate the driver
        // actually granted.
        rhythm.prepare(format.sampleRate.toFloat())
        periodNanos = format.bufferFrames * 1_000_000_000L / format.sampleRate
        // ~150 ms release and ~1.5 s peak hold, expressed per callback so the ballistics stay the
        // same whether a callback is 1.45 ms or 5.8 ms.
        val periodSeconds = format.bufferFrames.toFloat() / format.sampleRate
        releaseCoefficient = exp(-periodSeconds / 0.15f)
        holdCoefficient = exp(-periodSeconds / 1.5f)
    }

    /**
     * Runs the whole chain over silence before the stream starts. Not an optimisation — mandatory.
     *
     * The first call into a not-yet-loaded class does file I/O, and the first few thousand
     * iterations of every hot loop run interpreted. Against a 1.45 ms deadline that means the app
     * would xrun on start every single time, which users read as "it's broken" rather than as "it
     * warms up". A few tens of milliseconds here, hidden behind the Start button, buys that.
     *
     * **The full pass is owed once per class, not once per start.** Class loading and JIT
     * compilation are per class and last as long as the process, so the second start of a session
     * was repeating work that was already done — measured at 236 ms of a 256 ms pause, almost all of
     * it a NAM capture. Once every class in the chain has been through it, a short pass is enough:
     * it first-touches the buffers `prepare` has just allocated and re-enters any loop the collector
     * deoptimised, at a tenth of the cost.
     *
     * The set of warmed classes is static, because that is the scope the property actually has —
     * a second [AudioEngine] in the same process inherits a hot JIT whether it knows it or not.
     */
    private fun warmUp(format: StreamFormat) {
        val frames = format.bufferFrames
        val input = FloatArray(frames)
        val output = FloatArray(frames)
        // Every chain, not just the first: the second board is reached by a switch pressed while
        // the stream runs, and the master now sits in a chain of its own after the sum.
        val effects = chains.flatMap { it.effects.asList() }
        val wasEnabled = effects.map { it.enabled }
        val reserves = effects.flatMap { it.alternates }.distinctBy { it.javaClass }
        // Transport is in the set because it runs on the audio thread and is not in the chain, so
        // nothing else here would ever record it as warmed.
        val classes = effects.map { it.javaClass } + reserves.map { it.javaClass } +
            Transport::class.java

        val cold = !warmedClasses.containsAll(classes)
        val blocks = if (cold) WARMUP_BLOCKS else REWARM_BLOCKS
        // Twenty updates over the whole pass. Reporting per block would post hundreds of events at a
        // UI that can only paint sixty times a second.
        val step = (blocks / 20).coerceAtLeast(1)

        // **The pass runs at a fixed small block, whatever the device asked for.** Warm-up is about
        // executing code — JIT compiles methods and loop bodies, and neither knows how many frames a
        // call carried — so tying the amount of work to the device's block size bought nothing and
        // cost everything: it made the total `WARMUP_BLOCKS × bufferFrames` samples, which is linear
        // in the buffer. Measured with a NAM capture in the chain: 236 ms at 64 frames and **7286 ms
        // at 2048**, for identical benefit. Java Sound's 7056-frame line would have been far worse.
        //
        // 64 is deliberate rather than arbitrary — it is the block the deadline work was done at, so
        // small-buffer behaviour, which is the case that actually needs the warm-up, is unchanged.
        val warmFrames = minOf(frames, WARMUP_FRAMES)

        try {
            effects.forEach { it.enabled = true }
            // A little noise rather than pure silence, so branches that depend on signal level get
            // profiled too and denormal-flushing paths are exercised.
            for (i in input.indices) input[i] = ((i * 2654435761L.toInt()) shr 8) * 1e-9f
            repeat(blocks) { block ->
                for (c in chains) {
                    System.arraycopy(input, 0, output, 0, warmFrames)
                    c.process(output, warmFrames)
                }
                if (block % step == 0) report(WARMING, CHAIN_SHARE * block / blocks)
            }

            // Then a few at the size the device will actually use, so the buffers `prepare` has just
            // allocated are first-touched at full length and any block-size-dependent branch is
            // taken at least once before the deadline is real. Cheap: a handful of blocks, not four
            // hundred.
            repeat(FULL_SIZE_BLOCKS) {
                for (c in chains) {
                    System.arraycopy(input, 0, output, 0, frames)
                    c.process(output, frames)
                }
            }

            // An FX slot runs one of its types and holds the rest in reserve, so the pass above has
            // just warmed the selected one and left every other type cold — and those are reached by
            // changing a dropdown, which happens *while the stream runs*. Warming them here is what
            // keeps that from being class loading and interpreted code against the deadline.
            //
            // Deduplicated by class, because that is the granularity the work actually has: JIT
            // compilation and class loading are per class, so a second slot holding the same six
            // types has already been paid for by the first.
            val beforeReserves = System.nanoTime()
            repeat(blocks) { block ->
                for (effect in reserves) {
                    System.arraycopy(input, 0, output, 0, warmFrames)
                    effect.process(output, warmFrames)
                }
                if (block % step == 0) {
                    report(WARMING, CHAIN_SHARE + (1f - CHAIN_SHARE) * block / blocks)
                }
            }
            reserves.forEach { it.reset() }
            warmUpReserveMillis = millisBetween(beforeReserves, System.nanoTime())

            warmUpRhythm(input, output, warmFrames, blocks)
            // Only on a pass that completed. A warm-up that threw has not proved anything about the
            // classes it never reached, and claiming otherwise would skip the retry it needs.
            warmedClasses.addAll(classes)
            report(WARMING, 1f)
        } catch (_: Throwable) {
            // A chain that throws during warm-up will throw in the callback too, but the callback
            // handles it; failing the whole start here would be worse.
        } finally {
            effects.forEachIndexed { i, effect -> effect.enabled = wasEnabled[i] }
            chains.forEach { it.reset() }
        }
    }

    /**
     * Warms the rhythm section over **every** pattern in the catalogue.
     *
     * Every pattern, not just the selected one, for the same reason an `FxSlot`'s unselected types
     * are warmed: FEEL is a dropdown, and a dropdown is changed while the stream is running. A
     * pattern first reached that way would class-load `Feels`, build its tables and run
     * `Transport.mix` interpreted, all against the deadline, at the exact moment the user is
     * listening for the beat.
     *
     * Touching `Feels.all()` also forces that object's initialiser here rather than on the audio
     * thread. It builds every step table in the catalogue, which is allocation the callback must
     * never see.
     *
     * The transport is left stopped and reset, so a warm-up cannot leave the metronome running
     * behind a Start button the user has not pressed yet.
     */
    private fun warmUpRhythm(input: FloatArray, output: FloatArray, frames: Int, blocks: Int) {
        val patterns = Feels.all()
        val restoreSignature = rhythm.signature
        val restorePattern = rhythm.pattern
        val restoreLevel = rhythm.level
        val restoreTempo = rhythm.tempoBpm
        try {
            // Silent, so a warm-up cannot be heard: the pass is over a scratch buffer, but the
            // level is the one control that would survive into the first real block if the restore
            // below were ever skipped.
            rhythm.level = 0f
            // Fast enough that a short pass still crosses many bar lines and exercises the downbeat
            // branch, the count-in comparison and the pattern-change path.
            rhythm.tempoBpm = Transport.MAX_BPM
            for (pattern in patterns) {
                rhythm.signature = pattern.signature
                rhythm.pattern = pattern
                rhythm.start()
                repeat(blocks / patterns.size + 1) {
                    System.arraycopy(input, 0, output, 0, frames)
                    rhythm.mix(output, frames)
                }
            }
        } finally {
            rhythm.stop()
            rhythm.signature = restoreSignature
            rhythm.pattern = restorePattern
            rhythm.level = restoreLevel
            rhythm.tempoBpm = restoreTempo
            rhythm.reset()
        }
    }

    /**
     * The realtime path.
     *
     * Forbidden here, and all of it for a reason: allocation of any kind (it is what triggers the
     * collection, not what costs the time), locks, sleeping, I/O of any kind including `println`,
     * class loading, reflection, and lazy initialisation. `System.nanoTime` is allowed and needed —
     * it is a QPC read at about 20 ns and the load meter is built on it.
     */
    override fun render(input: FloatArray, output: FloatArray, frames: Int) {
        val t0 = System.nanoTime()
        var peak = 0f

        // A downbeat reached during the *previous* block's mix starts the take now. That is up to
        // one block late — 1.45 ms at 64 frames — because the rhythm is mixed after the recorder is
        // written and the flag therefore cannot be acted on until the next call. Splitting the
        // transport's clock away from its mixer would remove the block of slop and would cost a
        // second pass over the schedule to save something an order of magnitude below one tick of
        // the beat it is aligning to.
        if (rhythm.consumeRecordDue()) {
            recorder = pendingRecorder
            rhythmRecorder = pendingRhythmRecorder
            pendingRecorder = null
            pendingRhythmRecorder = null
        }

        try {
            System.arraycopy(input, 0, output, 0, frames)
            chain.process(output, frames)
            // The second board gets the same input, renders into its own buffer and is added to the
            // first. Run while split is on and for as long afterwards as its fade-out takes; after
            // that it costs nothing. The size check is the one guard a headless caller needs.
            val b = second
            if (b != null && (split || blend > 0f) && scratch.size >= frames) {
                System.arraycopy(input, 0, scratch, 0, frames)
                b.process(scratch, frames)
                mixSecond(output, frames)
            }
            // The master, on the sum. After the fork, so one knob governs both boards and turning
            // it down changes how loud the sound is rather than how hard the drives are hit.
            post?.process(output, frames)
            // Recorded *before* the clamp. The file is 32-bit float, which has headroom above
            // 0 dBFS, so a take driven into the clip keeps its peaks and can be pulled back down
            // later — capturing after the clamp would record the damage instead.
            recorder?.write(output, frames)
            // After the chain, so the click does not arrive at the listener through the overdrive
            // and the reverb; before the clamp, so the twin gets the same headroom as the dry take.
            rhythm.mix(output, frames)
            rhythmRecorder?.write(output, frames)
            for (i in 0 until frames) {
                val v = output[i]
                val clamped = if (v > 1f) 1f else if (v < -1f) -1f else v
                output[i] = clamped
                val level = abs(clamped)
                if (level > peak) peak = level
            }
        } catch (t: Throwable) {
            // An exception escaping into JNI is not well defined. Silence, flag, keep the stream up.
            java.util.Arrays.fill(output, 0, frames, 0f)
            // The silence goes in the file too. A block that is simply missing would shorten the
            // recording relative to the performance, and everything after it would be early.
            //
            // Both files, and that matters more than it looks: the twin exists to line up with the
            // dry take, and a fault that shortened one of them by a block would put every sample
            // after it out of step with the other for the rest of the recording.
            recorder?.write(output, frames)
            rhythmRecorder?.write(output, frames)
            if (faulted == null) faulted = t.javaClass.simpleName
            peak = 0f
        }

        // Instant attack, exponential release; the hold decays slower still.
        val level = outputLevel * releaseCoefficient
        outputLevel = if (peak > level) peak else level
        val held = outputPeakHold * holdCoefficient
        outputPeakHold = if (peak > held) peak else held

        val spent = System.nanoTime() - t0
        if (spent > periodNanos) deadlineMisses++
        val micros = (spent / 1_000L).toInt()
        if (micros > worstCallbackMicros) worstCallbackMicros = micros
        loadPercent = spent.toFloat() / periodNanos
    }

    /**
     * Adds the second board's buffer into [output] at its current share, ramping that share towards
     * where [split] says it should be.
     *
     * The same exponential approach [org.example.dsp.VolumeStage] uses, snapped at the same
     * distance, so the common case — split on and settled — is a plain add with no multiply, and
     * split off reaches an exact zero after which the board stops being run at all.
     */
    private fun mixSecond(output: FloatArray, frames: Int) {
        val target = if (split) 1f else 0f
        var g = blend
        if (abs(target - g) < BLEND_SNAP) g = target
        val bus = scratch
        if (g == target) {
            if (g == 1f) for (i in 0 until frames) output[i] += bus[i]
        } else {
            val s = blendSmoothing
            for (i in 0 until frames) {
                g += (target - g) * s
                output[i] += g * bus[i]
            }
        }
        blend = g
    }

    private companion object {
        /** How long switching split on or off takes to fade: the master's own mute time. */
        const val BLEND_SECONDS = 500f / 44_100f

        /** -80 dB of the remaining distance, at which the blend is called arrived. */
        const val BLEND_SNAP = 1e-4f

        /** Room for the second board before a device has said how big a block is. */
        const val DEFAULT_SCRATCH_FRAMES = 8192

        /**
         * Enough to get every hot method through C1 into C2 — the tiered compilation threshold for
         * C2 is 10 000 invocations, and each block is thousands of loop iterations.
         */
        const val WARMUP_BLOCKS = 400

        /**
         * The pass once the process is hot. Enough to first-touch freshly allocated buffers and
         * re-enter every loop; not a substitute for the full one, which is why it is only ever
         * reached after a complete pass has been recorded in [warmedClasses].
         */
        const val REWARM_BLOCKS = 40

        /**
         * Block size the warm-up runs at, whatever the device's is.
         *
         * 64 frames because that is the buffer the deadline work was done at — see the note in
         * `warmUp`. Larger devices get the same amount of warming rather than proportionally more.
         */
        const val WARMUP_FRAMES = 64

        /** Blocks at the device's real size, to first-touch what `prepare` allocated. */
        const val FULL_SIZE_BLOCKS = 4

        /**
         * Every class this process has already put through a full warm-up.
         *
         * Static because JIT compilation is a property of the process, not of an engine instance,
         * and synchronised because nothing promises two engines are not opening at once.
         */
        private val warmedClasses: MutableSet<Class<*>> =
            java.util.Collections.synchronizedSet(HashSet())

        // Stage names for onStartProgress. Held here so the strings a UI matches on have one home.
        const val OPENING = "Opening the device"
        const val PREPARING = "Preparing the chain"
        const val WARMING = "Warming up"
        const val STARTING = "Starting the stream"

        /**
         * How much of the warm-up bar the chain pass gets, the reserves taking the rest.
         *
         * An estimate, and unavoidably one: the split depends on what is loaded. Measured at 236 ms
         * against 6 ms with a NAM capture in the chain and 14 against 7 without, so weighting it
         * heavily towards the chain is right for the case where the wait is long enough to watch.
         */
        const val CHAIN_SHARE = 0.9f

        fun millisBetween(from: Long, to: Long): Long = (to - from) / 1_000_000L
    }
}
