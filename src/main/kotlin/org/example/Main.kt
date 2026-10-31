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
package org.example

import org.example.audio.AudioBackend
import org.example.audio.AudioEngine
import org.example.audio.BackendDevice
import org.example.audio.Backends
import org.example.audio.LoopbackProbe
import org.example.audio.Pairing
import org.example.audio.SampleRates
import org.example.audio.StreamRequest
import org.example.dsp.DEFAULT_SAMPLE_RATE
import org.example.dsp.EffectChain
import org.example.dsp.Nam
import org.example.dsp.nam.Activation
import org.example.dsp.nam.Gating
import org.example.dsp.nam.LayerArrayConfig
import org.example.dsp.nam.NamException
import org.example.dsp.nam.NamFile
import org.example.dsp.nam.WaveNet
import org.example.ui.MainWindow
import org.example.ui.Theme
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when {
        // Which backends loaded, and why the others did not. The fastest answer to "why is it
        // 200 ms on this machine".
        args.contains("--list-backends") -> listBackends()

        // Which devices each backend can see, headlessly. On Linux the usual cause of an empty list
        // is not "no device" but "device held exclusively" by PipeWire or PulseAudio.
        args.contains("--list-devices") -> listDevices()

        // What a NAM capture costs on this machine, which is the only thing that decides whether
        // the module is usable at a given buffer size.
        args.contains("--nambench") -> namBench(args.filter { it != "--nambench" })

        args.isNotEmpty() && args[0] == "--selftest" -> selfTest(args.drop(1), measure = false)

        // True cable-to-cable round trip. Needs a lead from output back to input.
        args.isNotEmpty() && args[0] == "--measure" -> selfTest(args.drop(1), measure = true)

        else -> {
            // Keeps the custom-painted knobs crisp on scaled displays and HiDPI Linux desktops.
            System.setProperty("sun.java2d.uiScale.enabled", "true")
            // Ahead of the window rather than inside it, and that is the whole point of the line.
            // The settings file is what a user has to edit to turn logging on without a menu, so it
            // has to be created *before* the startup that might not survive to build one. The window
            // loads the same file a moment later and finds it already there, so this prints once,
            // on the run that created it, and never again.
            org.example.util.Config.default().firstRunNotice?.let { println(it) }
            // An exception nothing caught is the failure a submitted log most needs to contain, and
            // it is precisely the one with no code of ours left to record it. Swing's default prints
            // to a console that a double-clicked OmegaPlugin.bat does not have, so without this the
            // evidence goes nowhere at all. Installed before the window, since construction is one
            // of the places it can happen.
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                org.example.util.Log.error("crash", "Uncaught on \"${thread.name}\".", error)
                // Still the console too, for anyone running from a terminal or through Gradle.
                error.printStackTrace()
            }
            SwingUtilities.invokeLater {
                Theme.install()
                org.example.ui.AppIcon.applyToTaskbar()
                // The logo, dead centre, for the half second the window takes to build; the window's
                // veil then carries it and fades it out. Taken down if the window never arrives, or
                // a failed start would leave a logo on the desktop with nothing behind it.
                org.example.ui.Splash.show()
                val build = {
                    try {
                        MainWindow().isVisible = true
                    } catch (t: Throwable) {
                        org.example.ui.Splash.dismiss()
                        throw t
                    }
                }
                // On a second event, not this one: the splash is painted when the event thread
                // next comes free, and building the window takes half a second of it. Shown and
                // built in one event, the splash's first paint would land after the window's.
                // X11 asks for that paint later still, behind the second event, so on Linux the
                // window waits for the paint itself.
                if (org.example.audio.linux.LinuxPlatform.isLinux) {
                    org.example.ui.Splash.whenPainted(build)
                } else {
                    SwingUtilities.invokeLater(build)
                }
            }
            return
        }
    }
    // Backends hold device handles; under an exclusive-mode backend a handle left open can need a
    // reboot to clear. Release before the process goes.
    Backends.shutdown()
    exitProcess(0)
}

/**
 * Whether the JVM is running the collector this program needs.
 *
 * At a 1.45 ms callback period a G1 young pause is two lost buffers. Measured on this machine at
 * 64 frames under allocation pressure: G1 lost 1.15 % of realtime with a worst callback gap of
 * 17.1 ms, generational ZGC lost 0.01 % with a worst gap of 4.1 ms. That is not a tuning
 * preference, it is the difference between working and not.
 *
 * `java -jar` cannot be told to use it — a manifest carries no VM options — so the app checks and
 * says so rather than quietly running badly. `gradlew run` and the generated start scripts get the
 * flags from `applicationDefaultJvmArgs`.
 */
