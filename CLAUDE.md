# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone realtime guitar effects processor with a Swing GUI. Audio comes in from a selected
capture device, runs through a rearrangeable chain of pedals — gate, compressor, EQ, gain,
overdrive, an amp capture, a cabinet, FX slots, delay, reverb — then a master volume, and goes back
out to a selected playback device. **Split mode** adds a second board fed the same input and summed
with the first. **Loop mode** adds a loop box above the boards: four lanes of a minute, recorded
after the master and played back either under the guitar or into the start of the chain.
`docs/WhatsDone.md` records what has been delivered.

Despite the name, this is **not** an IntelliJ Platform plugin — "plugin" here means a guitar effect.
`build.gradle.kts` applies `kotlin("jvm")` and `application`; there is no `plugin.xml` and no
IntelliJ Platform dependency. Don't assume IDE APIs are on the classpath.

**Public at `https://github.com/Sam-GN/Omega-Plugin` since 2026-10-15** — the URL `ui/Licences.kt`
and README.md name, because GPLv3 §6 needs a binary's source to be reachable. `.github/workflows/`
holds a build workflow (Windows and Ubuntu 22.04, the latter also packing and smoke-testing the
AppImage) and a release workflow that runs only after the build has passed on both, then packages the
Windows zip and the Linux AppImage in parallel and publishes both in one draft. The Ubuntu version is
the AppImage's glibc floor — do not move it to `-latest`. **Every push to
`main` is a release**: the version in `build.gradle.kts` becomes the tag, so every change bumps it
and adds a CHANGELOG entry, and the workflows fail a push that does not.

The first run found `gradlew` committed without its executable bit. Windows cannot set one on the
file, so git has to be told directly: `git update-index --chmod=+x gradlew` after any re-add of the
wrapper. `git ls-files -s gradlew` should read `100755`.

**Committing and pushing are the maintainer's, never an agent's.** Do the work, leave it in the
working tree, hand over the commands.

### The other documents

`CHANGELOG.md`, `README.md`, `CONTRIBUTING.md` and this file are at the root. `CONTRIBUTING.md` is
the short version of this file written for a stranger — the rules that get a patch turned down, and
nothing about why the code is shaped the way it is. When a rule here changes and it is one of those,
change it there too; when it is not, leave that file alone. Everything else is in `docs/`, and the
split there is worth knowing before adding to it — three different kinds of document that must not
be confused with each other:

| File | What it is |
| --- | --- |
| `docs/manual.md` | **The user manual**, written for a player who has never used the program: every control, every pedal, every menu item, and what to do when it goes wrong. The one document here meant for users rather than maintainers — README.md and the About dialog (`Licences.MANUAL_URL`) link to it. **When a control changes, change it here too.** Its table of contents links to its own headings, the one place in `docs/` a markdown link is allowed, because an anchor survives a move. |
| `docs/known-issues.md` | **Defects in code that already ships.** Nothing speculative. Each entry is a shortcoming in something a user can switch on today. |
| `docs/linux-compat.md` | **The Linux work plan**, numbered `F1`–`F11` against workstreams `W1`–`W7`, mostly built in 1.1.0. Its §0 is the current status and overrides the plan text below it. AppImage is the chosen format. |
| `docs/low-latency-backend.md` | The design behind the `AudioBackend` seam. History rather than a plan — the seam it describes is built. |
| `docs/virtual-io.md` | Routing Omega into and out of other applications. |
| `docs/WhatsDone.md` | What has been finished, against the original requirements. |
| `docs/update-check.md` | **The agreed plan for 1.8.0**, not built: a Check for updates button on About and an Update button that replaces the installed bundle, the only two times the program may touch the network. Its §11 lists what is still open; everything else in it is decided. |
| `docs/theme-optional.md`, `docs/profile-optional.md`, `docs/pickup-optional.md`, `docs/instrument-sampler.md` | **Proposals for things nobody has promised.** Written to review later. Do not treat as commitments or as a backlog. The last is the instrument sampler — a captured note played chromatically from a keyboard, and the guitar as its controller. |

Nothing in any of them is a markdown link — every cross-reference is prose in backticks, which is
what made moving them into `docs/` cost nothing. Keep it that way. The manual's own table of
contents is the one exception, noted above.

## Build and run

Use the Gradle wrapper (Gradle 8.10.2). On Windows/PowerShell use `.\gradlew.bat`; the Bash tool can
use `./gradlew`.

```powershell
.\gradlew.bat build          # compile + test + fat jar + launchers
.\gradlew.bat run            # launch the GUI, with the right JVM flags
.\gradlew.bat test
.\gradlew.bat test --tests "org.example.audio.LoopbackProbeTest"
```

**Java 21+ and ZGC are part of the product, not a preference.** The audio callback is an attached
Java thread and cannot opt out of a safepoint, so the collector decides whether a 1.45 ms deadline
holds. Measured at a 64-frame ASIO buffer with the full pedal chain and an allocation flood on a
side thread: **G1 moved 97.2 % of realtime, generational ZGC moved 100.0 %**. The 2.8 % G1 lost is
audible, and — this is the trap — `deadlineMisses` was **0 in both cases**, because a collection
stalls the thread *between* callbacks rather than making a callback slow. `realtimeRatio` is the
counter that catches it; watch that one.

Flags live in `gcArgs` in `build.gradle.kts` and reach `gradlew run` and the generated start scripts
through `applicationDefaultJvmArgs`. A manifest cannot carry VM options, so **`java -jar` gets none
of them** — hence `build/libs/omega.bat` and `omega.sh`, written next to the fat jar by the
`launchers` task. `Main.collectorWarning()` checks at startup and says so rather than running badly
in silence. If you add a diagnostic entry point, route it through the launchers or it will measure
the wrong JVM.

### Headless diagnostics

```
omega.sh --list-backends                                  # what loaded, what didn't, and why
omega.sh --list-devices                                   # devices per backend
omega.sh --selftest [--backend=asio] [--buffer=128] [--chain] [--split] [--stress] <in> <out> [secs] [--tone]
omega.sh --measure  [--backend=asio] [--buffer=128] <in> <out> [secs]
```

- `--list-backends` is the fastest answer to "why is it 200 ms on this machine".
- `--selftest` runs the real engine through the real seam. `--chain` enables every module that runs
  without a file on disk — everything but NAM, with the IR pedal on a synthesised cabinet and the
  amp on its four-stage voice — with the EQ's five bands all active and an FX slot set to Reverb,
  which is the heaviest thing a slot can hold, plus the rhythm section running 16ths at 240 BPM and
  the loop box with three lanes playing (one through the boards) and a fourth recording. It is the
  only way the DSP cost gets measured; without it you are testing the
  device path alone. **Keep it in step with the shipping chain**: it drifted to four pedals once and
  spent several versions quietly measuring less than half the app. `--split` runs that chain on a
  second board as well, summed, which is the application's split mode and its worst case without a
  file on disk.
  `--stress` floods the heap from a side thread — an idle machine holds almost any buffer size, so
  this is what separates "runs" from "stable". `--tone` emits 1470 Hz instead of passing the input
  through; without it the chain is empty and it is safe to point at speakers.
