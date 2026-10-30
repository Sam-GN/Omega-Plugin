# What's Done

A running record of what was asked for and what was delivered, so the work can be traced back.
Requirements are grouped by where they came from. Every performance figure here was measured, and
the method is stated — where something is an estimate or unverified, it says so.

Companion to `low-latency-backend.md`, which is the architecture design.

---

## 1. Core requirements

| # | Requirement | Status | Where |
| --- | --- | --- | --- |
| 1 | GUI guitar plugin | Done | `ui/` |
| 2 | Output is an executable program | Done | `build/libs/OmegaPlugin-<version>-all.jar` — **now needs Java 21+**, see §7 |
| 3 | GUI program (not a library) | Done | Swing, custom-painted |
| 4 | GUI to select input and output channels | Done | Device bar, with backend/rate/buffer pickers |
| 5 | Access to audio devices on Windows **and Linux** | Done — Linux since 1.1.0-beta (JACK, ALSA), on one laptop so far; see §7 |
| 6 | Switches to turn effects on and off | Done | `ToggleSwitch` per pedal |
| 7 | A knob to adjust granularity | Done | `Knob` — drag, shift for fine, wheel, double-click to reset |
| 8 | Effects: gain, delay, reverb, overdrive | Done | `dsp/Gain.kt`, `Delay.kt`, `Reverb.kt`, `Overdrive.kt` |
| + | Beyond the original scope: compressor, gate, IR cabinet | Done | `dsp/Compressor.kt`, `Gate.kt`, `Ir.kt` |
| + | Beyond the original scope: Neural Amp Modeler | Done | `dsp/Nam.kt`, `dsp/nam/`. Reads `.nam` **0.5.x and 0.7.0**, including slimmable containers; verified against 14 real captures. Loudness-normalised, and rate-matched to the capture through `dsp/Resampler.kt` |
| + | Beyond the original scope: presets, saved per file | Done | `preset/`, hamburger menu. Carries the NAM and IR files a sound was made with |
| + | Beyond the original scope: recording to WAV | Done | `audio/Recorder.kt` — 32-bit float mono, pre-clamp, opens in Audacity |
| + | Beyond the original scope: the session is restored on launch | Done | `preset/Session.kt` in `config/config.json` |

Linux was expected to be the hard part. It landed in 1.1.0-beta behind the same seam, touching
nothing in `dsp/` — JACK, ALSA, realtime through rtkit, and an AppImage — and has so far run on one
laptop's built-in card; no guitar interface has been tried there. See `linux-compat.md` §0.

---

## 2. The latency requirement

**Stated mid-project: 10–12 ms round trip maximum, "if we don't match it this project is dead."**

Delivered — and beaten. Measured on the Sonicake Pocket Master via ASIO, full pedal chain enabled:

| Buffer | Round trip | Realtime ratio | Deadline misses | Xruns | Worst callback / period |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 16 frames | **5.35 ms** | 1.0001 | 0 | 0 | 0.19 / 0.36 ms |
| 32 frames | **5.71 ms** | 1.0000 | 0 | 0 | 0.25 / 0.73 ms |
| 64 frames | **6.44 ms** | 1.0000 | 0 | 0 | 0.15 / 1.45 ms |
| 128 frames | **9.91 ms** | 1.0000 | 0 | 0 | 0.20 / 2.90 ms |

Starting point was **~150–200 ms** on the same interface.

Re-measured 2026-08-12 against the full current chain — every module that runs without a file, the
EQ with all five bands active, and an FX slot holding a reverb. 8 frames still fails. 16 now passes
where it was previously marginal, on *more* DSP than produced the earlier figure, which says the
added cost is smaller than the run-to-run variation; 64 remains the recommendation, because a pass
at 16 spends 53 % of its period against 10 % at 64 and that margin is what absorbs a bad moment.

**Method and its limit:** these are the driver's own `ASIOGetLatencies` figures. They include driver
safety offsets and exclude converters. **No true cable-to-cable measurement has been taken** — that
needs a physical loopback lead and `--measure`, which is built and tested but never run against real
hardware. This is the single most important open verification.

