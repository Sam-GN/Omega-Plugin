# Low-latency backend: design

**Status:** design only. Nothing under `src/` or `build.gradle.kts` is changed by this document.
**Target:** 10–12 ms round trip, cable to cable.
**Scope split:** which native backend to use, its licence, and what this machine can actually achieve
are Cid's. Everything that has to be true *around* whatever he picks is here. Where a decision waits
on his measurements it is marked **[DEPENDS ON CID]**.

Assumption running through the whole document: the chosen backend is **callback-driven and
native-backed**, because every candidate is. ASIO calls us on the driver's thread; WASAPI exclusive
and ALSA `hw:` wake our thread on an event and are wrapped to *look* like a callback; JACK and
PipeWire call us on the server's client thread. The seam is shaped for that, and Java Sound is
adapted into it rather than allowed to dictate it.

---

## 0. The latency arithmetic, so the rest of the document has numbers

At 48 kHz:

| Buffer | Period | 2× buffer | Plausible driver offsets | Round trip |
|-------:|-------:|----------:|-------------------------:|-----------:|
| 64     | 1.33 ms | 2.7 ms   | 3–6 ms                   | 6–9 ms     |
| 128    | 2.67 ms | 5.3 ms   | 3–6 ms                   | 8–11 ms    |
| 256    | 5.33 ms | 10.7 ms  | 3–6 ms                   | 14–17 ms   |
| 480 (WASAPI shared default period) | 10.0 ms | 20 ms | 2–4 ms | 22–24 ms |

So the design point is **48 kHz, 128 frames**, with 64 as the stretch and 256 as the safe fallback.
That fixes the callback deadline at **2.67 ms**, and everything in section 3 follows from it.

Two consequences worth stating up front:

- The sample rate stops being 44 100. Exclusive-mode and ASIO devices very often want 48 000, and
  JACK's rate is the server's to choose. **The rate becomes a runtime property**, which is the one
  thing in `dsp/` that genuinely breaks (section 2.5).
- The driver's own safety offsets, plus converter latency, are the term that decides whether 10–12 ms
  is reachable, and they are a property of the *interface*, not of our code. **[DEPENDS ON CID]**

The DSP cost is not the problem and should not be treated as one. Reverb (8 combs + 4 allpasses) and
Overdrive (one `tanh` per sample) come to roughly 150 ns/sample, i.e. ~19 µs for a 128-frame block
against a 2 670 µs period — under 1 % load. What threatens the deadline is the *runtime*: GC,
class loading, JIT, and thread scheduling. Section 3 is about those.

---

## 1. The `AudioBackend` seam

### 1.1 Why the earlier refusal was right, and what changes

A seam shaped around `TargetDataLine.read()` would have been a pull-driven, two-independent-devices,
16-bit-interleaved seam. Every candidate backend is push-driven, and several of them (ASIO, JACK)
cannot open input and output as separate devices at all. That seam would have had to be torn out.

The seam below is shaped the other way round: **the unit is a duplex stream driven by one clock**,
and Java Sound — which is genuinely two devices on two crystals — is the awkward case that has to
adapt internally. That inversion is the whole point.

### 1.2 The interfaces

```kotlin
package org.example.audio

/** Which implementation, not which device. [rank] orders first-run auto-selection: lowest wins. */
enum class BackendId(val displayName: String, val rank: Int) {
    ASIO("ASIO", 0),
    WASAPI_EXCLUSIVE("WASAPI (exclusive)", 1),
    JACK("JACK / PipeWire", 1),
    ALSA("ALSA (direct)", 2),
    WASAPI_SHARED("WASAPI (shared)", 3),
    JAVA_SOUND("Java Sound", 9),
}

sealed class Availability {
    object Available : Availability()
    /** Shown to the user verbatim. "No ASIO driver installed", "omega_wasapi.dll failed to load". */
    data class Unavailable(val reason: String) : Availability()
}

/**
 * A device as the backend names it.
 *
 * [id] must be stable across disconnect/reconnect and across runs — a driver-provided string, never
 * an index and never a display name. USB device indices shuffle when anything else is plugged in,
 * so persistence and auto-reconnect both key on [id]. Java Sound has no stable id; its adapter
 * synthesises one from the mixer name and accepts that a driver rename breaks the saved selection.
 */
data class BackendDevice(
    val backend: BackendId,
    val id: String,
    val name: String,
    val inputChannels: Int,
    val outputChannels: Int,
)

enum class Pairing {
    /** ASIO, JACK: one driver owns both directions. The GUI shows one device chooser. */
    SINGLE_DEVICE,
    /** Java Sound, WASAPI: input and output are chosen independently, on separate clocks. */
    ANY_PAIR,
}

data class DeviceCapabilities(
    /** Empty means the server dictates the rate (JACK) — see [fixedSampleRate]. */
    val sampleRates: List<Int>,
    /** Legal frame counts, ascending. May be a single value. Never offer anything not in here. */
    val bufferSizes: List<Int>,
    val preferredBufferFrames: Int,
    val pairing: Pairing,
    /** Non-null when the rate is not ours to choose. The GUI shows it read-only. */
    val fixedSampleRate: Int?,
    val hasControlPanel: Boolean,
)

data class StreamRequest(
    val input: BackendDevice?,
    /** Which hardware input the guitar is on. Multi-in interfaces make this mandatory, not cosmetic. */
    val inputChannel: Int,
    val output: BackendDevice?,
    /** One or two hardware outputs; the mono chain is duplicated across them. */
    val outputChannels: IntArray,
    val sampleRate: Int,
    val bufferFrames: Int,
)

/**
 * What the driver actually gave us. Authoritative — [StreamRequest] was only a preference, and
 * drivers routinely round the buffer size, refuse the rate, or change both later.
 */
data class StreamFormat(
    val sampleRate: Int,
    val bufferFrames: Int,
    /** Driver-reported, in frames. ASIO getLatencies; WASAPI GetStreamLatency + period. */
    val inputLatencyFrames: Int,
    val outputLatencyFrames: Int,
    /** Extra latency this adapter added itself. Zero for a true duplex callback; the cushion for Java Sound. */
    val adapterLatencyFrames: Int,
) {
    val callbackPeriodMs get() = bufferFrames * 1000f / sampleRate
    val reportedRoundTripMs
        get() = (inputLatencyFrames + outputLatencyFrames + adapterLatencyFrames) * 1000f / sampleRate
}

fun interface AudioCallback {
    /**
     * Called on the driver's thread. Mono in, mono out, distinct pre-allocated arrays reused on
     * every call — do not retain either. Must return inside a fraction of the callback period.
     * See docs/low-latency-backend.md section 3 for what that forbids.
     */
    fun render(input: FloatArray, output: FloatArray, frames: Int)
}

/**
 * Out-of-band notifications. **Never** invoked from the callback thread — the backend raises a flag
 * there and a monitor thread dispatches. Implementations bounce to the EDT themselves.
 */
interface StreamEvents {
    /** The driver changed the buffer size or rate under us (ASIO panel, JACK server). Re-read and redisplay. */
    fun onFormatChanged(format: StreamFormat)
    fun onDeviceLost(reason: String)
    fun onFatal(reason: String)
}

interface AudioStream : AutoCloseable {
    val format: StreamFormat
    /** Buffers the driver could not deliver or consume in time. Volatile; polled by the UI at 30 Hz. */
    val xruns: Int
    fun start()
    fun stop()
    override fun close()
}

interface AudioBackend {
    val id: BackendId
    val availability: Availability
    fun devices(): List<BackendDevice>
    fun capabilities(device: BackendDevice): DeviceCapabilities
    fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream
    /** Optional capability — ASIO only in practice. Blocks for seconds; call off the EDT. */
    fun openControlPanel(device: BackendDevice) {}
}
```