- `--measure` is the only number that is not somebody's claim. Patch a lead from the output back
  into the input and it reports the true cable-to-cable round trip; see `LoopbackProbe`.

There is no linter or formatter configured.

## Architecture

Three packages under `src/main/kotlin/org/example/`, layered strictly `ui -> dsp <- audio`:

- **`audio/`** — the backend seam and the callback body.
  - `AudioBackend.kt` defines the seam: a **duplex stream driven by one clock**, callback-driven,
    mono float in and out. `Backends` is the registry and the fallback ladder.
  - `audio/asio/` — the primary path on Windows. `IASIO` is reached by walking its COM vtable by
    hand through JNA; there is no Steinberg SDK and there must never be one (licence).
  - `audio/wasapi/` — exclusive, event-driven, two endpoints and two clocks.
  - `audio/jack/` — Linux's primary path, and PipeWire's through its JACK library. One clock, the
    server's realtime thread, so it is shaped like ASIO. Each client owning hardware ports is a
    device. `JackLibrary` tries PipeWire's shim **by path** before the system `libjack.so.0` when
    PipeWire is running: Debian and Ubuntu ship it off the library path, and a machine with jack2
    installed and no `jackd` would otherwise find the wrong one and give up.
  - `audio/alsa/` — Linux's always-present rung: `Default` through the sound server, or `hw:`
    straight to a card. Capture and playback are separate `BackendDevice`s (`in:`/`out:` ids),
    because `capabilities()` must know which direction it is answering for. The thread is ours.
    A same-rate pair runs in lockstep (read a period, render, write); a converted input runs
    `convertingLoop`, driven by the output, with the input going through a `Resampler` into a
    `CaptureReservoir`.
  - `audio/linux/` — `LinuxRt` (realtime via `SCHED_FIFO`, else rtkit over D-Bus) and
    `LinuxPlatform` (the OS check). **They are separate on purpose**: `LinuxRt` declares JNA
    interfaces, so touching it loads JNA, and the OS check is what has to work when JNA does not.
  - `audio/javasound/` — the bottom rung, always available. The old `pump()` loop, priming, line
    sizing and `CushionRegulator` all live here now, because they are answers to Java Sound's
    behaviour and to nothing else.
  - `AudioEngine` is now just the callback body plus lifecycle. `LoopbackProbe` measures true
    round-trip latency. `Pcm` is Java Sound's codec; other backends have their own.
- **`dsp/`** — `AudioProcessor` implementations plus `EffectChain`. All DSP works on **mono float
  samples in [-1, 1]**, in place; conversion happens only at the device boundary. `Biquad` is the
  shared second-order section (`Eq`, `DeEsser`); `FxSlot` is a module that holds one instance of
  every type it offers and runs whichever the dropdown selects.
  - **`dsp/rhythm/`** — the metronome. **The one thing here that generates audio rather than shaping
    it**, which is why `Transport` is not an `AudioProcessor` and not in `EffectChain`: those model a
    pedal the guitar passes *through*, and a click routed through the overdrive and the reverb would
    be absurd. `AudioEngine.render` mixes it in **after** `chain.process`. See "Adding to the rhythm
    section" below before touching it.
  - **`dsp/loop/`** — the loop box. `Looper` is four `Lane`s and one playhead, driven by three calls
    the engine makes around the chain each block; also not an `AudioProcessor`, because it touches
    the signal in two places. See "Loop mode" below.
- **`ui/`** — Swing. `Knob`, `ToggleSwitch`, `LevelMeter` and `BeatLamps` are custom-painted
  `JComponent`s; `EffectPanel` builds one pedal from any `AudioProcessor` by reflecting over its
  `parameters` list. `Board` is one pedal board — its effect instances, its `EffectChain`, its
  `ChainBoard`, and the per-board state that used to be fields of `MainWindow` (which file the NAM
  loaded, which FX slot is first) — and exists twice, because of split mode. `BoardStack` holds the
  `Stackable` panels in the scroll view: the `LoopBoard` above, the first board, the second board
  below. `ToolStrip` is the band between the top bars and the stack with the eye, the loop and the
  fork; `LoopWindow` is the frame the loop board moves into when popped out. `MainWindow` keeps what
  there is one of: the master, the device bar, the session, the presets, the looper.

  **The window opens in its final shape.** Anything the saved session changes about the picture —
  the eye, loop mode — is applied in the constructor with no animation (`refreshVisibility` snaps
  while the board has no width; `setLoop(atOnce = true)`), because there is nothing on screen for
  a fade to start from, and a fade that starts from nothing eases every pedal out of a point. The
  sound itself is restored after the first paint, for the NAM warm-up. The entrance is the logo:
  `Splash` holds it dead centre of the screen in a small frameless window from the moment `main`
  reaches Swing, the window's glass-pane veil paints the same logo at the same point and lifts
  over `VEIL_MILLIS`, logo out and window in, and the splash is dismissed on the veil's first tick,
  not on `windowOpened`, which arrives while the event thread is still busy behind it. Both
  surfaces draw through `Splash.paint`, which is what keeps them in register. Only the GUI path
  shows it.

  **Linux takes a different route to the same picture, and Windows must not be moved onto it.**
  X11 maps a window and asks for its paint later, as an event of its own, so on Linux: the splash
  and the frame get `Theme.BACKGROUND` as their native background (unset, X11 shows white);
  `main` builds the window only after `Splash.whenPainted`; the restore runs off the veil's first
  paint rather than an `invokeLater` that could beat it; and `liftVeilSettled` holds the veil until
  nothing in the window has moved for `VEIL_QUIET_MILLIS`, then fades a snapshot. Early frames
  there cost 45-65 ms against Windows' few, so the restore's pedal animations used to outlast the
  fade and play out in jumps. Quiet is judged by component events, not paints — the loop board's
  lanes repaint thirty times a second regardless. Every branch is behind `LinuxPlatform.isLinux`.

  **Borrowed components have to be opted in by name, one at a time.** The dark look is applied per
  instance — `DarkComboBoxUI.install(...)`, `DarkMenu.popup()`, `DarkTextField.style(...)` — not
  through UIManager keys, because Nimbus paints most controls through *painters* in its defaults that
  ignore the component's own colours. A new combo, popup or text field therefore arrives looking like
  stock Nimbus until it is added to those calls, and beside the styled ones that reads as a rendering
  fault. It has already happened once: the rhythm row shipped in 1.0.49 with two silver combos, a
  grey popup and a black bar across the tempo field.

  `DarkTextField.installClickAwayCommit` is on the toolkit rather than per field, so it reaches the
  editors built on demand. It moves focus and nothing else — every field here commits on `focusLost`,
  so moving focus *is* Enter, and firing the action too would commit twice.

  **A button fires on the release, never on `mouseClicked`.** AWT generates that event only when
  the pointer did not move a pixel between press and release, so a firm press that drifts is lost
  and the user clicks twice. `Clicks.onRelease` is the one place that does it right — press on the
  component, left release still inside it — and every custom button goes through it or through
  `PushButton`, which has the same rule built in.

  **Wheel notches belong to a gesture, not to what is under the pointer.** `WheelGesture` is the
  one place that decides: the first notch of a run picks its owner — the boards' scroll pane, a
  `Knob`, the loop board's time scroll — and notches under `GAP_MILLIS` apart go to that owner
  however the content has moved since, a knob passing a notch it may not take up to its parent.
  Real pointer motion ends the gesture (a toolkit listener, because content sliding under a still
  mouse fires enter and exit events but never a move). Anything new that turns on the wheel must
  ask `WheelGesture.take` first, or scrolling will stop on it.