### How it was reached

- **JNA 5.19.1 under LGPL 2.1**, the only runtime dependency. ASIO is driven through a hand-declared
  `IASIO` COM vtable — **no Steinberg SDK**, so no native build step and nothing vendored.
- **Toolchain moved from Java 11 to 21, with generational ZGC.** Not a tuning preference: at 64
  frames under load, G1 gave a realtime ratio of 0.9716 versus ZGC's 1.0000 — 2.8 % of the audio
  lost — while `deadlineMisses` read 0 under *both*. Collections stall the callback thread *between*
  callbacks, so body-time instrumentation calls G1 healthy while audio is being dropped. `realtimeRatio`
  exists in the seam because of this and is the counter to trust.
- **DSP cost is negligible** — the whole chain measures 0.0 % load, worst callback 0.03–0.07 ms
  against periods of 0.73–2.90 ms.

---

## 3. Backend architecture

Asked for as "separate subsystems for Windows and Linux; the GUI can be shared". Implemented as one
OS-agnostic seam with per-OS implementations behind it.

| Item | Status |
| --- | --- |
| `AudioBackend` seam, callback-driven, duplex, mono float | Done |
| ASIO backend (primary) | Done — `audio/asio/` |
| WASAPI exclusive backend (fallback) | Done — `audio/wasapi/` |
| WASAPI **shared** backend (device stays sharable) | Done — `audio/wasapi/WasapiSharedBackend.kt`, 65.99 ms |
| Java Sound backend (always-available bottom tier) | Done — `audio/javasound/` |
| Automatic fallback with the reason surfaced | Done — verified by removing JNA from the classpath; app starts, explains, streams |
| Linux backends (JACK, ALSA) | Done in 1.1.0-beta — `audio/jack/`, `audio/alsa/`, realtime in `audio/linux/`. Nothing in `dsp/` changed |
| Fallback ladder re-verified on Linux | Done 2026-10-29 against 1.1.0-beta, JNA off the classpath — found and fixed a `--list-backends` crash |

`dsp/` is **byte-identical** to before the backend work. That was the design goal and it held.

---

## 4. UI work