### 1.3 Load-bearing decisions in that shape

**Conversion lives in the backend, not the engine.** The callback hands the engine mono floats and
takes mono floats back. Channel selection, sample-format conversion (ASIO gives Int32LSB, Int24LSB
or Float32LSB per device, non-interleaved; WASAPI exclusive commonly Int16 or Int32; Java Sound
Int16 interleaved), and interleaving are all the adapter's job, because only the adapter knows its
buffer layout. `Pcm` stays as the Java Sound codec; other backends get their own, chosen once at
open and stored as a field so the callback has no branch on format.

The payoff is that `AudioEngine.render()` is byte-for-byte identical across every backend, and
`dsp/` never learns that anything changed.

**Duplex is the unit; two-clock is a property, not a shape.** `Pairing.ANY_PAIR` backends may be
handed two devices with independent crystals. That is exactly the situation `CushionRegulator`
exists for — so the regulator moves *into* those adapters and reports its cost through
`adapterLatencyFrames`. A `SINGLE_DEVICE` backend sets that to zero and does not compile the
regulator into its path at all.

**Buffer sizes are enumerated, never free-form.** ASIO reports min/max/preferred/granularity
(granularity −1 meaning powers of two). WASAPI exclusive requires a multiple of the device period.
JACK's is the server's. The GUI must only ever offer members of `DeviceCapabilities.bufferSizes`;
a text field or a continuous slider here produces "device refused the format" errors that look like
our bug.

**Format negotiation is one round trip, and the driver wins.** `open()` takes preferences and
returns the truth. Nothing downstream may cache the requested values. `onFormatChanged` exists
because the truth can change mid-stream — this is not an ASIO quirk (`kAsioResetRequest` when the
user changes the buffer in the driver panel), it is also JACK changing the server buffer size, and
WASAPI reporting a format change on an endpoint. One mechanism covers all three.

**Errors and device loss never travel on the callback thread.** The callback may only do a volatile
store of an error code and fill the output with silence. A monitor thread — one per stream, normal
priority, allowed to allocate and log — polls that code, plus the backend's own device-notification
mechanism, and calls `StreamEvents`. This keeps the "no I/O on the audio thread" rule intact under
the one condition where it is most tempting to break it.

**Latency is reported in the three terms that matter**, so the GUI can show the breakdown rather
than one unexplained number: driver input offset, buffer × 2, driver output offset, plus whatever
the adapter added. It is still not the truth — converter latency is invisible to the driver — which
is why the loopback measurement in section 4 stays part of the product.

### 1.4 The registry, and why it cannot throw

```kotlin
object Backends {
    private val CANDIDATES = listOf(
        "org.example.audio.asio.AsioBackend",
        "org.example.audio.wasapi.WasapiBackend",
        "org.example.audio.jack.JackBackend",
        "org.example.audio.alsa.AlsaBackend",
    )

    /**
     * Never throws and never returns empty: [JavaSoundBackend] is compiled into the core and has no
     * optional dependency. A candidate that will not load becomes an entry whose availability is
     * Unavailable(reason), so the GUI can say *why* instead of silently offering less.
     */
    fun all(): List<AudioBackend> = ...
}
```

Candidates are named as **strings** and reached through `Class.forName`, so a missing class is a
caught `ClassNotFoundException` rather than a link error while the registry itself initialises. The
catch is `Throwable`: `UnsatisfiedLinkError` and `NoClassDefFoundError` are `Error`s, not
`Exception`s, and catching only `Exception` here is the single most common way this pattern fails.

"The app always starts and always makes sound" becomes a structural property of the registry, not a
runtime hope. Section 5 depends on this.

---

## 2. How `AudioEngine` restructures

### 2.1 `pump()` is deleted from the engine

The blocking read → decode → process → encode → write loop stops being an engine concern. What
survives of it moves inside `JavaSoundBackend`, where it belongs, along with priming, the cushion
regulator, the line-capacity sizing, and the `-Domega.audio.*` overrides.

`AudioEngine` becomes a callback body plus lifecycle:

```kotlin
class AudioEngine(private val chain: EffectChain) : AudioCallback {

    @Volatile var outputLevel: Float = 0f; private set        // with release ballistics, see 2.4
    @Volatile var outputPeakHold: Float = 0f; private set
    @Volatile var loadPercent: Float = 0f; private set        // DSP time / callback period
    @Volatile var worstCallbackMicros: Int = 0; private set
    @Volatile var deadlineMisses: Int = 0; private set

    override fun render(input: FloatArray, output: FloatArray, frames: Int) {
        val t0 = System.nanoTime()
        try {
            System.arraycopy(input, 0, output, 0, frames)
            chain.process(output, frames)
            // one pass: clamp + peak, as today minus the quietest-sample search
        } catch (t: Throwable) {
            // An exception escaping into JNI is not recoverable. Silence, flag, let the monitor
            // thread tear the stream down.
            java.util.Arrays.fill(output, 0, frames, 0f)
            faultFlag = FAULT_DSP
        }
        // ... load metering off t0
    }
}
```