### Threading contract

The deadline used to be 5.8 ms and is now **1.45 ms at 64 frames, 0.36 ms at 16**. The old rules
still apply; they are just no longer negotiable, and a few new ones join them.

**The thread is not ours.** Under ASIO the callback arrives on a thread the driver created, which
JNA attaches to the JVM. Two things follow. First, it is attached **once** at stream open with
`CallbackThreadInitializer(daemon = true, detach = false)` — attaching per callback is a
hundreds-of-microseconds allocating operation and an instant failure at this period. Second, an
attached thread is a Java thread and **stops at safepoints**; there is no opting out, which is what
makes the collector choice part of the design rather than a tuning knob.

Priority is set **natively**, on the callback thread itself, at the first callback:
`Rt.joinProAudio()` puts it in MMCSS's "Pro Audio" class. `Thread.MAX_PRIORITY` is close to
meaningless on Windows. `Rt.beginHighResolutionTimer()` raises the system timer to 1 ms for the life
of the stream and drops it on stop.

**Forbidden in `AudioEngine.render` and in any backend's callback**, each for a reason:

- **Any allocation** — including boxing, capturing lambdas, iterators (`for (x in list)` allocates),
  varargs, string concatenation and `String.format`. Allocation is not slow; allocation is what
  triggers the collection that stops the thread.
- **Locks of any kind**, and unbounded CAS retry loops.
- **`Thread.sleep`, `park`, `yield`, `wait`.**
- **Any I/O, including `println`.** This is where a debugging session breaks the product.
- **Exceptions as control flow.** One `try/catch (Throwable)` wraps the whole body, fills silence
  and raises a flag; an exception escaping into JNI is not well defined.
- **Class loading, reflection, and lazy initialisation** (`by lazy`, first touch of an `object`).
  Mitigated by the warm-up pass, which is why that is mandatory rather than an optimisation.

`System.nanoTime()` is allowed and needed — a QPC read at ~20 ns, and the load meter is built on it.

**Errors never travel on the callback thread.** The audio side does a volatile store of a flag; a
per-stream monitor thread at normal priority — allowed to allocate and log — turns it into a
`StreamEvents` call. That is what keeps the "no I/O on the audio thread" rule intact at the one
moment it is most tempting to break it. `kAsioResetRequest`, which is what the user changing the
buffer size in the driver's own control panel produces, arrives this way and is **not** an error:
it becomes `onFormatChanged`, the UI mirrors it, and the stream keeps playing.

**UI to audio** is unchanged and already correct: `Parameter.value` and `AudioProcessor.enabled` are
`@Volatile`, so a knob turn is one unsynchronised store. Smaller blocks make that *better*, not
worse — the staircase from applying a knob value once per block gets finer as the block shrinks. If
some future effect needs two parameters to change together, do not add a lock: publish an immutable
snapshot through one volatile reference, allocated on the UI thread.

**Audio to UI** is plain volatile scalars polled at 30 Hz: `outputLevel`, `outputPeakHold`,
`loadPercent`, `deadlineMisses`, `worstCallbackMicros`, `xruns`, `realtimeRatio`.

### Meter ballistics

`outputLevel` is no longer a per-block peak. At 64 frames the UI would see one block in twenty-two
and miss most transients — on a guitar, that is the interesting part of the signal. The audio thread
keeps the value itself: instant attack, exponential release (~150 ms), plus a slower peak hold
(~1.5 s), both expressed per callback so the ballistics do not change with the buffer size. It is
still two plain volatile floats.

### The warm-up pass is mandatory

`AudioEngine.warmUp` runs the full chain over 400 blocks of noise, with every effect temporarily
enabled, on the opening thread before `stream.start()`. Without it the first second runs interpreted
and class-loading against the deadline, and the app xruns on start *every single time* — which users
read as "it's broken", not "it warms up".

Three properties of it are load-bearing, and two were learned by getting them wrong:

- **It runs at a fixed 64-frame block, not the device's.** It used to use the device's block size,
  which made the cost `400 × bufferFrames` samples — linear in the buffer, for no benefit. Measured
  with a NAM capture in the chain: 236 ms at 64 frames, **7286 ms at 2048**. JIT compiles methods and
  loop bodies and neither knows how many frames a call carried, so the block size was never the thing
  that mattered. 64 is the buffer the deadline work was done at. Four blocks at the real size follow,
  to first-touch what `prepare` just allocated.
- **The full pass is owed once per class, not once per start.** Class loading and JIT last as long as
  the process, so repeat starts were redoing finished work — 271 ms became 26 ms once
  `warmedClasses` was consulted. That set is static, because that is the scope the property has, and
  is only added to after a pass that completed.
- **It must reach an `FxSlot`'s unselected types**, through `AudioProcessor.alternates`. Those are
  entered by changing a dropdown *while the stream runs*. The same applies to every pattern in
  `dsp/rhythm/`, which `warmUpRhythm` walks — that one is not in `EffectChain`, so nothing reaches it
  by accident.

It costs a few hundred milliseconds behind the Start button on a cold process — dominated by a NAM
capture if one is loaded — and a few tens of milliseconds after that. `AudioEngine.lastStartTiming`
breaks that down and logs it; `--selftest` prints it. **`engine.start` runs on a worker thread**, not
the event thread, so the window keeps painting and `onStartProgress` can report a stage and a
percentage; everything after it marshals back through `invokeLater`.

### Adding a backend

Implement `AudioBackend`, add its fully-qualified name **and its OS** to `Backends.OPTIONAL`, and
nothing else in the app changes. A backend is only constructed on its own OS, so a Windows user never
sees a JACK entry and the Windows picker is exactly what it was before Linux existed —
`BackendSeamTest` pins that in both directions. Rules the registry depends on:

- **It must never throw from the constructor or from `devices()`.** A backend that cannot run
  reports `Availability.Unavailable(reason)` and the reason goes in front of the user verbatim.
- The registry reaches candidates by `Class.forName` and catches **`Throwable`**, not `Exception` —
  `UnsatisfiedLinkError` and `NoClassDefFoundError` are `Error`s, and catching only `Exception` is
  the single most common way this pattern fails. `Backends.rootCause` then digs the real message out
  from under `InvocationTargetException`, because "InvocationTargetException: no detail" helps
  nobody.
- **Enumerate by capability, never by arithmetic.** `DeviceCapabilities.bufferSizes` must contain
  only sizes the driver will actually accept, *at the rate asked about* — hence
  `capabilities(device, sampleRate)`. Two measured reasons, both from this machine:
  - The Sonicake reports a 3.0 ms WASAPI minimum period and then refuses it with
    `AUDCLNT_E_INVALID_DEVICE_PERIOD`. So `WasapiBackend` probes with a real `Initialize`.
  - `Initialize` returning `S_OK` is still not proof: the AORUS HDMI output accepts a 144-frame
    request and reports a 192-frame buffer. `accepts()` therefore also requires
    `GetBufferSize() == requested`.

  ASIO's granularity encoding (-1 powers of two, 0 preferred-only, positive step) is in
  `legalBufferSizes`, which has tests.
- **Never round-trip a frame count through 100 ns units to compare it.** 264 frames at 44.1 kHz is
  59 863 945.578 units, which comes back as 263. Keep the frame count you asked for.
- **The engine runs at the output's rate; only the input is ever converted.** See `SampleRates`.
  On a two-clock backend that sets `convertsInput` (WASAPI, both modes) the picker offers the
  output's rates, and one the input cannot do is marked `C`: the capture endpoint opens at its own
  rate and its packets go through the chain's fixed-ratio `Resampler` on the way into the ring,
  which already absorbs uneven counts; the ppm drift between the two crystals is the render loop's
  existing correction, exactly as at equal rates. The output is never converted — the round trip
  is set by the hardware periods in milliseconds and the engine's rate only changes how many
  frames that is, and the render side has no ring to absorb a resampler's uneven counts. ALSA
  converts too since 1.7.0, but its lockstep loop has nowhere to put uneven counts either, so a
  converted ALSA stream runs `convertingLoop` instead: the output drives it and a
  `CaptureReservoir` holds the converted input, correcting drift by a frame at a time. A backend
  without the converter (JACK, where the server owns the one rate) offers the intersection. Before
  1.6.0 nothing converted, on the belief that it would cost more than the backend saved; measured,
  it is 32 frames at the input's rate, 0.7 ms, against a ring hop of a whole period. A backend
  that ignores the output's rates will still ask a 48 kHz-only device for 44.1 kHz and then report
  the refusal as though the user could fix it in Windows.
- **Declare what the adapter adds.** `StreamFormat.adapterLatencyFrames` is the ring hop on a
  two-clock backend, the cushion on Java Sound, and the converter's delay when `inputRate` differs
  from `sampleRate`. Hiding it makes cross-backend comparison a lie.
- **Release in a `finally`.** Under exclusive mode a device left held can need a reboot.
- **The OS check comes first and must not load JNA.** `LinuxPlatform.isLinux` is JNA-free;
  `LinuxRt.isLinux` used to exist and broke this — JNA off the classpath made the guard itself fail,
  and `--list-backends` crashed on it. `Rt.isWindows` has the same flaw, since `Rt` declares JNA
  interfaces; the registry's `Throwable` catch contains it on Windows (the backend becomes an
  explained `Broken` entry), but code outside the registry should not lean on it.
- **Realtime calls go through a direct mapping.** JNA's interface mapping allocates an argument array
  on every call; `JackRt` and `AlsaRt` are `Native.register`ed `external fun`s taking primitives and
  existing `Pointer`s, and pass buffer addresses as `Long` so a partial read needs no `Pointer.share`.
- **A thread of ours waits for everything it still needs, not for a period.** ALSA wakes on
  `avail_min`, one period; a loop that reads a partial period and then waits sleeps a whole period
  for the remainder and falls behind the card every cycle. That was an xrun every third period at
  256 frames until `AlsaStream` checked `snd_pcm_avail` before reading.

The degradation path is tested by deliberately breaking it — run with JNA off the classpath and
confirm the app starts, streams on Java Sound and explains itself. A degradation path that has never
been exercised is one that does not work.

### Java Sound only: do not shrink the buffers

Everything in this section is `audio/javasound/`'s business and nothing else's. It is why that
backend exists at all and why its numbers look nothing like the others'.

The single biggest source of "crackly and robotic" audio here is not DSP, it is how Java Sound's
Windows backend blocks. `com.sun.media.sound.DirectAudioDevice` implements blocking `read`/`write`
as a poll: when the native call cannot move everything it does `lock.wait(waitTime)` and retries,
with `waitTime = lineBufferMillis / 4` (forced to 1 ms when that is under 10). On Windows the
scheduler tick is 15.6 ms unless something has raised the timer resolution, and nothing in the JVM
does — so **every one of those waits costs ~15.6 ms whatever it asked for**. Measured on a 23 ms
playback buffer, the loop moved 5 900 frames/s instead of 44 100: the device was starved 87 % of the
time and replayed stale buffer content.

Three things follow, and all three are load-bearing:

1. **Prime before starting.** `JavaSoundStream` writes `AudioConfig.CUSHION_MS` of silence into the
   playback line *before* `start()`. Starting an empty line and writing into it underruns on block
   one and, on this backend, on most blocks after it.
2. **Keep the line buffers large.** See `AudioConfig` for the numbers. Line buffer *capacity* is
   free — latency is set by how much audio is actually queued, not by how much could be. Capacity is
   sized so `write` never enters the polling path at all.
3. **Regulate the queue, do not target it.** `CushionRegulator` splices a single frame in or out at
   the quietest point in a block, at most once every four blocks, and only when the smoothed queue
   approaches either end of the buffer. It deliberately has no setpoint: the driver keeps a lead of
   its own that priming cannot take back, so an earlier setpoint version could never converge and
   corrected at its rate limit forever.

A callback backend needs none of it: one clock, exactly `bufferFrames` in and out per callback,
forever. `CushionRegulator` is not compiled into those paths and must not be — splicing frames into
a sample-locked stream would be actively harmful.

### Logging

`util/Log` writes `logs/omega.txt` in the settings folder, and only when **☰ → Logging** is on. The
setting lives in `Config.loggingEnabled` and survives a restart, because the faults worth a log are
the intermittent ones. Off means nothing on disk — no folder, no writer thread.

**It must never be called from the audio thread.** It allocates a string, takes a queue slot and has
a disk behind it, so every clause of the threading contract above applies to it. The existing calls
are on the EDT, on stream monitor threads, and on the opening thread. A bounded queue and one
`MIN_PRIORITY` daemon writer keep a stalled disk off the caller's thread and narrow the damage if
that rule is ever broken — they do not make it safe, and they are not an invitation.