| Requirement | Status | Notes |
| --- | --- | --- |
| Master volume, click to mute, unmute to prior level | Done | In the top bar. Mute ramps ~11 ms rather than cutting, so it is click-free. **Last** in the signal path since 1.2.0 — it was first, which made it an input trim |
| AMP pedal voiced as five amplifier families, six knobs, 2× oversampled | Done | 1.4.0. Voicings, not models; a NAM capture of the real amp wins |
| Synthesised cabinets on the IR pedal — 1x12, 2x12, 4x12, 1x10, with a MIC knob | Done | 1.4.0. The loaded file stays first and default |
| Overdrive with a TYPE dropdown — Smooth, Screamer, Rat, Fuzz, Tube, each with its own knobs | Done | 1.3.0. The FX slot's machinery on a fixed pedal; Smooth is the original algorithm, so old presets are unchanged |
| Split mode — a second board under the first, both heard | Done | 1.2.0. The fork beside the eye. Each board gets a LEVEL in its header at -6 dB by default, so two correlated boards sum to what one was. Saved in presets and the session as a nested block; one-board presets are unchanged |
| Fixed-width master readout | Done | Width measured from the font, not guessed; `MUTED` no longer shifts the buttons |
| Buffer size picker beside Start, locked while running | Done | Driver-enumerated frame counts, shows frames **and** ms |
| Sample-rate picker | Done | Same discipline; defaults to the endpoints' own current rate |
| Chain visualisation — cables linking pedals | Done | `ChainBoard`, with directional arrowheads |
| Drag-and-drop pedal reordering, disabled while running | Done | Reorders live under the pointer; `EffectChain.reorder` requires a permutation |
| EQ collapsing to four knobs, `+` expands to nine | Done | Every band exists at all times and a flat one is skipped by a comparison, so the button reveals rather than constructs and is safe while streaming |
| FX modules — a dropdown chooses what each one is | Done | `FxSlot`. All six types are built, prepared and warmed up front; changing type fades through silence over ~12 ms rather than stepping |
| `+` adds another FX module, `✕` removes one | Done | FX 1 is permanent and carries no ✕; the rest renumber to close the gap. Removal asks only when the slot has been dialled away from its defaults. Both need the stream stopped, like dragging |
| Pedal width follows its header as well as its knob row | Done | A two-knob FX slot is narrower than its own title, dropdown, ✕ and switch; sizing from the knobs alone pushed the ✕ off the end of the pedal |
| App icons from supplied artwork | Done | Eight sizes, 16–512, from `omega_icon_v6*.svg` |
| Custom title bar (IntelliJ/Discord style) | Done | Undecorated frame; drag, double-click maximise, edge-drag resize all re-implemented |
| Caption buttons vertically centred | Done | Were 8.5 px high |
| Hamburger menu between icon and name | Done | Save/Load/Delete presets, Import, Export, recording destination, About, Exit |
| Type a value under any knob | Done | Click the readout, type, Enter. Unacceptable values are refused and flash rather than being clamped silently |
| Record button beside Start, with a red lamp | Done | Writes to `Desktop/Omega-Plugin` unless told otherwise; destination persists |
| LOAD button on NAM and IR, with a ✕ to unload | Done | ✕ is hidden until there is a file to clear; unloading keeps every knob |
| A preset remembers its NAM capture and cabinet | Done | Missing file ⇒ red LOAD button naming what to go and find, settings kept |
| The session comes back on launch | Done | Sound, order, switches, files, driver, devices, rate and buffer. Does **not** auto-start the stream |
| Every control hand-painted, including the file chooser | Done | Nimbus cannot be recoloured per-component, so the lever is the UI delegate |
| Menu: no separator, accent hover; title bar keeps grey hover; close stays red | Done | Menu rows are hand-painted; Nimbus cannot be recoloured per-component |
| App named **Omega-Plugin** | Done | Window title and README. Build artifact is still `OmegaPlugin-…jar` |
| IntelliJ run configurations | Done | `../.run` — Run App, List Devices, Run App (no Gradle) |

---

## 5. Licensing

| Item | Status |
| --- | --- |
| **GPLv3 or later** | Done — `LICENSE` is the verbatim GPLv3 text from gnu.org |
| "or later" notice | Done — `../README.md`, the header and the SPDX line on every source file |
| Copyright holder | Sam Ganji &lt;sam.ganji.n@gmail.com&gt;, personal — not the company address on the account |
| Per-file headers | Done — every `.kt` in `../src/main` and `../src/test` |
| Third-party licence table | Done — `../README.md` |
| JNA elected under **LGPL 2.1**, not Apache 2.0 | Done — both are GPLv3-compatible; the election is recorded in `../build.gradle.kts` and the README |
| Steinberg ASIO SDK | **Deliberately never used** — hand-declared vtable, no native build step |
| Gradle build scripts | Deliberately unheadered — project-specific config that never travels alone |

---

## 6. Bugs found and fixed

Listed because several were latent and would have been hard to find later.

**Audio**
- JVM stayed alive after the window closed, orphaning a process that held the capture device open.
- "Crackly and robotic" playback: the JDK's Windows backend polls with `lock.wait(waitTime)`, and
  Windows' 15.6 ms scheduler tick made every wait cost ~15.6 ms. The loop moved 5 897 frames/s
  instead of 44 100. Fixed by priming, buffer sizing, and `CushionRegulator`: 505 glitches in 28 s → 1 in 60 s.
- `Reverb.process` allocated two iterators **per sample** (~96 000 objects/s) by iterating a `List`
  inside the per-sample loop.