fun collectorWarning(): String? {
    val names = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().map { it.name }
    if (names.any { it.contains("ZGC", ignoreCase = true) }) return null
    return "Running under ${names.joinToString(" + ").ifEmpty { "an unknown collector" }}. " +
        "For the smallest buffer sizes, relaunch with: java -XX:+UseZGC -XX:+ZGenerational -jar <jar>"
}

private fun listBackends() {
    println("Backends, best first:")
    for (backend in Backends.all()) {
        val state = backend.availability.reason?.let { "UNAVAILABLE — $it" } ?: "available"
        println("  ${backend.id.displayName.padEnd(18)} $state")
        if (!backend.availability.isAvailable) continue
        val devices = runCatching { backend.devices() }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            println("      (no devices)")
            continue
        }
        for (d in devices) {
            val caps = runCatching { backend.capabilities(d) }.getOrNull()
            val rate = caps?.fixedSampleRate ?: caps?.preferredSampleRate?.takeIf { it > 0 }
                ?: caps?.sampleRates?.firstOrNull() ?: 0
            val sizes = caps?.bufferSizes.orEmpty()
            val range = when {
                sizes.isEmpty() -> "buffer ?"
                else -> "buffer ${sizes.first()}..${sizes.last()} frames, prefers ${caps?.preferredBufferFrames}"
            }
            // "smallest buffer", not "period": on Java Sound the two are different things, because
            // the figure offered there is a line capacity and the DSP block stays at 256 frames.
            val best = if (rate > 0 && sizes.isNotEmpty()) {
                String.format(" — smallest is %.2f ms", sizes.first() * 1000f / rate)
            } else ""
            // Every rate, not just the chosen one: an "only 48 kHz" device is the whole reason a
            // pair can fail to open, so that fact belongs in the listing rather than in an error.
            val rates = caps?.sampleRates.orEmpty()
            val rateText = if (rates.size > 1) "${SampleRates.describe(rates)}, using $rate Hz" else "$rate Hz"
            println("      ${d.name}  (${d.inputChannels} in / ${d.outputChannels} out, $rateText, $range)$best")
        }
    }
    println()
    println(collectorWarning() ?: "Collector: generational ZGC. Good.")
    // Linux only: whether a thread of ours can have realtime priority is a per-account setting, and
    // the one most likely to be the answer to "it crackles". JACK's thread is the server's and is
    // not covered by this; ALSA's is.
    if (org.example.audio.linux.LinuxPlatform.isLinux) {
        // Throwable: without JNA the probe's class cannot load, and the listing above already says
        // why. The diagnostic must survive the very failure it is there to describe.
        val grant = try {
            org.example.audio.linux.LinuxRt.probe()
        } catch (t: Throwable) {
            null
        }
        println(
            when {
                grant == null -> "Realtime: could not be checked — JNA did not load."
                grant.realtime -> "Realtime: ${grant.detail}. Good."
                else -> "Realtime: ${grant.detail}"
            }
        )
    }
}

/** A benchmark layer array: uniform activation, no gating, no 1x1 on the head path. */
private fun benchArray(
    inputSize: Int,
    channels: Int,
    headChannels: Int,
    kernelSizes: IntArray,
    dilations: IntArray,
    headKernelSize: Int,
    headBias: Boolean,
    activation: String = "Tanh",
): LayerArrayConfig = LayerArrayConfig(
    inputSize = inputSize,
    conditionSize = 1,
    channels = channels,
    bottleneck = channels,
    kernelSizes = kernelSizes,
    dilations = dilations,
    primary = Array(dilations.size) { Activation.of(activation) },
    gating = IntArray(dilations.size) { Gating.NONE },
    secondary = arrayOfNulls(dilations.size),
    layer1x1 = true,
    head1x1 = false,
    head1x1Channels = 1,
    headChannels = headChannels,
    headKernelSize = headKernelSize,
    headBias = headBias,
)

/**
 * Times a standard-sized NAM capture against realtime.
 *
 * Neural inference is the one module whose cost is large enough to decide the buffer size, and it
 * varies enormously between machines and between models. Guessing would be irresponsible; this
 * measures.
 */
