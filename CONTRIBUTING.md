# Contributing

The long version of everything here is `CLAUDE.md` at the root — it is written for whoever is
editing the code next, and it explains *why* each of these rules exists rather than just stating it.
This file is the short list of the ones that will get a patch turned down.

Status: **beta, Windows only, one developer, one audio interface.** Nothing about it is settled
except the licence.

## Licence

The program is **GPLv3-or-later** and contributions come in under the same terms. Every source file
carries the header — all 96 of them do today, and a new file without one will be asked for it.

Two dependency rules are deliberate rather than stylistic, and both have been "simplified away" in
other projects:

- **JNA is taken under LGPL 2.1**, not the Apache 2.0 it is also offered under. Both are
  GPLv3-compatible, but a dual licence has to be elected once and written down, and this one is — in
  README.md, `build.gradle.kts` and `ui/Licences.kt`, where a test pins it. Change all three or none.
- **Never introduce the Steinberg ASIO SDK.** It would add a native build step and vendored code
  under a licence of its own, for nothing: `IASIO` is hand-declared from the published vtable layout
  and contains no Steinberg code. Keep it that way.

**No audio assets.** No NAM captures, no impulse responses, no drum samples. Loaders are welcome;
bundled content arrives with its own copyright and reopens a question that is currently closed. It
is why every voice in `dsp/rhythm/` is synthesised.

## Building

```
./gradlew build          # compile, test, fat jar, launchers
./gradlew run            # the GUI, with the JVM flags it needs
```

Java 21 and generational ZGC are **part of the product, not a preference**. The audio callback is an
attached Java thread that cannot opt out of a safepoint, so the collector decides whether the
deadline holds: measured at a 64-frame buffer with the full chain, G1 moved 97.2 % of realtime and
ZGC moved 100.0 %. A manifest cannot carry VM options, so `java -jar` gets none of the flags — use
the generated `omega.bat` / `omega.sh` launchers, and route any new entry point through them.

## The realtime contract

`AudioEngine.render` and every backend callback have **1.45 ms at 64 frames**. Inside them, all of
these are forbidden: allocation of any kind (boxing, capturing lambdas, `for (x in list)`, varargs,
string building), locks, `sleep`/`park`/`yield`, any I/O including `println` and `Log`, exceptions as
control flow, and anything that triggers class loading or lazy initialisation.

Allocation is the one people argue with. It is not that allocation is slow — it is that allocation
is what triggers the collection that stops the thread. `AudioEngineTest` asserts `render` allocates
under 64 kB over 20 000 blocks, in bytes rather than in milliseconds, for exactly that reason.

Errors leave the audio thread as a volatile flag that a monitor thread turns into a log line. That
rule is most tempting to break during a debugging session, which is when it matters most.

## Tests

`src/test/kotlin/`, 300-odd of them, **none needing an audio device**. The habit worth keeping is to
assert against a number you worked out independently — a -12 dB bell reduces its own centre frequency
to a linear 0.251; a 300 Hz low cut attenuates 50 Hz identically at 44.1 and 96 kHz — rather than
against whatever the implementation currently returns. Several real bugs were caught by tests written
that way and by no other means.

Swing, and anything that touches a driver, are not unit-tested. Verify those by running the program,
and by:

```
omega.sh --selftest --chain --stress <in> <out> 60
```

`--chain --stress` is not optional garnish. Without `--chain` you are measuring the device path with
no DSP in it; without `--stress` an idle machine holds almost any buffer size. **Watch
`realtimeRatio`, not `deadlineMisses`** — a collection stalls the thread between callbacks rather
than making one slow, so `deadlineMisses` reads zero straight through audible dropouts.

## Per change

- **Bump the patch version** in `build.gradle.kts` and add a `CHANGELOG.md` entry. Every change,
  including a one-line one.
- State measured figures with the method that produced them. Where something is an estimate, the
  changelog says so — the ASIO latency numbers are the driver's own claims and are labelled as
  claims everywhere they appear.

## Where documentation goes

`docs/` holds three different kinds of file and they must not be confused with each other:

| | |
| --- | --- |
| `known-issues.md` | Defects in code that **already ships**. Nothing speculative. |
| `linux-compat.md` | A **work plan** for the Linux port. Numbered, and unbuilt. |
| `low-latency-backend.md`, `virtual-io.md`, `WhatsDone.md` | History and reference. |
| `theme-optional.md`, `profile-optional.md`, `pickup-optional.md` | **Proposals nobody has promised.** Not a backlog. |

No file in `docs/` uses markdown links; every cross-reference is prose in backticks, which is what
made moving them there cost nothing.