`start()`/`stop()` become thin: build a `StreamRequest`, ask the backend to `open()`, run the warm-up
pass (section 3.4), `stream.start()`. No thread is created here any more; the backend owns its
thread or is given one by the driver.

### 2.2 `CushionRegulator`: still needed, but not here

Answer to the direct question: **no, not when both directions share one driver clock.** ASIO, JACK,
and WASAPI exclusive on a single device give one clock, exactly `bufferFrames` in and
`bufferFrames` out per callback, forever. There is nothing to regulate, and a regulator splicing
frames into that path would be actively harmful.

It is still needed whenever the two directions have separate crystals:

- Java Sound, always.
- WASAPI (shared or exclusive) with input and output on two different physical devices — which is
  the common case for a guitarist with a USB interface in and headphones out of the motherboard.
- Any future "our thread, driver-woken" ALSA adapter opened on two different cards.

So it moves down into the `ANY_PAIR` adapters. The class itself is unchanged, `CushionRegulatorTest`
carries over untouched, and its cost stops polluting the latency figure of backends that do not use
it. This is the clearest example of the general rule: **Java Sound's problems become Java Sound's,
not the engine's.**

### 2.3 Priming and the transport counters

Priming disappears under a callback backend — the driver pulls, so there is nothing to pre-fill and
starting empty is correct. It survives inside `JavaSoundBackend`.

The counters change meaning:

| Today | Under the seam |
|---|---|
| `underruns`, `overruns` | `AudioStream.xruns` — one number, backend-reported, uniform across backends |
| `cushionFrames`, `minCushionFrames` | Java Sound diagnostics only; surfaced in the setup panel when that backend is selected, not in the main status bar |
| `driftCorrections` | same |
| `worstBlockMicros` | `worstCallbackMicros` — now far more important, because it is measured against a hard 2.67 ms deadline |
| — | **`loadPercent`** — new, and the single most useful number on screen. Every DAW has it. |
| — | **`deadlineMisses`** — callbacks that took longer than their own period |

`latencyMs` stops being computed by the engine and comes from `StreamFormat`, which is more honest
still than today's version: today's "measured, not assumed" cushion figure is replaced by a
driver-reported breakdown plus an optional real loopback measurement.

### 2.4 Meter ballistics — a bug the migration would otherwise introduce

`outputLevel = peak` is written once per block and polled every 33 ms. At 5.8 ms blocks the UI sees
one block in six. At 2.67 ms it would see one in twelve, and the meter would start missing
transients outright — on a guitar, that is most of the interesting signal.

Fix at the same time, and it stays a single unsynchronised store: the audio thread keeps a running
value with instant attack and an exponential release applied per callback, plus a separate ~1.5 s
peak hold. The UI just reads two floats. Attack fast, release slow, peak held — which is what the
rule about meter ballistics asks for anyway, and today's code does not do.

### 2.5 `dsp/` — what carries over, and the two things that do not

The algorithms carry over unchanged. Two things do not, and both are real:

**(a) `dsp` depends on `audio` and hard-codes 44.1 kHz.** This is already a layering violation
against the stated `ui -> dsp <- audio`, and under a 48 kHz backend it is a correctness bug:

- `Delay.kt:25` — `FloatArray((AudioConfig.SAMPLE_RATE * 1.1f).toInt())`, 48 510 samples. At 48 kHz
  a 1000 ms delay clamps to ~1010 ms (survivable); at 96 kHz it silently clamps to ~505 ms, so the
  knob reads 1000 ms and the echo arrives at half that. A control that lies about the audio.
- `Overdrive.kt:51` — the one-pole coefficient uses the constant. At 48 kHz the tone control lands
  ~9 % high; at 96 kHz, an octave off.
- `Reverb.kt:60` — rescales correctly *provided it is given the right rate*, which it currently
  cannot be.
- `Delay.TIME_SMOOTHING` and `MasterVolume.SMOOTHING` are per-sample time constants tuned at
  44.1 kHz. At 96 kHz the mute ramp halves to ~5 ms and the delay-time glide doubles in speed.
  Not fatal; still wrong.

Fix: add a lifecycle call to `AudioProcessor`, invoked on a normal thread before the stream starts,
where all allocation and coefficient derivation happens.

```kotlin
interface AudioProcessor {
    /** Called off the audio thread before streaming. All allocation happens here. Then [reset]. */
    fun prepare(sampleRate: Float, maxBlockFrames: Int) {}
    fun reset()                       // unchanged: cheap clear, may run just before start
    fun process(buffer: FloatArray, count: Int)
}
```

and delete every `AudioConfig` import from `dsp/`. That fixes the layering violation as a side
effect, and it is Stage 0 work: it can ship today, on Java Sound, with no natives in sight.

**(b) One hot loop allocates.** `Reverb.process` does `for (comb in combs)` and
`for (allpass in allpasses)` over `List<Comb>` / `List<Allpass>` **inside the per-sample loop**.
Kotlin compiles that to `iterator()`, so it is two `ArrayList$Itr` allocations *per sample* —
96 000 objects/second at 48 kHz whenever reverb is on. Escape analysis often removes them once C2
has compiled the method, but "often" is not a realtime guarantee, and the window before C2 kicks in
is exactly stream start. Change both to `Array<Comb>` / `Array<Allpass>`, which Kotlin compiles to
an indexed loop with no iterator. Same for `EffectChain.effects`, though once per block that one is
cosmetic.

Everything else in `dsp/` — the Freeverb structure, the fractional-read delay, the `tanh` clipper,
the mute ramp, the `Parameter` volatile-float contract — is untouched.

`Overdrive`'s per-sample `Math.tanh` is the only transcendental in the sample path and is worth
watching once load is measurable; at ~1 % total load there is no case for approximating it now.

### 2.6 `AudioConfig` splits