When adding a call, the bar is whether a stranger's submitted log would be worse without it. Prefer
the ones that record **what the machine did rather than what the user did**: a driver granting a
different buffer than was asked for, a backend that would not load and why, a counter that moved.
`realtimeRatio` belongs in any line about stream health, and belongs first — see the note on
`AudioStream.realtimeRatio` for why `deadlineMisses` reading zero proves nothing.

### Adding an effect

Implement `AudioProcessor`, declare its knobs as `Parameter`s in the `parameters` list, and add it to
`Board.pedals`. The UI needs no changes — `EffectPanel` renders whatever parameters it finds — and
nor does split mode, since the second board is another `Board`.

**If a module's control set can change while it is on the board** — an `FxSlot` switching type, an
expandable module — call `EffectPanel.transitionKnobs()`, never `rebuildKnobs()` directly. It fades
the old controls out, swaps them while nothing is visible, then resizes the pedal as the new ones
fade in, so a change of knob *count* and of *arrangement* costs no more visually than a change of one
label. Change the effect's own state immediately and let only the picture wait: an FX slot switching
type is a change to what the audio thread is running, and delaying that would put the sound behind
the click.

Size every rate-dependent buffer and coefficient in `prepare(sampleRate, maxBlockFrames)` and clear
state in `reset()`; the engine calls both before the stream starts, `prepare` with the rate the line
actually negotiated. **Never derive anything from a hardcoded 44 100** — that was a real bug: the
delay line, the overdrive tone filter, the reverb tunings and the gain ramps were all pinned to
44.1 kHz, so on a 96 kHz device the 1000 ms delay knob silently topped out near 505 ms. `prepare` has
no default implementation on purpose, so a new effect cannot forget it. Effects must still work
un-prepared — the constructor assumes `DEFAULT_SAMPLE_RATE`, which is what the headless tests use.

`dsp/` must not import from `audio/`. It processes mono float blocks at whatever rate it is told and
knows nothing about devices; that is what makes it carry over unchanged to another backend.

**Do not iterate a `List` inside a per-sample loop.** `for (x in list)` allocates an iterator. In
`Reverb` that was ~96 000 objects a second, which is how a young-generation collection becomes an
audible dropout. Use arrays and hoist them into locals before the loop.

Chain order is deliberate and is `Board.pedals`, which reads gate -> compressor -> eq -> gain ->
overdrive -> amp -> nam -> ir -> fx1 -> delay -> reverb, then the board's own `level`, pinned. It matches
conventional pedal wiring: dynamics and tone shaping ahead of the distortion, the amp capture and
cabinet where an amp and a cabinet go, and **time-based effects last so repeats and tails are not
fed back into the clipper**. That last clause is the one worth keeping; the user can drag the rest
into any order they like.

**The master is after the boards, not before them.** It sat first until 1.2.0, which made it an
input trim — turning it down changed how hard the drive was hit. It now lives in the engine's `post`
chain, on the sum, and a `Preset` applies it on its own: it is in the outer preset's maps beside the
first board's pedals, and on no board.

### Split mode

A second `Board`, fed the same input and summed with the first. The rules it depends on:

- **The second board is always in the engine**, prepared and warmed with the first, whether or not
  split is on. Split is switched *while the stream runs*, and a board first prepared at that moment
  would be class loading against the deadline. Idle, it costs nothing: `AudioEngine` does not run it
  once its fade-out has finished.
- **Switching is a fade, not a switch.** `AudioEngine.split` is one volatile store; the engine ramps
  the second board's share over `BLEND_SECONDS` (the master's 11 ms) and snaps at -80 dB of the
  distance, so split on and settled is a plain add and split off reaches an exact zero. The engine
  test asserts the output never steps by more than a hundredth across either transition.
- **Each board ends in a `BoardLevel`**, a `VolumeStage` like the master with an `active` flag. Off,
  it ramps to unity and then returns without touching the buffer — asserted bit-for-bit — which is
  what lets it sit in the chain permanently under the "membership never changes while running" rule.
  Default 71 %, a gain of 0.504: two boards fed one guitar are correlated, and two at that gain sum
  back to one board's level.
- **A preset nests the second board** as `split`, a whole `Preset` with a blank name. Both boards
  carry the same module names, so a second set of entries in the same maps is impossible. Absent
  when split is off, so one-board presets are byte-identical to before; a nested board never nests a
  third. With split off the second board is not saved but keeps its state until the process ends.
- **Two NAM captures want 256 frames.** One capture measured at 97.7 % of a 64-frame period. The
  window says so in the status line — on switching split on, on loading the capture, on stream
  start — and enforces nothing; `realtimeRatio` is the arbiter.
- **The eye is one preference for both boards**, `Config.hideDisabledModules`, pressed on the
  strip above them. 1.4.2 gave each board its own; 1.5.0 put the toggles on the strip and went back
  to one, since a strip has one eye.
- **Nothing on a board is pinned to the viewport.** A board's name and its LEVEL are part of the
  board and scroll with it. The lane they sit in is there from the start: `ChainBoard.laneHeight`
  sizes it for the header's right end whether or not that is showing, so switching split on moves
  no pedal; the knob, the name and the second board fade in, and `BoardStack` scales each fading
  panel's room with its alpha so the first board's rows glide rather than jump. The scroll pane
  keeps its bar's column reserved (`VERTICAL_SCROLLBAR_ALWAYS`) and `SlimScrollBarUI` fades the
  thumb, because a bar added on demand narrows the viewport and the centred rows lurch sideways by
  half of it. The fork on the strip is the only way in and out — the second board has no ✕.

### Loop mode

A `Looper` of four `Lane`s, in the engine from the start like the second board, switched on and
off by the loop icon on the strip. The `LoopBoard` is a view of it: a row per lane — ✕, +, SAVE,
LOAD, REC, ORDER, KEY, ON as square buttons, a level knob, a waveform — and one horizontal
scrollbar under them for the minute. Pop-out moves that panel into a `LoopWindow`; close moves it
back. The rules it depends on:

- **Recording always takes the finished sound**, after the master and *before* the looper adds its
  own after-lanes, so a new lane never contains the ones already playing. ORDER is playback only:
  `AFTER` adds the lane under the live guitar; `THROUGH` adds it to the input ahead of both boards,
  so the pedals run over it again — record a riff with the overdrive, then put it through the delay.
  A through-lane that is playing is in the sum and so in anything recorded meanwhile; a real rig
  does the same, and it is documented rather than fought.