- `dsp/` hardcoded 44.1 kHz in four places and imported from `audio/`. At 96 kHz the 1000 ms delay
  knob silently topped out near 505 ms. Fixed with `prepare(sampleRate, maxBlockFrames)` — which
  later turned out to be what let the backend work carry over untouched.
- Sample rate was negotiated from the **input only**, so a 48 kHz-only output produced impossible
  advice about Windows settings.
- WASAPI `Initialize` returning `S_OK` is not proof the format was honoured — one device accepts 144
  frames and reports 192.
- A single buffer size was forced on both endpoints, which are independent clients joined by a ring.
- Frame counts round-tripped through 100 ns units truncate (264 → 263), breaking a working pairing.

**UI**
- Caption buttons sat 8.5 px high: `WindowButton` set `preferredSize` and `maximumSize` but not
  `minimumSize`, so `BoxLayout` read a stale `Component.size()`.
- Maximise covered the taskbar — `MAXIMIZED_BOTH` ignores screen insets on an undecorated frame.
- Icon generation silently wrote eight blank PNGs over good ones when a render failed; the generator
  now throws instead of continuing.

---

## 7. Open items

Ordered by how much they matter.

1. **No true cable-to-cable latency measurement.** All figures are the driver's own claim. Patch a
   lead from the Sonicake's output to its input and run `--measure`.
2. **Linux has run on one laptop only.** No guitar interface, and JACK only against a dummy jackd. See `known-issues.md` §5.
3. **No installer.** The jar needs Java 21+ and cannot be given ZGC flags through a manifest, so
   `java -jar` runs without the collector the app needs. That half is closed twice over: `omega.bat`
   and `omega.sh` are written beside the fat jar carrying the right flags, and `exeZip` now produces
   a jpackage app-image — `OmegaPlugin.exe` and a bundled Java 21 — where the flags live in a `.cfg`
   the launcher reads, so there is no way to start it wrongly. Verified on this machine:
   `OmegaPlugin.exe --list-backends > report.txt` writes `Collector: generational ZGC. Good.` and
   finds ASIO. The redirect is not optional there: one launcher ships, it is GUI-subsystem, and such
   a binary attaches to no console.

   What is still missing is an actual *installer*: no `.msi`, so no start-menu entry, no uninstaller,
   no file associations. `--type msi` would give all three and needs WiX on the runner. Unsigned
   either way, so SmartScreen will warn on first run until a code-signing certificate exists.
4. **Investigate sample-rate conversion so the Sonicake can pair with the AORUS.** The Sonicake is
   44.1 kHz-only and the AORUS is 48 kHz-only, so there is no shared rate and the pairing is
   currently refused by design — resampling was ruled out earlier on latency grounds. Worth
   revisiting for the specific case of playing through the monitor's speakers, where a few
   milliseconds matter far less than on the main path. Three things to weigh before building it:
   - 44.1 ↔ 48 kHz is a 147:160 ratio, so it needs a real polyphase or asynchronous converter, not
     cheap interpolation. Quality and CPU are both genuine costs. **A fixed polyphase converter now
     exists** — `dsp/Resampler.kt`, written for NAM captures — but it is synchronous, so it is a
     starting point rather than the answer: two devices on two crystals need drift handling as well.
   - It must stay **off the low-latency path**. The Sonicake-to-itself route is 5.71 ms and nothing
     should be allowed to regress it.
   - **Resampling alone will not make this pairing work** — see item 6. The AORUS also cannot run
     WASAPI exclusive at all, so it needs the shared-mode output path, which now exists but costs
     66 ms. For monitor speakers that is probably acceptable; it is not a route for playing.
5. **Aero Snap is gone** — the cost of the undecorated window. Recoverable only with native hooks.
6. **AORUS HDMI cannot run WASAPI exclusive** — its driver fires events 33 % faster than it plays.
   Refused with a named reason rather than worked around; two workarounds were tried and reverted
   because they damaged well-behaved devices.
7. **Two different physical devices at the smallest WASAPI period drop out** — independent crystals,
   no cushion. Remedy is a larger buffer, one device both ways, or ASIO.

