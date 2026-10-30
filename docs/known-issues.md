# Known issues

Defects in code that **already ships**, with enough detail to act on. Distinct from
`theme-optional.md`, `profile-optional.md` and `pickup-optional.md`, which propose things nobody has
promised — everything here is a shortcoming in something a user can already switch on.

---

## 1. `--selftest` reports PASS on a stream that never ran

**Affects:** the headless diagnostic, all versions.
**Status:** **not fixed.**

Observed on Realtek ASIO: `--selftest` printed **PASS** with `0 + 0 frames` round trip, a `0.00 ms`
worst callback and a realtime ratio of `1.0000`. `render` never fired — but the ratio is trivially
1.0 when the device never asks for frames, so **a dead stream is indistinguishable from a perfect
one**, which is the one thing this diagnostic exists to tell apart.

The fix is a floor: a run that produced no callbacks, or fewer than the elapsed time and sample rate
imply, must fail loudly rather than pass silently.

---

## 2. Changing the driver combo freezes the window for ~700 ms

**Affects:** 1.0.46-alpha onward — the Rescan button was fixed, this path was not.
**Status:** **not fixed**, deliberately scoped out at the time.

`AsioBackend.probeAll()` opens, `init`s and releases every registered ASIO driver on every
`devices()` call — measured at ~700 ms on the development machine. Rescan now does this on a worker
thread with a `Scanning…` status, but selecting a different driver in the combo still enumerates
synchronously on the event thread.

The `scan()` / `applyScan()` split introduced for Rescan is exactly what this path needs; it was left
alone only to keep that change narrow.

---

## 3. Java Sound cannot see hot-plugged devices, and Rescan does not help

**Affects:** every version, the Java Sound backend only.
**Status:** **not fixed**, and probably not fixable at our level.

`com.sun.media.sound` enumerates its mixers and does not pick up hardware plugged in after the JVM
started. So on the fallback backend, plugging an interface in and pressing Rescan finds nothing —
the device list is fixed for the life of the process and only a restart updates it.

This sits **below** our seam, which is why the 1.0.46 Rescan work does not reach it: `Backends.rescan()`
correctly invalidates what each backend caches, and `JavaSoundBackend` caches nothing of ours — it
asks `AudioSystem` afresh every time and gets the same stale answer. ASIO and WASAPI both
re-enumerate properly.

Worth telling the user rather than fixing: if Rescan finds nothing on Java Sound after plugging
something in, the honest message is "restart the application", not silence.

---

## 4. Latency figures are the driver's claims, not measurements

**Affects:** the numbers in CLAUDE.md, README.md and the app.
**Status:** stated honestly in CLAUDE.md already; recorded here so it is not mistaken for done.

Every ASIO round-trip figure quoted anywhere in this project comes from `ASIOGetLatencies` — the
driver's own report, which includes its safety offsets and excludes the converters. **No true
cable-to-cable measurement has ever been taken.** `--measure` and `LoopbackProbe` exist for exactly
this and need a lead from the output back into the input. Until that is run, every latency number
here is a good estimate presented as a fact.

---

## 5. Linux ships with one laptop's worth of evidence

**Affects:** 1.1.0-beta onward, every Linux backend. **Status:** known scope, listed for honesty.

Linux runs since 1.1.0 — JACK, ALSA and Java Sound, packaged as an AppImage — but everything known
about it came from one Manjaro laptop's built-in HDA card. Specifically:

- **No guitar interface has been tried on Linux.** The 10–12 ms question is unanswered there.
- **JACK has only met a jackd running the dummy driver**, without realtime priority. It has never
  been run through PipeWire's JACK library, which is how most users will reach it.
- **ALSA direct fails at 128 and 64 frames on that card**, and so does ALSA's own `alsaloop` on the
  same pair of devices, so the limit is believed to be the hardware's. 256 frames runs clean.
- **The window has been started, not looked at** — not at the Steam Deck's 1280×800, not under
  fractional scaling, and the file dialogs have not been opened. See `linux-compat.md` F7 and F8.

The degradation path was exercised on Linux against 1.1.0-beta (JNA off the classpath: Java Sound
streams, both native backends explain themselves). It found and fixed a crash in `--list-backends`;
the next run of it should be dated here too.

---

## 6. `Octave`'s detector is weak on harmonic-rich low notes — measured, never observed

**Affects:** 1.0.47-alpha, the `Octave` FX slot type.
**Status:** **no confirmed defect.** Listed as a characteristic, not a fault.

### 6.1 The report this started from was withdrawn

An earlier revision of this file carried this as issue §1, at the top, described as audible and
specific. It was opened on a user report — notes changing pitch mid-decay at frets 2–4 on an Ibanez
TOD10N — and **the user has since traced that to a buzzing string, not to the module.** Nobody has
heard `Octave` mistrack. The severity claim, the "reported symptom exactly" conclusion and the
priority that followed from them were all wrong and have been removed.

Recorded rather than deleted, because it is the failure mode worth remembering: a synthetic model
built to reproduce a described symptom will generally succeed at reproducing it, and its agreeing
with the report is then evidence of nothing at all. The model was never checked against the
instrument before the conclusion was written down.

### 6.2 What is left, and it is real

The measurement itself stands, because it is a property of the code rather than of the report. Read
headlessly off the flip-flop's inter-transition periods, calibrated to 100 % correct on pure sines
first, against a synthetic nylon/piezo note (fundamental split into two detuned polarisations, second
harmonic +5.6 dB above it, a 101 Hz body resonance, a 6 ms pick click). Across E2–B2:

| | correct octave | an octave high |
| --- | ---: | ---: |
| shipped defaults (TRACK 450) | **17 %** | **77 %** |
| TRACK 110 | 75 % | 21 % |
| low shelf in front, TRACK 450 | 86 % | 12 % |
| both | 87 % | 1 % |

The mechanism is not in doubt: a 12 dB/oct pole at 450 Hz attenuates a 92 Hz note's second harmonic
by **0.1 dB**, so if that harmonic arrives louder than the fundamental — which is what a bridge-force
piezo does — the comparator counts it and divides that instead. What *is* in doubt is whether any
real pickup presents the +5.6 dB the model assumed.

So the honest statement is narrower than the old one: **`TRACK 450` is a poor default for a low
note**, and a low shelf ahead of the module improves the detector's diet. Both are settings, not
code.

### 6.3 If it is ever confirmed on a real instrument

Three weaknesses, cheapest first, unchanged from the earlier analysis:

1. One detector pole at a fixed frequency cannot suit two octaves of neck. Two cascaded poles for
   24 dB/oct is the largest effect for the least work.
2. The threshold and the sub's amplitude come from the *unfiltered* input, so the pick click decides
   when the flip-flop may count. Running the envelope on the filtered signal is about one line.
3. The flip-flop has no memory and cannot reject an edge arriving implausibly early. Tracking the
   running period is the only one of the three that removes the octave-up failure rather than
   filtering around it.

**Do none of this without a recording from the actual guitar.** That is the whole lesson of §6.1.