- **One loop length**, `Looper.loopLength`, set by the first lane recorded (or loaded) and free again
  when every lane is empty. A later lane is **armed** by REC and starts at the top of the loop,
  sample-accurately: `begin` knows where in the block the playhead wraps and `finish` copies from
  that sample. It stops by itself when it reaches the length. With no loop yet it starts at the next
  block from frame zero. Esc, Space and Enter stop a recording from anywhere in the app and cannot be
  bound to a lane.
- **Three calls a block, no allocation, no lock**: `begin(frames)`, `mixThrough(buffer)` before the
  chain, `finish(buffer)` after the master. Every lane's buffer is allocated in `prepare`, on the
  opening thread, a minute at the stream's rate — about 92 MB for four at 96 kHz. A rate change
  resamples the lanes offline with the chain's own `Resampler`; they are never dropped. The engine's
  allocation test runs with the looper playing and recording.
- **A click on a waveform is `Looper.seek`**: a request the audio thread applies at the top of the
  next block, where the playhead is read, and refuses during a recording. ✕ on the panel clears a
  lane with content and removes an empty one; the first lane is never removed.
- **Play and pause** are `Looper.playing`: paused, the lanes fade to silence and the playhead
  holds, so play resumes rather than restarts; REC while paused starts the transport. The box
  opens paused — switching loop mode on sets `playing` false — so the button reads PLAY until
  the user starts something. The tests and `--selftest` set it true themselves.
- **Lanes outlive everything but the process**: the panel being hidden, the pop-out, the stream
  stopping, the rate changing. Loop off stops playback with a fade and keeps the audio. The session
  stores only whether the board was on; `Config` keeps the key bindings, the popped-out window's
  bounds and whether it was popped out at close.
- **The warm-up runs the looper's paths on a throwaway instance** with tiny lanes, so the real lanes
  are untouched and the methods are compiled before the stream starts.
- **Lane files are mono float WAV** written by `LaneFiles`, the recorder's format; loading takes
  anything the JDK decodes, mixes to mono, keeps the first minute and resamples to the stream's rate
  off the audio thread. Nothing sampled ships; a lane is always the user's own audio.

### Adding to the rhythm section

`dsp/rhythm/` is a transport (`Transport`), a catalogue (`Feels`), a step table (`Pattern`) and five
synthesised voices (`Voices.kt`). A new click style or drum pattern is **data** — one entry in
`Feels.grooves` — and the dropdown, the session format and the warm-up all read that list. A pattern
is written as the grid it is, one character per step (`X` accent, `x` normal, `o` soft, `.` silent),
because a pattern nobody can check by eye does not get checked.

Adding a *voice* is code: one more entry in `Voice`, one more generator in `Voices.kt`, one more
element in `Transport.voices`.

**Nothing here is ever sampled.** A drum kit shipped as audio is an asset with its own copyright —
the same reasoning that keeps NAM captures and IRs out of the distribution. A user-supplied `.wav`
loader would be fine; a bundled kit is not.

Eight rules it depends on:

- **`Voice` is append-only and `Transport.voices` is indexed by its ordinal.** Nothing in the language
  enforces that they agree. Insert an entry in the middle and every pattern still plays, in time,
  with a kick coming out of the hi-hat — which is why `TransportTest` identifies each voice by its
  measured brightness rather than trusting the array.
- **Voice gains are the balance between instruments; `Transport.BUS_TRIM` is where the group sits.**
  Keep them separate, as a mixer does. Three oscillators landing on a downbeat all start from phase
  zero, so their peaks genuinely align: the 3/4 Waltz measured **1.71** before the trim existed. The
  bus is added to the guitar and only then clamped, so a pattern that reaches full scale alone leaves
  nothing for the instrument.
- **No `sin()` in a swept voice.** `Tick` can use the coupled recurrence because a tick's pitch never
  moves; the kick sweeps, so it reads `SineTable`. `Transport.prepare` touches that table
  deliberately — first use inside a callback is class loading against the deadline.

- **Tempo is the pulse, not always the quarter note.** `TimeSignature.beatsPerBar` is pulses: 2 for
  6/8 and 4 for 12/8, because a 6/8 bar is two dotted quarters and a musician who says "at 70" means
  70 of those. It is also the lamp count, which is what makes a wrong SIG visible.
- **The clock is fractional and the remainder carries.** 130 BPM at 44.1 kHz is 20 353.846 samples a
  pulse. Rounding per beat drifts ~0.85 samples a beat — 109 by the 130th. `TransportTest` pins the
  130th beat to within three samples of the arithmetic, which no rounding implementation passes.
- **The warm-up must reach every pattern.** `AudioEngine.warmUpRhythm` walks `Feels.all()`, because
  FEEL is a dropdown and a dropdown is changed *while the stream runs* — the same reason `FxSlot`
  has `alternates`. It also forces `Feels`' initialiser, which builds every step table.
- **`--selftest --chain` drives it**, Rock with the click layered over it at 240 BPM, silent, and
  **after `engine.start`** — the warm-up stops the transport in its `finally`, so a version placed
  above that line reports it is running and measures nothing. A full kit rather than a metronome:
  the scheduler costs the same either way and the voices are the part that adds up.
- **Swing is a timing value, not extra rows.** A shuffle is straight rock played unevenly —
  `Feels.grooves` has the two side by side differing only in that number. It lengthens the on-beat
  and shortens the off-beat equally, so the pulse never moves.

Pattern names are the session's storage key, so **renaming a feel silently resets anyone's saved
choice to the plain click**. That is the tolerated failure; changing what a name means is not.

`Session` stores the rhythm as plain strings and numbers. `preset/` is free of `dsp/` for the same
reason it is free of `audio/`.

### Adding an FX slot type, a drive, an amp voice or a cabinet

Write the `AudioProcessor` as above, then add one line to the `TYPES` list of `FxSlot`, `Overdrive`,
`Amp` or `Ir`. Nothing else changes — the dropdown, the preset format and the warm-up all read that
list. All four are a `TypedModule`: one instance of every type, the selected one running, a fade
across a switch, every type's knobs captured, and `compactParameters` following the selected type.
The slot is positional and numbered and goes in a preset's `fx` list; the other three have fixed
names and go in its `modules` map, keyed by them.

The amp voices and the cabinets are one class each, `AmpVoice` and `CabinetVoice`, parametrised by
a `Spec`: a new family is a new spec, not new code. They are voicings in the style of a family —
the amp runs at twice the device rate because clipping at the device rate aliases — and a NAM
capture or a loaded impulse is the real thing; the dropdowns put the file first on the IR pedal for
that reason. The IR pedal's LOAD and ✕ belong to its `Loaded IR` type; `Board.syncIrHeader` hides
them on the cabinets. A preset from before the
Overdrive had types carries flat `Overdrive.Drive` keys and no `modules` entry, and `Board.apply`
puts the pedal on its first type before `applyTo` so those keys land where they always did — which
is why **the first drive stays `Smooth`, the original algorithm, with the original knob names**.