- `AudioConfig` keeps unit conversion, but the functions take a rate rather than reading a constant.
  `SAMPLE_RATE` is deleted. `BLOCK_FRAMES` is deleted — block size now comes from the driver.
- `JavaSoundConfig` gets `CAPTURE_BUFFER_MS`, `PLAYBACK_BUFFER_MS`, `CUSHION_MS`,
  `DRIFT_CORRECTION` and the `-Domega.audio.*` overrides, which become that backend's business.
- A new persisted `AudioSettings` (backend id, device ids, channels, rate, buffer frames) is the
  GUI's model and is written to disk. Not a compile-time object; see section 4.7.

---

## 3. Threading contract at a 2.67 ms deadline

This replaces the "Threading contract" section of CLAUDE.md when the seam lands.

### 3.1 The thread is not ours, and it is a Java thread

Under ASIO and JACK the callback arrives on a thread the native side created. To run Kotlin on it,
JNI must `AttachCurrentThreadAsDaemon` it. Two things follow, and the second is the central risk in
this whole document:

1. **Attach once**, at stream start or on the first callback, and cache `JNIEnv*` and the
   `jmethodID`. Attaching is a hundreds-of-microseconds, allocating operation that interacts with
   safepoints. Attaching per callback is an instant failure.
2. **An attached thread is a Java thread, and Java threads stop at safepoints.** There is no way to
   opt out. A stop-the-world young collection stops the audio callback. This cannot be prevented,
   only made rare (3.5).

Thread priority must be set **natively**, not from Java. `Thread.MAX_PRIORITY` is close to
meaningless on Windows and Linux. Windows: `AvSetMmThreadCharacteristics("Pro Audio")`, which puts
the thread in MMCSS's audio class. Linux: `pthread_setschedparam` with `SCHED_FIFO`, which requires
`rtprio` limits the user may not have — see section 7.

### 3.2 Crossing the boundary

One JNI transition per callback, no more. Buffers are **pre-allocated direct `ByteBuffer`s** created
at open, with the native side writing at a fixed address and the Java side reading through a cached
`FloatBuffer` view (or `Unsafe`/`VarHandle` if profiling demands it). Do **not** use
`GetPrimitiveArrayCritical` to pin `float[]`: it can block while a collection is in progress, which
converts a GC pause into a dropped buffer with extra steps.

No JNI object allocation in the callback path — no `NewObject`, no `NewStringUTF`, no local-ref
churn beyond the cached ones. Error reporting from native is an `int` written to a shared field.

### 3.3 Forbidden in `render()`

Stricter than today's list, and each item has a reason:

- **Any allocation.** Including boxing, capturing lambdas, iterators (see 2.5b), varargs, string
  concatenation, `String.format`, and `Optional`. Allocation is not slow — it is that allocation is
  what triggers the collection.
- **Locks of any kind**, `synchronized`, `ReentrantLock`, and also unbounded CAS retry loops.
- **`Thread.sleep`, `park`, `yield`, `Object.wait`.**
- **Any I/O, including `println`.** This is where a debugging session breaks the product.
- **Exceptions as control flow.** One `try/catch (Throwable)` wrapping the whole body, which fills
  silence and raises a flag. An exception escaping into JNI is not well defined.
- **Class loading.** The first call into a not-yet-loaded class does file I/O. Mitigated by 3.4.
- **Reflection.** `EffectPanel` reflects over `parameters`, which is UI-side and fine; nothing on
  the audio path may.
- **Lazy initialisation** of any kind, including Kotlin `by lazy` and `object` initialisers reached
  for the first time mid-stream.

`System.nanoTime()` is permitted and needed — it is a vDSO/QPC read at ~20–25 ns and is what the
load meter is built on.

### 3.4 The warm-up pass — mandatory, not an optimisation

At stream open, on the opening (non-realtime) thread, after `prepare()` and before `stream.start()`:

run the full chain over a few hundred blocks of silence at the negotiated block size, with every
effect temporarily enabled.

This loads every class, populates every branch profile, gets the hot methods through C1 into C2, and
forces every lazy initialiser. Without it, the first second of streaming runs interpreted and
class-loading against a 2.67 ms deadline, and the app will xrun on start every single time — which
users read as "it's broken", not "it warms up".

Cost: a few tens of milliseconds, hidden behind the Start button.

### 3.5 GC exposure — stated plainly

The audio callback is a Java thread; **it will be stopped by a stop-the-world pause.** G1 young
pauses on a small heap typically land at 1–5 ms. Against a 2.67 ms period, a 5 ms pause is two lost
buffers — an audible click.

The mitigation is to make collections rare, not fast:

1. **Zero steady-state allocation on the audio path** (3.3), and near-zero on the UI path. The 30 Hz
   timer currently allocates via `String.format` three times a second plus repaint garbage; that is
   a few hundred KB/s, which with a generous young generation is minutes between collections.
   Worth tightening anyway: format into a reused `StringBuilder`, and only when the text changed.
2. **Choose the collector deliberately, and ship it.** Options:
   - *SerialGC with a large young gen* — pause time scales with the *live* set, which here is a few
     MB of Swing plus a 200 kB delay line. Typically well under 1 ms. Simple, predictable, and my
     default recommendation if we stay on a JDK 11-era runtime.
   - *Generational ZGC* — sub-millisecond pauses by design, needs JDK 21. Best answer if we bundle a
     runtime (section 5), which we should.
   - *EpsilonGC* — no collector at all. Only viable with genuinely zero allocation, which Swing
     rules out.
   - Also `-XX:+AlwaysPreTouch` so first-touch page faults do not land mid-stream.
3. **Measure it.** `-Xlog:gc` during a loopback selftest, and correlate pause outliers against
   `deadlineMisses`. Falsifiable, like the underrun counter already is.
4. **Survive it.** The callback fills silence and increments `deadlineMisses` rather than emitting
   whatever was in the buffer. One quiet 2.67 ms gap is far less objectionable than a burst of
   stale samples, and it makes the failure countable.

### 3.6 UI ↔ audio, still lock-free

**UI → audio** is unchanged in spirit and is already correct: `Parameter.value` and
`AudioProcessor.enabled` are `@Volatile` and a knob turn is one unsynchronised store. A `float`
store is atomic on every platform we target. If some future effect ever needs two parameters to
change *together*, do not add a lock — publish an immutable snapshot object through a single
volatile reference, allocated on the UI thread (allocation there is fine; the audio thread only
reads a reference).