private fun namBench(args: List<String>) {
    val path = args.firstOrNull { !it.startsWith("--") }
    if (path != null) {
        namBenchFile(path)
        return
    }

    val dilations = IntArray(10) { 1 shl it }
    val nine = IntArray(9) { 1 shl it }
    val eight = IntArray(8) { 1 shl it }

    // The dilation and kernel pattern of the 0.7.0 slimmable captures this was tested against: one
    // layer array of 23 layers, kernel 6 except for two wide ones, and a 16-tap head re-channel.
    val slimDilations = intArrayOf(
        1, 3, 7, 17, 41, 101, 239,
        1, 3, 7, 17, 41, 101, 239,
        1, 13,
        1, 3, 7, 17, 41, 101, 239,
    )
    val slimKernels = IntArray(slimDilations.size) { if (it == 14 || it == 15) 15 else 6 }

    val shapes = listOf(
        "0.5.x standard (16/8 channels, 2x10 layers)" to listOf(
            benchArray(1, 16, 8, IntArray(dilations.size) { 3 }, dilations, 1, false),
            benchArray(16, 8, 1, IntArray(dilations.size) { 3 }, dilations, 1, true),
        ),
        "0.5.x lite (8/4 channels, 2x9 layers)" to listOf(
            benchArray(1, 8, 4, IntArray(nine.size) { 3 }, nine, 1, false),
            benchArray(8, 4, 1, IntArray(nine.size) { 3 }, nine, 1, true),
        ),
        "0.5.x feather (8/4 channels, 2x8 layers)" to listOf(
            benchArray(1, 8, 4, IntArray(eight.size) { 3 }, eight, 1, false),
            benchArray(8, 4, 1, IntArray(eight.size) { 3 }, eight, 1, true),
        ),
        "0.7.0 slimmable, full (8 channels, 23 layers)" to listOf(
            benchArray(1, 8, 1, slimKernels, slimDilations, 16, true, "LeakyReLU"),
        ),
        "0.7.0 slimmable, small (3 channels, 23 layers)" to listOf(
            benchArray(1, 3, 1, slimKernels, slimDilations, 16, true, "LeakyReLU"),
        ),
    )

    println(collectorWarning() ?: "Collector: generational ZGC.")
    println()
    println("model                                   ops/sample    realtime x    CPU at 44.1 kHz")

    for ((label, configs) in shapes) {
        // Small deterministic weights: the point is the arithmetic's cost, and a network that
        // saturates or explodes would measure the same but tell us nothing.
        var seed = 0x9E3779B97F4A7C15uL
        val weights = FloatArray(WaveNet.weightCount(configs, null)) {
            seed = seed * 6364136223846793005uL + 1442695040888963407uL
            (((seed shr 41).toInt() and 0x7FFFFF) / 8_388_608f - 0.5f) * 0.08f
        }
        val network = WaveNet.build(configs, null, weights)

        val block = FloatArray(128) { 0.05f }
        // Warm up, so the JIT has compiled the inner loops before the clock starts.
        repeat(400) { network.process(block, block.size) }

        val blocks = 3000
        val started = System.nanoTime()
        repeat(blocks) { network.process(block, block.size) }
        val elapsed = (System.nanoTime() - started) / 1e9

        val samples = blocks.toLong() * block.size
        val audioSeconds = samples / 44_100.0
        val realtime = audioSeconds / elapsed
        println(
            String.format(
                "%-38s %,11d %12.1f %14.0f %%",
                label,
                network.operationsPerSample,
                realtime,
                100.0 / realtime,
            )
        )
    }

    println()
    println("Realtime x is how many seconds of audio it renders per second of CPU. It has to stay")
    println("comfortably above 1, because the audio callback gets only its share of the budget and")
    println("the rest of the chain, the UI and the collector all want some of the same core.")
}

/**
 * Loads a real `.nam` and reports what it costs — and, where the file allows it, whether it was
 * read correctly.
 *
 * The cost half is the same measurement as the synthetic bench. The correctness half is the reason
 * this takes a file at all: a slimmable container holds *the same amplifier* captured at several
 * widths, so their outputs for one input must agree closely. Nothing else available here can catch
 * a misread weight layout — a wrong unpacking produces a network that runs perfectly happily and
 * sounds like nothing in particular — but two independently-stored blobs agreeing to three decimal
 * places cannot happen by accident.
 */
