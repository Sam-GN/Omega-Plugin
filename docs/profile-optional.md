# Profile — an optional instrument-matching module

**Status: proposal. Nothing here is built.** Written 2026-08-12 against 1.0.47-alpha as a plan to
review later, not as a commitment. Companion to `theme-optional.md`.

The idea: a module that **measures the instrument actually plugged in** and applies the correction
that moves it toward a chosen target — an electric guitar, a different acoustic, a flat reference.
The same operation a Fishman Aura or a DAW "match EQ" performs, but derived from *your* instrument
rather than from whichever one was used to bake a generic impulse response.

---

## 1. Why this exists

The motivating case is real and measured. The instrument is an Ibanez TOD10N — a nylon-string guitar
with an AeroSilk undersaddle piezo — and the goal is for it to read as an electric guitar.

A downloaded "acoustic to electric" IR corrects someone else's guitar, someone else's pickup and
someone else's hands. A profile module corrects the one in the room. That is the entire argument for
it, and it is a good one — **but §2 is the reason it is not simply a match EQ**, and anyone picking
this up should read that section before writing a line of it.

---

## 2. What the measurement already proved, and why a naive match EQ would fail

A 115 s recording of the TOD10N, every module bypassed, was analysed on 2026-08-12. Two results, and
the second one is the load-bearing one.

### 2.1 The noise floor is not the constraint

Noise floor −80.3 dBFS RMS; 67 dB between it and normal playing. Per-band signal-to-noise:

| 1 kHz | 2 kHz | 4 kHz | 6.3 kHz | 10 kHz | 16 kHz |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 68 dB | 69 dB | 63 dB | 58 dB | 54 dB | 46 dB |

The expected failure — "correction boosts hiss" — does not apply to this instrument. +20 dB anywhere
below 10 kHz still leaves 34 dB of headroom over the noise.

### 2.2 The high end is the pick, not the string — so a plain average spectrum is the wrong input

83 note onsets were located, and the spectrum of the first 46 ms compared against the same note
200 ms later, each normalised to its own 200–800 Hz energy so only shape is compared:

| | 1 kHz | 2 kHz | 3 kHz | 5 kHz | 6 kHz | 8 kHz | 10 kHz |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| attack − sustain | +1.8 | +1.0 | **+6.7** | **+6.7** | **+13.8** | **+17.2** | **+15.7** |

Below 2 kHz the attack and the note have the same shape. Above 6 kHz the *note* has essentially
nothing — 38–45 dB down, against 25 dB for the attack. The material was **all pick playing**, which
is the brightest way to excite a string, so this is the best case; fingers would measure duller.

**Therefore: a correction derived from a long-term average spectrum would be wrong.** The LTAS is
dominated in its top octaves by transient energy that exists for 46 ms of every note. Matching it
tells the filter to boost 3–10 kHz, which lifts pick click by 7–17 dB and brings up no sustain at
all, because there is none to bring up. It would measure as a perfect match and sound like a bag of
gravel.

**The module must profile the *sustain*, not the average.** Onset-gated, windowed some fixed time
after each attack. This is the single most important design constraint in this document and it was
not obvious in advance — it came out of a measurement taken specifically to check it.

### 2.3 The corollary: a filter is the wrong tool for the missing half

Above ~3 kHz there is nothing sustained to correct *toward* a target. No linear filter creates
harmonics; only a nonlinearity does. So the honest division of labour is:

- **Profile / EQ** — the 200 Hz to ~2.5 kHz region, where sustain genuinely responds.
- **Overdrive** — manufactures the upper harmonics the string never produced. Load-bearing, not
  garnish.
- **Transient** — the pick attack, which is a time-domain problem and cannot be filtered.
- **Ir** — the speaker rolloff above ~5 kHz, which is free and also disposes of the click.

A profile module that silently promises to do all four would be lying.

---

## 3. Architecture

### 3.1 Capture — off the audio thread, always

Three states: **idle**, **listening**, **profiled**.

- Listening collects onset-gated sustain windows plus a separate **silence** capture for the noise
  floor. Both accumulate into averaged periodograms.
- All of it runs on a worker, never in `process()`. The audio thread's only job is to copy blocks
  into a ring; the analysis drains it. That keeps every clause of the threading contract intact.
- Publishing the finished correction follows the rule CLAUDE.md already sets for multi-value
  parameter changes: **build an immutable coefficient snapshot on the worker and swap one volatile
  reference.** No locks, no partially-applied curve.

### 3.2 Targets

Stored curves rather than baked IRs — a short list of band levels, loadable from a file so a target
can be shared without shipping anything copyrightable. Note the project's standing constraint:
**Omega-Plugin ships no captures, IRs or cabinet packs**, and a target profile must not become a
back door for one. A target derived from a public specification or measured by the user is fine.

### 3.3 Correction — biquad bank, not FIR

Recommended: a cascade of peaking biquads fitted to the smoothed correction curve, reusing `Biquad`.

- **Zero added latency**, which an FIR of useful resolution cannot offer. Everything else in `dsp/`
  is zero-latency and it would be a shame to break that for a broad, smooth curve.
- Cheap enough to sit in the chain permanently.
- The correction the measurement calls for is gentle and wide — a few dB over octaves, not a comb.
  Biquads represent that well. If a future target needed fine structure, an FIR path could be added
  behind the same seam.

### 3.4 Gain limiting, computed rather than guessed

Because the silence capture gives a real noise floor, the module can **refuse to boost where the
signal-to-noise does not support it** and say so, instead of producing hiss and leaving the user to
work out why. A hard ceiling on boost (12 dB is a reasonable default) belongs alongside it. This is
the feature that distinguishes it from a DAW match EQ, and it exists only because the capture
measures the floor as well as the signal.

---

## 4. Phases

### Phase 1 — prove it offline
No module. A scratch harness that takes a recording, produces the correction curve, and prints it as
`Eq` settings. Cheap, and it answers whether the whole idea is worth a module before one exists.
**The 2026-08-12 analysis is most of this already.**

### Phase 2 — capture and display
Listening state, onset-gated sustain averaging, silence capture, and a way to see the resulting
curve. No correction applied yet. The hard part is here, and it is all testable headlessly against
synthesised material with a known spectrum.

### Phase 3 — apply it
Biquad-bank fitting, snapshot publication, `Mix`/`Amount` control, bypass. Bit-transparent at zero.

### Phase 4 — targets as files
Load and save target profiles; a small set derived from public data.

---

## 5. Limits to state plainly in the UI

- **Mono, summed.** A Variax works because it has a hex pickup and processes each string separately.
  One summed piezo cannot be unmixed, so intermodulation between simultaneous notes is uncorrectable.
- **It cannot create what is not there** — see §2.3.
- **It corrects timbre, not touch.** Attack, decay and the feel of the instrument are untouched.
- Expect "convincingly electric-ish", not "indistinguishable". That is also what the commercial
  versions deliver.

---

## 6. Open questions

1. **Is this a feature or a preset?** Everything the measurement says is achievable can already be
   done with `Eq` plus `Ir` and no new code. This module earns its place only if the *automatic,
   adapts-to-your-instrument* part is the point — which matters much more for other people's
   instruments than for this one.
2. **How long must listening run** to be stable across playing dynamics and register? The 2026-08-12
   analysis used 115 s; 83 onsets gave a clean result, but the minimum is unmeasured.
3. **Per-target or per-instrument profiles?** Capture once and keep it, or re-capture per session as
   strings age?
4. **Does it belong in an `FxSlot`, or is it a board-level module** like NAM and IR? It is arguably
   part of the instrument rather than an effect.