Smaller blocks make parameter stepping *better*, not worse: the staircase from applying a knob value
once per block gets finer as the block shrinks. No change needed.

**Audio → UI** stays plain volatile scalars: `outputLevel`, `outputPeakHold`, `loadPercent`,
`worstCallbackMicros`, `deadlineMisses`, `xruns`. Polled at 30 Hz.

For anything that is not a scalar — "device lost", "xrun at t", "fault code 3" — add a
**single-producer/single-consumer ring of pre-allocated slots** with a volatile write index. The
audio side stores into a slot and bumps an index; the monitor thread drains it. No allocation, no
lock, and a full ring drops the oldest event rather than blocking. This is what keeps the "no I/O
on the audio thread" rule intact when something goes wrong, which is precisely when it is most
tempting to `println`.

### 3.7 What Cid's numbers would change here

- **If 64 frames is achievable** (1.33 ms period): everything above tightens by 2×, and the collector
  choice moves from "should" to "must". Bundling a JDK 21 runtime with generational ZGC stops being
  optional.
- **If only WASAPI shared is available**, with a 10 ms device period: the round trip lands at 22–24 ms
  and the requirement is missed regardless of anything in this document. `IAudioClient3`
  low-latency mode gets ~2.67 ms periods on many endpoints, which would put us back in range —
  whether this machine's endpoints support it is a measurement, not a guess.
- **If input and output must be separate endpoints** (no single duplex device), `CushionRegulator`
  comes back into the low-latency path with a latency cost of its own, and the two-clock problem has
  to be solved at 128 frames rather than at 160 ms of buffer. That is a materially harder problem
  and would deserve its own design.
- **If the only interface on this machine is onboard Realtek**, the honest answer is likely "10–12 ms
  is not reachable, buy a class-compliant USB interface with a real ASIO driver". That is a
  legitimate outcome and the GUI is designed to say it (4.6).

---

## 4. What the GUI must grow

### 4.1 Where it goes

Not in the device bar. Six-plus controls (driver, device or device pair, input channel, output
channels, rate, buffer) would swamp a pedal board, and they are *setup*, not performance. The pedal
board is what you look at while playing.

- The existing device bar collapses to a **one-line summary plus a gear button**:
  `ASIO · Focusrite USB · 128 frames · 11.4 ms   [⚙ Audio Setup]   [Start]`
- The gear opens a **non-modal** `JDialog` owned by the main frame. Non-modal deliberately: Swing
  modality plus an unfamiliar Linux WM is the flakiest combination in this codebase's future, and
  the user should be able to watch the main window's readouts while adjusting.
- Changes are staged and applied on **Apply**, which stops and restarts the stream behind a short
  mute ramp. Live-editing a buffer size is not a thing any backend supports.

### 4.2 The setup panel

```
┌ Audio Setup ───────────────────────────────────────────────────┐
│  DRIVER   [ ASIO                            ▾]                 │
│  DEVICE   [ Focusrite USB ASIO              ▾]  [Control Panel]│
│  INPUT    [ 1 · Input 1                     ▾]                 │
│  OUTPUT   [ 1-2 · Monitor L/R               ▾]                 │
│  RATE     [ 48000 Hz                        ▾]                 │
│  BUFFER   [ 128 frames   ·   2.7 ms         ▾]                 │
│  ────────────────────────────────────────────────────────────  │
│  ROUND TRIP        11.4 ms   reported by driver                │
│  [████████████████████│░░░░░░░░░░░]  within 10-12 ms target     │
│  in 4.2 + buffer 2.7 x2 + out 1.8                              │
│  ────────────────────────────────────────────────────────────  │
│  ASIO driver loaded. 2 in / 4 out at 48 kHz.                   │
│                       [Measure…]   [Cancel]   [Apply]          │
└────────────────────────────────────────────────────────────────┘
```

**DRIVER** lists *every* backend, including unavailable ones, greyed with their reason appended:
`WASAPI (exclusive) — omega_wasapi.dll not found`. Never hide an option: a hidden backend is
indistinguishable from a broken build, and the reason string is the whole diagnostic. Swing has no
per-item disable, so implement it in a `ListCellRenderer` plus a `ComboBoxModel.setSelectedItem`
that refuses unavailable entries.

**DEVICE rows are backend-shaped.** `Pairing.SINGLE_DEVICE` (ASIO, JACK) shows one device combo plus
channel pickers. `Pairing.ANY_PAIR` (Java Sound, WASAPI) shows two device combos. The panel rebuilds
those rows when the driver changes — a two-column `GridBagLayout` with the label column
right-aligned and fixed width, so the layout does not jump between shapes.

**INPUT channel is mandatory, not cosmetic.** Today's `Pcm.decode` mixes all channels to mono; on an
8-in interface that mixes in seven unused inputs and attenuates the guitar by 8×. Under the seam the
adapter takes one named channel.

**BUFFER shows both units, always** — `128 frames · 2.7 ms` — with the milliseconds recomputed from
the *selected* rate, not the running one. Entries come only from `DeviceCapabilities.bufferSizes`.
A combo, not a knob: legal sizes are a discrete set of five or six values, and a knob for that is
worse than a list. Consistency of *look* with the pedal board, not of widget type.

If the backend exposes exactly one size (WASAPI shared without `IAudioClient3`), show it as a single
disabled entry — the user can then see *why* they cannot get there, which is more useful than an
empty control.

### 4.3 The latency readout

Three separate things, labelled as three separate things:

1. **Reported** — `StreamFormat.reportedRoundTripMs`, explicitly attributed to the driver, with the
   breakdown underneath. Includes the driver's safety offsets; excludes converter latency.
2. **Measured** — from the `Measure…` button, which runs the existing `--selftest --tone` technique
   in-app: emit a tone, require the user to loop output back to input (physical lead or virtual
   cable), demodulate, report the true cable-to-cable figure. Shown beside the reported value when
   both exist. This is the only thing that catches a driver that lies, and the project already has
   the technique — it should not be lost in the migration.