private fun namBenchFile(path: String) {
    val target = java.io.File(path)
    if (target.isDirectory) {
        val files = target.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".nam", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            println("No .nam files in ${target.path}")
            return
        }
        println(String.format("%-24s %5s %13s %10s %13s", "model", "subs", "ops/sample", "loudness", "correlation"))
        for (file in files) namBenchSummary(file)
        println()
        println("Correlation compares the smallest submodel against the full-size one. They are the")
        println("same amplifier at different widths, so a value near 1 says the weight layout was")
        println("read correctly; a near-zero one says it was not.")
        return
    }

    val loaded = load(target) ?: return
    val rate = loaded.sampleRate
    println("${loaded.name}: ${loaded.submodels.size} submodel(s), captured at ${rate.toInt()} Hz")
    println()

    // What loading actually costs, and whether the first block after it is realtime. A model that
    // has never executed runs interpreted — measured at 0.1x realtime against 2.5x compiled — so
    // without the warm-up the first second after LOAD misses every deadline. This is the check that
    // it does not.
    val module = Nam()
    module.prepare(rate, 512)
    val loadStarted = System.nanoTime()
    module.load(loaded)
    val loadMillis = (System.nanoTime() - loadStarted) / 1e6

    val firstBlock = FloatArray(64) { 0.05f }
    val blockStarted = System.nanoTime()
    module.process(firstBlock, firstBlock.size)
    val blockMicros = (System.nanoTime() - blockStarted) / 1000.0
    val periodMicros = 64.0 * 1_000_000.0 / rate

    println(
        String.format(
            "Warm-up behind the LOAD button: %.0f ms. First 64-frame block after it: %.0f us " +
                "against a %.0f us period — %s.",
            loadMillis,
            blockMicros,
            periodMicros,
            if (blockMicros < periodMicros) "realtime from the first block" else "NOT realtime",
        )
    )
    println()
    println("submodel   max size   ops/sample   realtime x    CPU   loudness    RMS raw   normalised")

    val length = 4096
    val outputs = ArrayList<FloatArray>()

    for (submodel in loaded.submodels) {
        val network = submodel.network
        val signal = FloatArray(length)
        settle(network, rate)
        for (i in 0 until length) signal[i] = tone(WARM_UP + i, rate)

        val started = System.nanoTime()
        network.process(signal, length)
        val elapsed = (System.nanoTime() - started) / 1e9

        val realtime = (length / rate) / elapsed
        var sum = 0.0
        for (value in signal) sum += value.toDouble() * value
        outputs.add(signal)

        val rms = Math.sqrt(sum / length)
        val loudness = submodel.loudness
        val gain =
            if (loudness == null) 1.0 else Math.pow(10.0, (Nam.TARGET_LOUDNESS - loudness) / 20.0)
        println(
            String.format(
                "%-10s %8.2f %,12d %11.1f %6.0f %% %9s %10.4f %12.4f",
                "${outputs.size} of ${loaded.submodels.size}",
                submodel.maxValue,
                network.operationsPerSample,
                realtime,
                100.0 / realtime,
                if (loudness == null) "-" else String.format("%.1f dB", loudness),
                rms,
                rms * gain,
            )
        )
    }

    if (outputs.size > 1) {
        println()
        val full = outputs[outputs.size - 1]
        for (i in 0 until outputs.size - 1) {
            println(
                String.format(
                    "submodel %d against the full-size one: correlation %.4f",
                    i + 1,
                    correlation(outputs[i], full),
                )
            )
        }
        println()
        println("These are the same amplifier captured at different widths, so a correlation near 1")
        println("says the weight layout was read correctly. A near-zero one says it was not.")
    }
}

/** One line per model: enough to check a whole folder loads and was read correctly. */
private fun namBenchSummary(file: java.io.File) {
    val loaded = load(file) ?: return
    val rate = loaded.sampleRate
    val full = loaded.submodels[loaded.submodels.size - 1]

    val correlation = if (loaded.submodels.size < 2) {
        "-"
    } else {
        val small = render(loaded.submodels[0].network, rate)
        val large = render(full.network, rate)
        String.format("%.4f", correlation(small, large))
    }

    println(
        String.format(
            "%-24s %5d %,13d %10s %13s",
            file.nameWithoutExtension.take(24),
            loaded.submodels.size,
            full.network.operationsPerSample,
            full.loudness?.let { String.format("%.1f dB", it) } ?: "-",
            correlation,
        )
    )
}

private fun load(file: java.io.File): NamFile.Loaded? = try {
    NamFile.parse(file.readText(), file.nameWithoutExtension)
} catch (e: NamException) {
    println("\"${file.name}\" ${e.message}")
    null
} catch (e: Exception) {
    println("Could not read ${file.path}: ${e.message ?: e::class.simpleName}")
    null
}

/** Samples of signal pushed through before anything is measured or compared. */
private const val WARM_UP = 4096

/**
 * Fills the network's ring buffers.
 *
 * Every model here has a receptive field of a couple of thousand samples, and until that has filled
 * it is reporting its own start-up transient rather than the amplifier.
 */
private fun settle(network: WaveNet, rate: Float) {
    network.reset()
    val block = FloatArray(512)
    var at = 0
    while (at < WARM_UP) {
        for (i in block.indices) block[i] = tone(at + i, rate)
        network.process(block, block.size)
        at += block.size
    }
}

private fun render(network: WaveNet, rate: Float, length: Int = 4096): FloatArray {
    settle(network, rate)
    val signal = FloatArray(length) { tone(WARM_UP + it, rate) }
    network.process(signal, length)
    return signal
}

/** A quiet two-tone test signal: enough harmonic content to move a guitar amp model. */
private fun tone(index: Int, rate: Float): Float {
    val t = index / rate
    return 0.1f * kotlin.math.sin(2.0 * Math.PI * 220.0 * t).toFloat() +
        0.05f * kotlin.math.sin(2.0 * Math.PI * 660.0 * t).toFloat()
}