The drives are voicings in the style of a family of pedal, not models: the clipping curve, what is
filtered out before it, what is done after. Each is tested at its loudest to stay inside full scale
and to be silent on silence; the biased ones subtract the clipper's resting value for that.

Three rules both depend on:

- **Append only.** A preset stores the selection twice: by name in its `fx` list, which is what is
  read back, and incidentally as the `Type` parameter's positional index. Reorder the list and those
  two disagree, which is worse than either being wrong alone.
- **The warm-up must reach it.** `AudioProcessor.alternates` is how; `AudioEngine.warmUp` walks it
  and deduplicates by class, since JIT compilation and class loading are per class. A type first
  reached by a dropdown change mid-stream would otherwise load and interpret against the deadline.
- **Slots are numbered, never named.** `FxSlot.label` is computed from board position and renumbers
  when the board changes, so it is a display string and never an identity. That is why `Preset.fx`
  is an ordered list rather than a map: deleting FX 2 renumbers FX 3 into its place, and any key
  naming either would then point at the wrong module.

Board membership only changes with the stream stopped — the same rule the drag handle follows.
`EffectChain.replaceAll` publishes atomically, but an arriving effect must already have been through
`prepare` and the warm-up, and a departing one has its tail cut wherever it happened to be.

`enabled = false` normally means **bypass** — `EffectChain` skips the effect entirely. `MasterVolume`
overrides `mutesWhenDisabled` to invert that: the chain keeps calling its `process()` while off so it
can ramp to silence, because a volume control whose switch passed audio straight through would be
backwards. Any effect whose switch should silence rather than bypass needs that same flag; a mute
implemented by zeroing the buffer in one step would click, so ramp it.

## Constraints and known limits

- `jvmToolchain(21)` — see the collector note under Build and run. The foojay resolver in
  `settings.gradle.kts` auto-provisions a JDK 21, so local `JAVA_HOME` need not be 21.
- Kotlin 2.1.20, `mavenCentral()` only. **JNA is the one runtime dependency, taken under LGPL 2.1**
  rather than the Apache 2.0 it is also offered under — this project is GPLv3-or-later and either
  would do, but a dual licence has to be elected once and written down, and it is: in README.md, in
  `build.gradle.kts` and in `ui/Licences.kt`, which `LicencesTest` pins. Change all three or none.
  Never introduce the Steinberg ASIO SDK: it would add a native build step and vendored code under a
  licence of its own, and `IASIO` is already hand-declared from its published vtable layout.
- **Latency, measured on the interface this was developed against** (Sonicake, 44.1 kHz only, ASIO
  driver reporting min 8 / max 2048 / granularity powers-of-two):

  All verdicts are from 60 s with an allocation flood running (`--selftest --chain --stress`), which
  is the only version of the test worth quoting.

  | Backend | Buffer | Driver-reported round trip | Worst callback / period | Verdict |
  | --- | ---: | ---: | ---: | --- |
  | ASIO | 8 | 5.17 ms | 0.34 / 0.18 ms | **fails** — two callbacks overran their period |
  | ASIO | 16 | 5.35 ms | 0.19 / 0.36 ms | clean, but over half the period is spent |
  | ASIO | 32 | 5.71 ms | 0.25 / 0.73 ms | clean |
  | ASIO | 64 | 6.44 ms | 0.15 / 1.45 ms | **the low-latency recommendation** |
  | ASIO | 128 | 9.91 ms | 0.20 / 2.90 ms | clean; the comfortable recommendation |
  | WASAPI exclusive | 264 | 17.96 ms | — | clean; needs no third-party driver |
  | Java Sound | 7056 (160 ms line) | ~160-200 ms | — | the fallback tier |

  **The chain grew again on 2026-10-29** — the amp voice at twice the rate, a synthesised cabinet,
  a board LEVEL — and was measured over the virtual cable at 264 frames under `--stress`: worst
  callback 0.28 ms, against 0.30 ms for the chain before them, so the additions are inside the
  variation between runs. Not an ASIO-at-64 figure; the Sonicake was not attached. With `--split`
  the two-board chain measured the same 0.28 ms.

  **The ASIO rows were re-measured on 2026-08-12** against the widened `--chain` above — more DSP
  than the chain that produced the previous figures, and no worse. 16 frames passed where it had
  been marginal, which says the added cost is smaller than the variation between runs; read it as
  "not worse" rather than as an improvement, and note that a *pass* at 16 still spends 53 % of its
  period against 10 % at 64. The WASAPI and Java Sound rows are older and were not repeated.

  Caveat on all of them: the input was silent, so `Compressor`'s over-threshold branch — its one
  per-sample logarithm — never fired. Everything else in the chain costs the same either way.

  **These figures have no NAM capture in them, and that matters more than the caveat above.**
  `--chain` cannot load one. Measured from the running application at 64 frames with a capture in
  the chain: realtime 1.0000, 0 xruns, 0 deadline misses — and a **worst callback of 1417 µs against
  a 1451 µs period, 97.7 % of it**, against the 0.15 ms the table reports. It holds and it has
  nothing spare. **With NAM loaded, recommend 128 frames, not 64.**

  Note how little the buffer buys below 64 frames: the driver's fixed overhead is ~66 frames in and
  a 154-frame floor out, so halving the buffer from 32 to 16 saves 0.36 ms of a 5.7 ms round trip
  and costs most of the margin. The knee is at 32-64.
- **Sample rate is a latency lever too**, which is why RATE is a picker beside BUFFER rather than a
  constant. The period is frames over rate: 128 frames is 2.90 ms at 44.1 kHz, 2.67 at 48, 1.33 at
  96. `SampleRates` holds the negotiation and has tests; `dsp/` needed no change for any of it,
  because every coefficient already derives from the rate passed to `prepare`.
- **Two-clock pairings under WASAPI have limits worth knowing.** Input and output are separate
  `IAudioClient`s joined by a ring, and each negotiates its *own* buffer size — they need not match.
  Measured: the same device both directions runs clean (0 xruns at 264 frames), and two *different*
  physical devices drop out at the smallest periods as their crystals drift — a USB mic into a
  wireless headset gave 7.7 xruns/s at 144 frames and 3.9/s at 288. The remedy is a larger buffer,
  the same device both ways, or ASIO. The counter reports it rather than hiding it.
- **Some drivers cannot work in exclusive mode at all**, and only runtime measurement finds them.
  The AORUS HDMI output grants whatever buffer it is asked for and then fires its event every three
  quarters of it, so one-buffer-per-event outruns it by 33 % for ever. `WasapiStream` watches its own
  realtime ratio and stops with a named explanation rather than breaking up silently.

  ASIO figures are `ASIOGetLatencies`, which includes the driver's safety offsets and excludes the
  converters. **No true cable-to-cable measurement has been taken** — that needs a lead from output
  to input and `--measure`. Treat the driver's numbers as good estimates, not as the truth.