3. **The target gauge** — a custom-painted bar with the 10–12 ms band marked. Colour is
   `Theme.GOOD` inside, a new `Theme.WARN` amber above, and it is always accompanied by the number
   and a word ("within target" / "above target"), because colour alone must not carry meaning.

Painting notes, so it survives both platforms: derive every dimension from `getFontMetrics().height`
or a scale helper rather than fixed pixels (Windows does fractional per-monitor scaling; Linux
desktops vary between integer scaling, font DPI, and nothing); inset hairlines by 0.5 px and paint
under `VALUE_STROKE_PURE`, the idiom `Knob` already uses; keep `Font.SANS_SERIF` / `Font.MONOSPACED`
logical families and never name a family.

### 4.4 The status bar grows a load meter

Next to the existing OUT meter: `DSP 12%` with a small bar, peak-held over ~1 s. It is the number
that tells a user whether a smaller buffer is available to them, and it is what every DAW shows for
exactly that reason. Reuse `LevelMeter` with a load mode — the ballistics differ (audio wants fast
attack / slow release; load wants a 1 s peak hold), so it needs a mode flag rather than a copy.

Xruns stay on screen with the same "0 dropouts" framing as today, promoted rather than demoted:
under low latency it is the number that separates "low latency" from "low latency that works".

### 4.5 Device or driver disappearing mid-stream

Requirements, in order of importance:

1. **Never keep a dead handle.** `onDeviceLost` → EDT → stop, close, mute, state `DISCONNECTED`.
   Pedal settings are untouched.
2. **Never show a modal dialog for it.** A modal under an unhelpful WM can land behind the main
   window and read as a hang. Status bar goes `Theme.BAD` with one plain sentence:
   *"Focusrite USB was disconnected. Audio stopped."* plus a `Reconnect` button.
3. **Recovery is one click.** Start flips back to "Start", the setup panel re-enables.
4. **Optional auto-resume**, default on, toggleable: a background thread re-enumerates every ~2 s
   and restarts if the device with the same **stable id** reappears. Matching on index or display
   name is the bug here — USB indices shuffle. Java Sound has no stable id and gets name matching
   with the risk documented.
5. **`onFormatChanged` is not an error path.** When the user changes buffer size in the ASIO control
   panel, the driver resets and hands us a new format. Mirror it: update the combo, update the
   latency readout, keep playing. Do not fight the driver's own panel.

### 4.6 Honest messaging when the target is unreachable

Each case gets a specific sentence with a named remedy, not a number:

- **Java Sound selected** — persistent amber banner in the setup panel: *"Java Sound cannot reach
  10–12 ms on this platform; expect 150–200 ms. Use it for testing and device discovery."* Amber,
  not red: it is a valid fallback, not a failure.
- **Smallest legal buffer still above target** — *"The smallest buffer this device offers is 480
  frames (10.0 ms), giving about 24 ms round trip. Reaching 12 ms needs an interface with a
  dedicated ASIO driver."*
- **Load above ~70 %** — *"Processing is using 74 % of each buffer. Increase the buffer, or switch
  off Reverb."* Name the actual remedy.
- **Exclusive mode about to be taken** — first time an exclusive backend is applied: *"WASAPI
  exclusive takes sole control of this device. Other applications will lose audio while Omega is
  running."* Once, remembered.

### 4.7 Defaults, first run, persistence

**First run**, no config file:

- Pick the lowest-`rank` **available** backend, its default device, and that device's
  **`preferredBufferFrames`** — not its minimum. A first run that xruns is worse than a first run at
  14 ms.
- **Do not auto-start.** An app that starts making noise at launch, into an output the user has not
  confirmed, is hostile. Keep the explicit Start button.
- Say what was chosen and where the dial is:
  *"ASIO · Focusrite USB · 256 frames · 14 ms. Lower the buffer in Audio Setup to reach 10–12 ms."*

**Persistence** to a plain properties/JSON file under the resolved app-data directory
(`%APPDATA%\Omega\audio.properties`, `$XDG_CONFIG_HOME/omega/audio.properties`) — a file the user
can delete when a bad setting bricks startup, plus a `--reset-audio` flag in `Main` for the same
reason. `java.util.prefs` is worse here precisely because it is not a deletable file on Windows.

**Safe mode.** Write a `cleanShutdown` flag on normal exit and clear it on start. If it is missing
at startup, do not silently reapply a 64-frame buffer that may have been what crashed: start at
`preferredBufferFrames` and say so. Standard DAW behaviour, and cheap.

**Missing device at startup** — fall back and *say so* rather than silently substituting something
else.

### 4.8 Keeping the look

Everything above uses `Theme` as it stands: `Theme.PANEL` on a rounded rect painted the way
`EffectPanel.paintComponent` does it, `Theme.LABEL_FONT` for the small caps row labels,
`Font.MONOSPACED` for every numeric readout (already the idiom for `transport`). One addition is
needed: `Theme` has `GOOD` and `BAD` but no third state, and this design leans on "works, but not to
target" repeatedly. Add `WARN` as an amber distinct from `ACCENT` — `ACCENT` already means "this
knob has a value" and overloading it would blur both.

---

## 5. Packaging and distribution

Native artifacts end the single-jar story as the *primary* artifact. They do not have to end it as
a *fallback* artifact, and that distinction is the recommendation.

### 5.1 Options

| Option | What it is | Cost |
|---|---|---|
| **(a) Fat jar per platform, classified** | `-windows-x64`, `-linux-x64`, each carrying its own natives | Loses "one download"; simple; good CI artifact |
| **(b) JavaCPP-style platform artifacts** | Thin jar + per-platform dependency jars resolved by classifier | Good for a *library*; ceremony for an app; still needs extraction at runtime |
| **(c) One universal jar, runtime extraction** | All platforms' natives inside, extracted and `System.load`ed on first use | Keeps one download. Extraction is where it bites: `/tmp` mounted `noexec` on hardened Linux, antivirus quarantining a freshly written DLL, concurrent instances racing on the same path, stale files after upgrade |
| **(d) jlink/jpackage app-image + installer** | Per-platform bundle with a trimmed, pinned JRE and natives already on disk | Three artifacts, per-platform build machines (jpackage cannot cross-build), 50–80 MB each |

### 5.2 Recommendation