private fun correlation(a: FloatArray, b: FloatArray): Double {
    var meanA = 0.0
    var meanB = 0.0
    for (i in a.indices) {
        meanA += a[i]
        meanB += b[i]
    }
    meanA /= a.size
    meanB /= b.size
    var top = 0.0
    var sumA = 0.0
    var sumB = 0.0
    for (i in a.indices) {
        val x = a[i] - meanA
        val y = b[i] - meanB
        top += x * y
        sumA += x * x
        sumB += y * y
    }
    val bottom = Math.sqrt(sumA * sumB)
    return if (bottom == 0.0) 0.0 else top / bottom
}

private fun listDevices() {
    for (backend in Backends.all()) {
        if (!backend.availability.isAvailable) {
            println("${backend.id.displayName}: ${backend.availability.reason}")
            continue
        }
        val devices = runCatching { backend.devices() }.getOrDefault(emptyList())
        println("${backend.id.displayName} (${devices.size}):")
        for (d in devices) println("  - ${d.name}  [${d.inputChannels} in / ${d.outputChannels} out]")
    }
}

/**
 * A 1470 Hz sine that ignores its input, for `--selftest --tone`.
 *
 * Safe to point at speakers only if you mean to; without `--tone` the chain is empty and the input
 * simply passes through.
 */
private class TestTone : org.example.dsp.AudioProcessor {
    override val name = "Tone"
    override var enabled = true
    override val parameters = emptyList<org.example.dsp.Parameter>()

    private var increment = incrementFor(DEFAULT_SAMPLE_RATE)
    private var phase = 0.0

    override fun prepare(sampleRate: Float, maxBlockFrames: Int) {
        increment = incrementFor(sampleRate)
    }

    private fun incrementFor(sampleRate: Float) = 2.0 * Math.PI * TONE_HZ / sampleRate

    override fun process(buffer: FloatArray, count: Int) {
        for (i in 0 until count) {
            buffer[i] = (0.7 * kotlin.math.sin(phase)).toFloat()
            phase += increment
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI
        }
    }

    override fun reset() {
        phase = 0.0
    }

    private companion object {
        const val TONE_HZ = 1470.0
    }
}

private fun flag(argv: List<String>, name: String): String? =
    argv.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')

/**
 * ```
 * --selftest [--backend=asio] [--buffer=128] <input> <output> [seconds] [--tone]
 * --measure  [--backend=asio] [--buffer=128] <input> <output> [seconds]
 * ```
 *
 * Both run the real engine through the real seam; `--measure` inserts [LoopbackProbe] instead of the
 * effect chain and reports the true round trip, which needs a lead from the output back into the
 * input. Device names match on any substring. On a single-device backend the output argument is
 * ignored — the driver owns both directions.
 */