- `AsioBackend` funnels every driver call through a single COM-apartment thread (`ComWorker`). ASIO
  drivers are thread-affine and several crash if `createBuffers` arrives on a different thread from
  `init`. The realtime callback is the exception and arrives on the driver's own thread.
- **Latency vs. dropouts on the Java Sound backend only** is still tunable without a rebuild:
  `-Domega.audio.captureMs=`, `-Domega.audio.playbackMs=`, `-Domega.audio.cushionMs=`,
  `-Domega.audio.drift=false`. Verify any change with `--selftest`, not by ear.
- The engine is mono end to end; stereo output is the same signal duplicated across channels.
  `StreamRequest` already carries channel counts, so widening `AudioProcessor.process` to two buffers
  later would not reopen the seam.
- **Linux runs since 1.1.0, measured on one laptop** (Manjaro, PipeWire, HDA Intel PCH, no guitar
  interface). ALSA direct holds 256 frames under `--chain --stress`; 128 and 64 fail on that card,
  as does ALSA's own `alsaloop`, so the card is the limit. JACK has only met a dummy-driver jackd.
  `known-issues.md` §5 lists what is unverified. The seam needed no change beyond two defaulted
  members — `DeviceCapabilities.unusableReason` and `AudioStream.schedulingNote` — and nothing in
  `dsp/`.
- **The AppImage needs a vendor JDK as the build toolchain.** The image names its runtime's exact
  build from the JDK's `release` file, and a distribution JDK (Arch's, for one) leaves
  `IMPLEMENTOR_VERSION` out, so `appDirFiles` stops rather than ship a notice with a hole in it. CI
  uses Temurin through `setup-java`. `appimagetool` is not fetched by Gradle; pass
  `-Pappimagetool=`. CI pins 1.9.1 by SHA-256 in **both** workflows — change them together.
- **Realtime on Linux is an account setting, not ours.** rtkit grants it unprivileged on most desktops
  (priority 20 here, with `rtprio` 0), but only after the process caps `RLIMIT_RTTIME`; `LinuxRt`
  does that. `--list-backends` ends with a `Realtime:` line naming the fix per distribution family.
- A seam validated by one native implementation is probably the wrong seam. It now has two (ASIO,
  WASAPI) plus Java Sound, and the two natives have genuinely different shapes — one clock versus
  two — which is the useful kind of evidence.
- Nimbus is forced in `Theme.install()` because, unlike the native look-and-feels, it honours colour
  overrides on both Windows and Linux. `UIManager` colour keys must be set *before* the L&F is
  installed.

## Testing

388 tests in `src/test/kotlin/`, none of which needs an audio device. `AlsaTest` covers the ALSA
codec and `/proc/asound` parsing against byte patterns worked out by hand; it touches no library, so
it runs on Windows too. `CaptureReservoirTest` drives the converting reservoir with a simulated pair
of devices whose clocks are set 100 ppm apart, for twenty minutes of periods. The useful patterns are impulse
response (`DelayTest` checks an echo lands at an offset computed independently), boundedness under
extreme settings, and decay over time (`ReverbTest`).

`FxModulesTest` and `PresetFxTest` cover the EQ, the four new effects and the slot. Two habits there
worth keeping. **Every module claiming transparency is asserted bit-transparent**, with
`assertContentEquals` rather than a tolerance — a module you cannot leave in the chain at its default
settings is not transparent, and "close" hides that. And the numbers come from the filter order or
the decibel figure rather than from the implementation: a -12 dB bell reduces its own centre
frequency to a linear 0.251; a 2 Hz tremolo dips to silence exactly twice in a second; a 300 Hz low
cut attenuates 50 Hz identically at 44.1 and 96 kHz, which is the standing guard against the
hardcoded-44100 bug. The de-esser's band split was rewritten because a test written that way failed
before the module had ever run.

The low-latency work added three suites, all built on the same principle — assert against a number
you worked out yourself, not against what the code currently returns:

- **`LoopbackProbeTest`** puts a delay line of a known length between the probe's output and its
  input and checks it reports that length. It sweeps several delays (a probe returning a constant
  would pass a single case), runs at 96 kHz as well as 44.1, and — the two that caught real bugs —
  buries the burst under 0.25-amplitude broadband noise and under a loud 100 Hz tone. The noise case
  is why the onset walk-back has a learned floor: without one it walked straight through the noise
  and reported an onset hundreds of frames early.
- **`BackendSeamTest`** pins the registry's promises (never empty, never throws, ordered best first,
  every unavailable entry carries a usable reason) and `legalBufferSizes`, including the nasty cases:
  granularity -1 with a non-power-of-two minimum, granularity 0, a step of 1, and a driver returning
  nonsense.
- **`SampleRatesTest`** covers rate negotiation against the two real devices that exposed the bug —
  a 44.1 kHz-only USB interface and a 48 kHz-only HDMI output. It asserts the intersection, which
  rate is chosen and why, that the period shortens as the rate rises, and — as a regression guard —
  that the no-overlap message never again tells the user to change a Windows sound setting. Since
  1.6.0 it also pins the converter's side: a converting backend offers the output's rates, a native
  rate is recommended over a converted one, the capture rate agrees with the rate the sizes were
  probed at, and that same failing pair negotiates at 48 kHz with the input converted.
  `ResamplerTest` checks that uneven chunks give the same samples as one call, which is how a
  capture packet arrives.
- **`AudioEngineTest`** covers the callback body: silence in/silence out, the input buffer is never
  written to (several backends hand out a view onto driver memory), output is clamped, a chain that
  throws yields silence and a flag rather than noise, short blocks leave the buffer tail alone, and
  meter ballistics attack instantly and release slowly. It also asserts that `render` **allocates
  under 64 kB over 20 000 blocks**, measured with `getCurrentThreadAllocatedBytes` — a bytes
  assertion rather than a timing one, because allocation is what triggers the collection. The split
  tests sit beside those: an idle second board is never run, the sum arrives by a fade and leaves by
  one, the post stage sees the sum, and the allocation bound holds with both boards running, and
  again with the looper playing three lanes and recording a fourth.
- **`LooperTest`** drives the loop box block by block as the engine does, against the rules rather
  than the code: a lane recorded over `n` blocks is `n × frames` long and plays back the samples it
  was given; the first lane sets the loop and a second waits for the top of it and stops at its
  length; a wrap inside a block starts the recording at that sample; a through-lane is halved by a
  chain that halves and an after-lane is not; off, the lanes fade and the playhead holds; a rate
  change keeps a 100 Hz tone at 100 Hz. `LaneFilesTest` round-trips a lane through a WAV, cuts a
  long file to a minute and converts a 48 kHz file to 96.

Swing and everything that touches a driver are not unit-tested. Verify those with `--selftest`
(add `--chain --stress` or the result means little), `--measure` if a lead is patched, and by
running the app. The number to watch is `realtimeRatio`, not `deadlineMisses`.