**Primary: (d) jpackage app-image per platform. Fallback tier: keep today's universal jar, Java
Sound only, with no natives at all.**

The decisive argument is section 3.5. This design needs a *specific* JVM version and *specific* GC
flags — the collector choice is part of the realtime story. You cannot get that from whatever JRE a
user happens to have, and `java -jar` gives you no way to insist. jpackage also removes the
extraction failure modes in (c) entirely, because the natives are installed files, and it removes
"install a JDK first" from the getting-started path for a consumer audio app.

Keeping the existing `fatJar` task unchanged costs nothing and preserves `java -jar` +
`--list-devices` + `--selftest` as the diagnostic path on any machine. The single-jar property is not
lost; it is **demoted to the fallback tier**, which is exactly what the degradation requirement asks
for anyway.

If (d) proves too heavy for CI, (a) is the acceptable retreat and (c) is the one to avoid on Linux.

**[DEPENDS ON CID]** If ASIO is chosen: the Steinberg ASIO SDK cannot be redistributed. The
packaging consequence is mine to state even though the licence call is his — if a prebuilt
ASIO-capable native cannot ship, then either the installer builds/fetches it, or the app ships
without ASIO and degrades to the next backend. That is a distribution gate, and it should be settled
before Stage 2 rather than discovered during it.

### 5.3 Degrading when the native will not load

This is architectural, not a `catch` at the call site (see 1.4). The properties that must hold:

- `Backends.all()` never throws and never returns empty.
- Load failures are caught as `Throwable` (`UnsatisfiedLinkError` and `NoClassDefFoundError` are
  `Error`s) and become `Unavailable(reason)` entries with the reason preserved verbatim.
- `JavaSoundBackend` is in the core jar with no optional dependency, so it cannot fail to be present.
- First-run selection therefore lands on Java Sound on a machine where natives fail, with the reason
  visible in the driver combo.
- Add **`--list-backends`** beside the existing `--list-devices`, printing each backend, its
  availability, and its reason. It is the fastest possible answer to "why is it 200 ms on this
  machine".
- **Test the fallback deliberately**: rename the DLL/`.so` and confirm the app starts, streams on
  Java Sound, and explains itself. A degradation path that has never been exercised is a degradation
  path that does not work.

Known load failures to expect: missing VC++ redistributable, 32/64-bit mismatch, antivirus,
SELinux/`noexec`, and glibc version skew on Linux (build against an old glibc or it fails on LTS
distros).

---

## 6. Staged migration

Guiding rule: **the app is never broken for more than one commit**, and no stage depends on Cid's
answer until Stage 2.

### Stage 0 — prep (no natives, ships immediately, zero risk)

- Add `AudioProcessor.prepare(sampleRate, maxBlockFrames)`; delete every `AudioConfig` import from
  `dsp/`; derive Delay's line length, Overdrive's coefficient, Reverb's tunings and both smoothing
  constants from the passed rate. Tests call `prepare(44_100f, 256)`.
- `Reverb`'s comb/allpass lists → arrays (removes ~96 k allocations/s).
- Meter ballistics: attack/release + peak hold on `outputLevel`.

All of it is a real improvement today and all of it survives every later decision. **Do this first
regardless of what Cid finds.**

### Stage 1 — the seam, still Java Sound only

- Introduce `AudioBackend` / `AudioStream` / `AudioCallback` / `StreamEvents`.
- Implement `JavaSoundBackend` behind it, absorbing `pump()`, priming, `CushionRegulator`, `Pcm`,
  and the line-capacity sizing.
- Restructure `AudioEngine` into the callback body of section 2.1.
- Retarget `--selftest` at the seam; add `--list-backends`.

Latency and behaviour must come out **identical**, verified by the same loopback measurement. Doing
the risky refactor with no native in play means any regression is unambiguous.

**The two-thread capture split lands here, inside `JavaSoundBackend`** — capture thread, playback
thread, SPSC ring between them, so a 15.6 ms poll on one side cannot stall the other. Position it
correctly: it is **throwaway for the low-latency goal** (no native backend will ever use it) but
**not throwaway for the fallback tier**, which is a shipping product on every machine where natives
fail. Timebox it, keep it inside the adapter so it cannot leak into the engine, and if Cid comes
back early with good numbers, defer it — it is the one piece here that can be dropped without
consequence to the main goal.

### Stage 2 — the first native backend **[DEPENDS ON CID]**

- One backend class behind the existing seam.
- Behind `-Domega.backend=` at first, plus a minimal driver + buffer combo in the existing device bar
  so it is usable. Java Sound stays the default until measurement says otherwise.
- Warm-up pass, load meter, xrun plumbing, native thread priority.

If the seam is right, this stage is one new class and no changes anywhere else. If it is not, this
stage is where we find out — which is why Stage 1 exists separately.

### Stage 3 — the full GUI

Setup panel, driver combo with reasons, buffer combo in both units, latency readout and gauge,
control-panel button, device-loss and format-change handling, persistence, first-run defaults, safe
mode.

### Stage 4 — packaging

jpackage app-images and installers; pinned runtime and GC flags; keep the universal jar building.
Last, because it is the least reversible and the most CI-shaped.

### Stage 5 — Linux native

Section 7. Do not defer this indefinitely: **a seam validated by exactly one native implementation
is probably the wrong seam**, and the second one is how you find out.

### Throwaway vs survives

| Survives | Throwaway, or demoted to the fallback tier |
|---|---|
| All of `dsp/` (algorithms) | The `pump()` loop shape |
| The seam, and `AudioEngine.render()` | Priming (→ Java Sound only) |
| Every UI component: `Knob`, `ToggleSwitch`, `LevelMeter`, `Theme`, `EffectPanel` | `CushionRegulator`'s *location* (code survives, in `ANY_PAIR` adapters only) |
| The transport-counter idea, and `--selftest` as measurement | `AudioConfig.CAPTURE/PLAYBACK/CUSHION_MS` (→ `JavaSoundConfig`) |
| `CushionRegulatorTest`, `PcmTest`, `EffectsTest` | `Pcm`'s 16-bit-interleaved-only assumption (grows into per-backend codecs) |
| The two-thread capture split — **for the fallback tier only** | The two-thread capture split — **for the 12 ms goal** |

---

## 7. Linux forward-compatibility

