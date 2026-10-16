# Pickup Position — an optional comb-filter module

**Status: proposal. Nothing here is built.** Written 2026-08-12 against 1.0.47-alpha as a plan to
review later, not as a commitment. Companion to `theme-optional.md` and `profile-optional.md`.

The idea: a small FX slot type that imposes the **comb filtering a magnetic pickup produces by
sensing the string at one point along its length** — the thing that makes a bridge pickup sound
thin and quacky and a neck pickup sound round, on an instrument whose pickup has no such character.

---

## 1. The physics, which is the whole module

A magnetic pickup sits a fixed distance *d* from the bridge and senses the string's motion at that
one point. It therefore cannot hear any harmonic with a node there. For harmonic *n* on a string of
length *L*, the sensed amplitude goes as:

```
|sin(n · π · d / L)|
```

which is a comb. The useful consequence: nulls occur when `n·d/L` is an integer *m*, so
`n = m·L/d`, and since harmonic *n* sits at `n·c/(2L)`, the null frequencies are

```
f = m · c / (2d)
```

**with L cancelled out.** The notches sit at fixed absolute frequencies set by the pickup position
and the string's wave speed, and do **not** move as you fret up the neck. That is what makes a plain
fixed-delay comb the physically correct model rather than an approximation.

An undersaddle piezo — which is what the TOD10N has — sits about as close to the bridge as it is
possible to get, so it has almost no comb at all. That is a specific, identifiable thing the
instrument is missing, and it is cheap to supply.

---

## 2. Why it is worth building, per the 2026-08-12 measurement

The recording analysed for `profile-optional.md` established where this instrument actually has
sustained energy: strong from roughly 150 Hz to 2.5 kHz, with essentially nothing sustained above
6 kHz.

For a Stratocaster the comb notches land between about **330 Hz** (neck position, *d* ≈ 16 cm) and
**1.3 kHz** (bridge, *d* ≈ 4 cm) for the first null, using a wave speed of ~107 m/s taken from a
650 mm scale at E2.

**Those notches land squarely inside the band where sustained energy exists.** So this module would
do audible work on real signal, rather than carving silence — which is the failure mode that makes
most "add character" processing pointless. That was worth checking before proposing it.

---

## 3. Implementation

A **single feedforward comb**: one short delay line, one add.

```
y[n] = x[n] − g · x[n − D]
```

with `D = 2d / c` in seconds, converted to samples in `prepare(sampleRate, …)` — never from a
hardcoded rate. `g` controls notch depth; a true point-sensing pickup is `g = 1`, but a real coil
has finite aperture, so slightly less than 1 is more faithful and much less brittle.

Properties that make it a good fit for this codebase:

- **Trivially cheap** — one multiply, one add, one ring index per sample.
- **Sub-millisecond delay**, and the delay is inherent to the effect rather than added latency in
  the sense the threading contract cares about.
- **Allocation-free**, with the ring sized once in `prepare`.
- **Testable against arithmetic**: feed a sine at a predicted null and assert it is gone; feed one at
  a predicted peak and assert it is not. The notch frequencies come from the formula above, not from
  what the code happens to return — the house rule for tests here.

**Parameters:** `Position` (bridge→neck, continuous, mapped to *d*), `Depth` (`g`), `Mix`. `Mix`
defaults to 0 so a freshly-spawned slot is bit-transparent, asserted with `assertContentEquals`.

Added as **one line at the end of `FxSlot.TYPES`** — append-only, for the reason recorded in
CLAUDE.md.

---

## 4. Honest scope: what this is not

**A static transfer function already contains the comb.** If you build a match-EQ curve or an IR from
a real Strat recording, the notches are baked into it, because they are fixed in absolute frequency
(§1). So this module is **not** adding a dimension that `Ir` or a profile correction lacks.

What it adds instead:

- **Sharpness.** Spectral matching averages and smooths, which turns deep narrow notches into gentle
  dips — and the notches are precisely what makes a pickup position recognisable.
- **A continuous knob** instead of one captured IR per position, with no file to manage.
- **Almost no CPU**, against a convolution.

It is a detail module. It will not on its own make a nylon guitar sound electric; see
`profile-optional.md` §2.3 for which module owns which part of that job.

---

## 5. Open questions

1. **Is the wave speed worth exposing?** It differs per string and per tuning, so one `Position` knob
   is a simplification. Probably right to keep it simple and let the knob be "sounds like" rather
   than calibrated centimetres — but that should be a deliberate choice, and the label should not
   claim more precision than it has.
2. **Two pickups in parallel?** The classic in-between Strat positions are two pickups summed, which
   is a different and more interesting comb. Cheap to add later; not phase one.
3. **Does it belong before or after the drive** in the default board? Physically it is part of the
   instrument, so before — but that is worth hearing rather than deducing.