### What you have vs. the guitar-rig universe

Effects marked **(FX)** live in a slot rather than on the fixed board — one dropdown entry each, so
adding another is a line in `FxSlot.TYPES` and nothing else.

- **Dynamics** — Gate ✓, Compressor ✓, Transient shaper ✓ (FX), De-esser ✓ (FX). Missing: limiter.
- **Gain & dirt** — Gain ✓, Overdrive ✓. Missing: distortion, fuzz, octave fuzz, bit crusher.
- **Filter/EQ** — EQ ✓: high-pass, low shelf, two sweepable bells, high shelf, collapsing to four
  knobs. Was the biggest sonic gap by a distance; now the only thing arguably missing here is a
  dynamic band, and the de-esser covers most of what that was wanted for.
- **Modulation** — Chorus ✓ (FX), Tremolo ✓ (FX). Missing: flanger, phaser, vibrato, rotary,
  univibe. Flanger and vibrato are close to free — both are `Delay`'s interpolating read head with a
  different sweep — and phaser can take its shape from `Reverb`'s existing `Allpass`.
- **Time** — Delay ✓, Reverb ✓, and both are also offered as slot types, so a board can carry two
  delays at different points in the chain.
- **Pitch** — nothing. Octaver, harmoniser, detune, whammy. Deliberately left alone: these need
  pitch tracking or real time-domain shifting, they are the hardest things on this list by a wide
  margin, and a bad one sounds unmistakably bad.
- **Amp & cab** — NAM ✓, IR ✓. That end is genuinely well covered.
- **Utility** — Master ✓. Missing: tuner, looper, phase invert. The first two are not slot material
  and never will be — a tuner reports a number rather than processing audio and belongs in the title
  bar, and a looper is transport-shaped rather than knob-shaped, so `EffectPanel` could not draw it.

### Closed, delivered

Kept rather than deleted, because a record of what was open is part of what this document is for.

- **Settings persistence.** Was: "chain order, buffer, rate, device choice and knob positions all
  reset on restart." Now a whole session comes back — the sound whether or not it was ever saved,
  with its `•` if it was modified, plus driver, devices, rate and buffer. A device that has gone
  falls back to the first on that driver and says so. See CHANGELOG 1.0.28-alpha.
- **A preset now remembers its impulse response and NAM capture.** The open question was what to do
  when the path is gone: the answer is that the knobs are kept and the LOAD button turns red naming
  the file to go and find, because restoring a module's settings but not its file is not the sound
  that was saved.
- **A 48 kHz capture run at 44.1 kHz is now resampled.** Was flagged on the status line and left to
  the player; `dsp/Resampler.kt` matches the capture's rate for 65 frames (1.47 ms), on by default
  and switchable from the NAM module's `⋮` menu, where the cost is stated beside the choice.

### Closed, with a negative result

- **Low-latency WASAPI shared via `IAudioClient3`.** Built, measured, reverted. Every endpoint on
  this machine supports the interface and reports `minimum == default`, so there is nothing to
  claim; the one device that does report headroom granted the same buffer anyway. Shared mode is
  22 ms of period and 66 ms of round trip, and that is the floor here. The period query survives
  behind `-Domega.wasapi.debug=1` so the same question can be answered quickly on other hardware —
  which is the only thing that could change this answer. See CHANGELOG 1.0.12-alpha.

---

## 8. Verification at a glance

- **Played by ear.** A guitar has been run through the Sonicake on both ASIO and WASAPI, and it
  works. Until this point everything was verified by unit test and by counter, never heard.
- **245 unit tests**, no device required.
- `--list-backends` — every backend, why the unavailable ones are unavailable, and each device's
  rates and legal buffer sizes.
- `--list-devices` — headless enumeration.
- `--selftest` — the real engine between two devices, reporting realtime ratio, deadline misses and
  xruns per second.
- `--measure` — true round trip through a physical loopback. Built and tested against synthetic
  delay lines; **never yet run against a real lead**.