Nobody has run this on Linux. The seam must not quietly become Windows-shaped, so here is what it
must *not* assume, each with the Linux backend that would otherwise break it:

- **That a device has both directions.** JACK has ports, not devices; ALSA has separate `hw:0,0`
  capture and playback PCMs. Handled by `BackendDevice` carrying independent in/out channel counts
  and by `Pairing`.
- **That the sample rate is ours.** JACK's rate is the server's and is non-negotiable. This is
  precisely why `prepare(sampleRate)` in Stage 0 matters, and why `DeviceCapabilities` has
  `fixedSampleRate`.
- **That the buffer size is ours, or that it is fixed.** The JACK server owns it and can change it
  mid-session. `onFormatChanged` is therefore not an ASIO quirk — it is the shared mechanism, and
  JACK is the reason it is in the interface rather than in the ASIO adapter.
- **That every backend has a control panel.** `openControlPanel` is an optional capability with a
  default no-op, and the GUI **hides** rather than disables the button when absent.
- **That start/stop is symmetric.** A JACK client can be zombified by the server; the adapter must
  surface that as `onDeviceLost` and not hang in `close()`.

### What would plug in

- **JACK** — `jack_set_process_callback` is a true callback; latency comes from
  `jack_port_get_latency_range`. It maps onto this seam almost exactly, which is a good sign: the
  seam should make JACK the *easy* case.
- **PipeWire** — now default on modern distros and it ships a JACK-compatible shim (`libjack.so`).
  Targeting the JACK API gets PipeWire for free on Fedora and Ubuntu 22.10+. This makes the Linux
  story considerably cheaper than it looks.
- **ALSA `hw:` direct** — `snd_pcm_hw_params` with period and buffer sizes we choose, `poll()`-driven.
  Not a true callback, so the adapter runs its own thread and *presents* a callback — the same shape
  as WASAPI exclusive, which is itself evidence the seam is right. Needs `SCHED_FIFO`, which needs
  `rtprio` limits in `/etc/security/limits.d`; without them, expect xruns under load. That is a
  user-facing prerequisite and the GUI should say so when ALSA is selected and the priority request
  failed.
- **PortAudio**, if Cid picks it, covers Windows and Linux with one binding and makes Stage 5 nearly
  free — at the cost of a layer between us and the driver, and of PortAudio's own buffering choices
  inside our latency budget.

### Cheap thing to do early

Run the existing `--list-devices` and `--selftest` under a Linux VM or WSLg **during Stage 1**,
before the seam hardens. The fallback tier should be known-good on both platforms before natives
complicate the picture, and the existing "no devices found — check PulseAudio isn't holding the
card" copy in `refreshDevices()` deserves to be verified rather than assumed.

---

## 8. Risk register

Ordered by how likely they are to cost real time.

1. **The JVM holding a 2.67 ms deadline. High risk; solvable, but not by default.**
   It requires all of: bundled JVM, chosen collector, warm-up pass, zero steady-state allocation,
   MMCSS/`SCHED_FIFO` priority, and one JNI transition per callback. Miss any one and the symptom is
   intermittent xruns under load — the hardest bug class there is, because it is unreproducible on
   demand. Expect more than one iteration. Mitigation: `deadlineMisses` and `loadPercent` on screen
   from Stage 2, so the failure is countable rather than anecdotal — the same principle that turned
   "it crackles" into `underruns: 4 (0.13/s)`.

2. **GC inside the native callback. Certain to happen; the question is how often.**
   The attached callback thread is a Java thread and cannot opt out of safepoints. A 5 ms G1 young
   pause against a 2.67 ms period is two lost buffers. There is no fix, only rarity (3.5). **If
   measurement shows pauses we cannot make rare enough, the fallback is 256 frames, and the round
   trip lands at 14–17 ms — the requirement is then missed by 2–5 ms.** That is the most likely way
   this project ends up short, and it should be measured early rather than discovered at Stage 4.

3. **The 10–12 ms target may be decided by hardware we do not control. [DEPENDS ON CID]**
   Round trip includes ADC and DAC converter latency (~0.5–1.5 ms each) and USB transfer frames, and
   typical driver safety offsets run 3–5 ms *per direction*. If this machine's only input is onboard
   Realtek, the honest answer is likely "not reachable — a class-compliant USB interface with a real
   ASIO driver is the requirement". The GUI in 4.6 is designed to be able to say that.

4. **Native loading across machines. Moderate risk, fully mitigable.**
   VC++ redistributable, bitness, antivirus, `noexec` `/tmp`, glibc skew. The mitigation is section
   5.3, and its value is entirely contingent on being *tested* by deliberately breaking the native.

5. **ASIO redistribution. [DEPENDS ON CID], but with a packaging consequence (5.2).**

6. **Exclusive mode takes the device from the rest of the OS.**
   The user loses system audio while Omega runs, and some drivers leave the device stuck until
   reboot if it is not released. The existing `exitProcess(0)` in `MainWindow.windowClosing` becomes
   *more* important, not less, and the native must release in a `finally` plus a shutdown hook. This
   is the "anything holding a device handle must release it on close, deliberately and verifiably"
   rule, and under exclusive mode the cost of getting it wrong is a reboot.

7. **Mono end to end.** Not in scope, but a user with a low-latency interface will expect real stereo
   out. `StreamRequest` already carries channel counts so widening `AudioProcessor.process` to two
   buffers later does not reopen the seam.

---

## 9. Not verified

- Nothing here has been compiled or run. It is a design against the code as it stands on
  2026-08-09.
- The 44.1 kHz couplings in `dsp/` (2.5a) were found by reading — `Delay.kt:25`, `Delay.kt:67`,
  `Overdrive.kt:51`, `Reverb.kt:60` — and their audible consequences at 48 kHz are reasoned, not
  measured.
- The per-sample iterator allocation in `Reverb.process` (2.5b) is from the Kotlin `for`-over-`List`
  lowering; whether escape analysis eliminates it in this specific loop after C2 has not been
  checked with a profiler.
- Every latency figure in section 0 is typical-case arithmetic, not a measurement on this machine.
  Cid's spike replaces them.
- No Linux behaviour in this document has been observed. Section 7 is what the seam must permit, not
  a report.