private fun selfTest(argv: List<String>, measure: Boolean) {
    val tone = argv.contains("--tone")
    val backendName = flag(argv, "backend")
    val bufferOverride = flag(argv, "buffer")?.toIntOrNull()
    val positional = argv.filterNot { it.startsWith("--") }

    val backend: AudioBackend = backendName
        ?.let { needle ->
            Backends.all().firstOrNull {
                it.id.name.contains(needle, true) || it.id.displayName.contains(needle, true)
            }
        }
        ?: Backends.preferred()

    if (!backend.availability.isAvailable) {
        println("${backend.id.displayName} is not available: ${backend.availability.reason}")
        return
    }

    val devices = backend.devices()
    if (positional.size < 2) {
        println("usage: ${if (measure) "--measure" else "--selftest"} " +
            "[--backend=asio] [--buffer=128] <input substring> <output substring> [seconds]" +
            if (measure) "" else " [--chain] [--split] [--stress] [--tone]")
        println()
        listDevices()
        return
    }
    val seconds = positional.getOrNull(2)?.toIntOrNull() ?: 15

    fun pick(needle: String, wantInput: Boolean): BackendDevice? = devices
        .filter { if (wantInput) it.inputChannels > 0 else it.outputChannels > 0 }
        .firstOrNull { it.name.contains(needle, ignoreCase = true) }

    val input = pick(positional[0], wantInput = true)
    val single = input?.let { backend.capabilities(it).pairing } == Pairing.SINGLE_DEVICE
    val output = if (single) devices.firstOrNull { it.id == input?.id } else pick(positional[1], wantInput = false)

    if (input == null || output == null) {
        println("No ${if (input == null) "input" else "output"} on ${backend.id.displayName} matching " +
            "\"${if (input == null) positional[0] else positional[1]}\".")
        println()
        listDevices()
        return
    }

    // The rate has to satisfy BOTH endpoints. Taking it from the input alone is what sent a
    // 44.1 kHz request at a 48 kHz-only HDMI output and produced impossible advice in return.
    val inputCaps = backend.capabilities(input)
    val outputCaps = if (single || output.id == input.id) inputCaps else backend.capabilities(output)
    val shared = if (single || output.id == input.id) inputCaps.sampleRates
    else SampleRates.offer(inputCaps.sampleRates, outputCaps.sampleRates, backend.convertsInput)

    if (shared.isEmpty()) {
        println(SampleRates.noOverlapMessage(input.name, inputCaps.sampleRates, output.name, outputCaps.sampleRates))
        return
    }

    val rateOverride = flag(argv, "rate")?.toIntOrNull()
    val rate = inputCaps.fixedSampleRate
        ?: rateOverride?.takeIf { it in shared }
        ?: SampleRates.recommended(shared, inputCaps.preferredSampleRate, outputCaps.preferredSampleRate)
        ?: 44_100
    if (rateOverride != null && rateOverride !in shared) {
        println("note    : ${SampleRates.format(rateOverride)} is not shared by both devices; " +
            "they offer ${SampleRates.describe(shared)}. Using ${SampleRates.format(rate)}.")
    }

    if (!single && output.id != input.id && SampleRates.needsConversion(rate, inputCaps.sampleRates)) {
        val captureRate = SampleRates.captureRate(rate, inputCaps.sampleRates, inputCaps.preferredSampleRate)
        println("input   : C — ${SampleRates.conversionNote(input.name, captureRate, rate)}")
    }

    val caps = backend.capabilities(input, rate)
    val outCaps = if (single || output.id == input.id) caps else backend.capabilities(output, rate)
    // A backend may find an endpoint has no usable buffer size at all — an HDMI output whose driver
    // rounds every size it is offered, for instance. Say so instead of picking one that will break.
    for ((who, c) in listOf(input.name to caps, output.name to outCaps)) {
        if (c.bufferSizes.isEmpty()) {
            println("$who has no usable buffer size at ${SampleRates.format(rate)} on " +
                "${backend.id.displayName}. Its driver does not honour any size offered.")
            return
        }
    }
    val frames = bufferOverride
        ?.let { want -> caps.bufferSizes.minByOrNull { kotlin.math.abs(it - want) } ?: want }
        ?: caps.preferredBufferFrames

    println("backend : ${backend.id.displayName}")
    println("in      : ${input.name}  (${SampleRates.describe(inputCaps.sampleRates)})")
    println("out     : ${output.name}${if (single) "  (same driver — one device owns both directions)"
        else "  (${SampleRates.describe(outputCaps.sampleRates)})"}")
    println(String.format("request : %d Hz, %d frames = %.2f ms", rate, frames, SampleRates.periodMs(frames, rate)))
    collectorWarning()?.let { println("warning : $it") }

    val probe = if (measure) LoopbackProbe() else null
    // --chain runs the shipping pedals, every one of them on. Without it the chain is empty, which
    // measures the transport but says nothing about whether the DSP fits inside the deadline.
    //
    // Every module that can run without a file on disk, which is all of them but NAM and IR — those
    // need a capture and a cabinet, and a selftest that demanded either would be a selftest nobody
    // could run. The FX slot is in here at its heaviest setting rather than its default: a slot
    // holding a reverb is the most expensive thing it can be, and the number worth knowing is the
    // worst case, not the typical one.
    //
    // A board's LEVEL is in here too, active, because that is how it runs in split mode and it is
    // the only module the application always carries that this list would otherwise leave out.
    fun shippingChain(): List<org.example.dsp.AudioProcessor> = listOf(
        org.example.dsp.Gate(),
        org.example.dsp.Compressor(),
        org.example.dsp.Eq().apply {
            // Flat bands are skipped by design, so an untouched EQ would measure as free and
            // report a cost this chain does not actually have. Every band is given something.
            lowCut.value = 80f
            midGain.value = -6f
            mid2Gain.value = 3f
            lowGain.value = 2f
            highGain.value = -4f
        },
        org.example.dsp.Gain(),
        org.example.dsp.Overdrive(),
        // The amp at its most expensive voice — four cascaded stages at twice the rate — and the
        // cabinet pedal on a synthesised cabinet, which is the one way it runs without a file.
        org.example.dsp.Amp().apply { selectType("Modern High Gain") },
        org.example.dsp.Ir().apply { selectType("4x12 Closed") },
        org.example.dsp.FxSlot().apply { selectType("Reverb") },
        org.example.dsp.Delay(),
        org.example.dsp.Reverb(),
        org.example.dsp.BoardLevel().apply { active = true },
    )
    val pedals = if (argv.contains("--chain")) shippingChain() else emptyList()
    val chain = when {
        probe != null -> EffectChain(listOf(probe) + pedals)
        tone -> EffectChain(listOf(TestTone()) + pedals)
        else -> EffectChain(pedals)
    }
    // --split is the application's split mode: the same chain again on a second board, summed with
    // the first. It is the worst case the shipping program can be put in without a file on disk,
    // and the one the "two boards need a bigger buffer" advice has to be measured against.
    val split = argv.contains("--split")
    val second = if (split) EffectChain(shippingChain()) else null
    if (pedals.isNotEmpty()) println("chain   : ${pedals.joinToString(" -> ") { it.name }}, all enabled")
    if (split) println("split   : a second board running the same chain, summed with the first")
    if (measure) {
        println("measure : 1470 Hz burst out, Goertzel detector in. Patch a lead from OUT to IN.")
    } else if (tone) {
        println("tone    : 1470 Hz at -3 dBFS replacing the input signal")
    }

    // --stress floods the heap from a side thread. An idle machine will hold almost any buffer
    // size; the question that decides the shipping default is whether it holds while the rest of
    // the JVM is working, because the audio callback is an attached Java thread and cannot opt out
    // of a safepoint. This is the switch that separated G1 from ZGC.
    val stress = argv.contains("--stress")
    val stressing = java.util.concurrent.atomic.AtomicBoolean(stress)
    if (stress) {
        println("stress  : allocation flood on a side thread")
        Thread({
            val held = ArrayDeque<ByteArray>()
            while (stressing.get()) {
                repeat(400) { held.addLast(ByteArray(32 * 1024)) }
                while (held.size > 3_000) held.removeFirst()
                Thread.sleep(2)
            }
        }, "stress").apply { isDaemon = true; start() }
    }

    // The master, after the sum, as the application runs it — at 100 %, so a tone or a probe burst
    // leaves at the level it was asked for. Only with --chain: without it there is no DSP to measure.
    val post = if (pedals.isNotEmpty()) {
        EffectChain(listOf(org.example.dsp.MasterVolume().apply { volume.value = 100f }))
    } else {
        null
    }
    // The loop box, with --chain: three lanes of noise playing — one of them through the boards,
    // which is the costlier path — and a fourth recording, which is every lane doing something at
    // once and so the most the looper can cost. The lanes are filled after `engine.start`, because
    // `prepare` is what sizes them for the rate the device granted.
    val looper = if (pedals.isNotEmpty()) org.example.dsp.loop.Looper().apply { enabled = true; playing = true } else null
    if (looper != null) println("loop    : three lanes playing (one through the boards), a fourth recording")
    val engine = AudioEngine(chain, second = second, post = post, looper = looper)
    engine.split = split
    engine.onError = { println("stream error: $it") }

    try {
        engine.start(
            backend,
            StreamRequest(
                input = input,
                inputChannel = 0,
                output = output,
                outputChannels = IntArray(minOf(2, maxOf(1, output.outputChannels))) { it },
                sampleRate = rate,
                bufferFrames = frames,
            ),
        )
    } catch (t: Throwable) {
        println("Could not open audio: ${t.message ?: t.javaClass.simpleName}")
        return
    }

    // The rhythm section runs on the audio thread and is not in the chain, so `--chain` has to reach
    // it deliberately or it is simply not measured. CLAUDE.md records what happens otherwise: this
    // switch once drifted to four pedals and spent several versions quietly measuring less than half
    // the app.
    //
    // **After `engine.start`, not before.** The warm-up pass drives the transport over every pattern
    // in the catalogue and stops it again in its `finally`, so a transport started above this line
    // is stopped by the time the first callback arrives — and a silent one measures nothing while
    // still printing that it ran.
    //
    // Its heaviest setting rather than its default, for the same reason the FX slot holds a reverb.
    // A full kit with the click layered over it, not a bare metronome: the scheduler costs the same
    // either way, and the voices are the part that adds up — a kick, a snare, two hats and a tick
    // all sounding at once, at a tempo that keeps them overlapping.
    //
    // Level zero, so a selftest pointed at speakers does not start playing drums at somebody. The
    // cost is in the scheduling, the oscillators and the filters, and all three run whatever the
    // level says.
    if (pedals.isNotEmpty()) {
        val signature = org.example.dsp.rhythm.TimeSignature.FOUR_FOUR
        engine.rhythm.signature = signature
        engine.rhythm.pattern =
            org.example.dsp.rhythm.Feels.find(signature, "Rock") ?: engine.rhythm.pattern
        engine.rhythm.clickOverDrums = true
        engine.rhythm.tempoBpm = 240.0
        engine.rhythm.level = 0f
        engine.rhythm.start()
        println("rhythm  : ${engine.rhythm.pattern.name} + click at 240 BPM, silent")
    }
    // The lanes, now that `prepare` has sized them for the device's rate. The fourth is armed first:
    // with no loop yet it starts recording at the next callback and runs for the whole minute. The
    // other three are then loaded with a minute of near-silent noise, the third through the boards,
    // which sets the loop at that minute. Near silence, because two of them are heard.
    if (looper != null) {
        val frames = looper.capacity
        looper.laneCount = 4
        looper.arm(looper.lanes[3])
        Thread.sleep(50)
        val noise = FloatArray(frames) { i -> ((i * 2654435761L.toInt()) shr 8) * 1e-12f }
        for (i in 0 until 3) {
            looper.load(looper.lanes[i], noise, frames, "selftest")
            looper.lanes[i].level.value = 10f
        }
        looper.lanes[2].order = org.example.dsp.loop.LaneOrder.THROUGH
    }

    // The pause behind the Start button, broken down. It is invisible from inside the application —
    // `start` runs on the event thread, so the window is frozen for exactly this long — and this is
    // the only place it can be read without a stopwatch.
    engine.lastStartTiming?.let { println("startup : $it") }

    val format = engine.format
    if (format != null) {
        println(String.format(
            "granted : %d Hz, %d frames = %.2f ms per callback",
            format.sampleRate, format.bufferFrames, format.callbackPeriodMs,
        ))
    }

    println()
    println("  time   load%   worst    realtime  misses  xruns" + if (measure) "   hits" else "")
    val startedAt = System.nanoTime()
    repeat(seconds) { second ->
        Thread.sleep(1_000)
        val row = String.format(
            "  %3ds   %5.1f   %5.2fms   %7.4f  %6d %6d",
            second + 1,
            engine.loadPercent * 100f,
            engine.worstCallbackMicros / 1000f,
            engine.realtimeRatio,
            engine.deadlineMisses,
            engine.xruns,
        )
        println(if (probe != null) row + String.format(" %6d", probe.count) else row)
    }
    val elapsed = (System.nanoTime() - startedAt) / 1e9
    val misses = engine.deadlineMisses
    val xruns = engine.xruns
    val worst = engine.worstCallbackMicros / 1000f
    val fault = engine.faulted
    val ratio = engine.realtimeRatio
    // Read at the end, not at the start: on a two-clock backend the adapter's own contribution is
    // the playback queue, which has not settled yet one block after start().
    val finalFormat = engine.format
    val scheduling = engine.schedulingNote
    stressing.set(false)
    engine.stop()

    println()
    finalFormat?.let {
        println(String.format(
            "round trip      : %.2f ms as %s reports it (in %d + out %d%s frames at %d Hz)",
            it.reportedRoundTripMs, backend.id.displayName, it.inputLatencyFrames, it.outputLatencyFrames,
            if (it.adapterLatencyFrames > 0) " + ${it.adapterLatencyFrames} this adapter added" else "",
            it.sampleRate,
        ))
    }
    println(String.format("realtime ratio  : %.4f — frames the device got vs frames the clock asked for", ratio))
    println(String.format("deadline misses : %d (%.2f/s) — callbacks that overran their own period", misses, misses / elapsed))
    println(String.format("device xruns    : %d (%.2f/s)", xruns, xruns / elapsed))
    println(String.format("worst callback  : %.2f ms against a %.2f ms period", worst, frames * 1000f / rate))
    scheduling?.let { println("scheduling      : $it") }
    fault?.let { println("DSP fault       : $it") }

    if (probe != null) {
        val median = probe.medianFrames()
        if (median == null) {
            println()
            println("No loopback detected in $seconds s. Nothing came back on the input above the noise")
            println(String.format("floor (Goertzel baseline %.3e). Patch a lead from the output to the input,", probe.noiseBaseline))
            println("check the input gain is up, and that the output is not muted.")
        } else {
            val trips = probe.results().sortedArray()
            println()
            println(String.format("TRUE ROUND TRIP from %d loopback circulations:", trips.size))
            println(String.format("  min %d frames = %.2f ms", trips.first(), trips.first() * 1000f / rate))
            println(String.format("  median %d frames = %.2f ms", median, median * 1000f / rate))
            println(String.format("  max %d frames = %.2f ms", trips.last(), trips.last() * 1000f / rate))
            format?.let {
                println(String.format("  the driver claimed %.2f ms — difference %+.2f ms (converters and cable)",
                    it.reportedRoundTripMs, median * 1000f / rate - it.reportedRoundTripMs))
            }
        }
    }
    val starved = ratio < 0.995f
    println(
        when {
            starved -> String.format(
                "FAIL - the device was starved: it got %.2f%% of the audio it asked for.", ratio * 100f
            )
            misses + xruns > 0 -> "FAIL - the stream dropped out."
            else -> "PASS - no dropouts, no starvation."
        }
    )
}
