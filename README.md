# Omega-Plugin

A guitar effects processor for Windows. Plug your interface in, pick it in the app, and play through
a board of pedals — gate, compressor, EQ, gain, overdrive, an amp capture, a cabinet, FX slots,
delay, reverb and a master volume — with a metronome and a drum machine above it. Pedals drag into
any order, and FX slots are added and removed. Despite the name, it is not an IDE plugin — "plugin"
here means a guitar effect.

![The board mid-stream, with a second FX slot added, above the device row and the rhythm section](docs/img/screenshot.png)

The picture is a real stream: WASAPI exclusive at 48 kHz and 144 frames — 9.0 ms by the driver's own
reckoning, 1 % DSP and no dropouts. Every module the program has is on the board; the greyed ones are
switched off, and `IR` and `NAM` are greyed because neither has a file loaded. Nothing sampled or
captured ships with the program, which is why those two have a LOAD button and no factory content.

## Get it

1. Download `Omega-Plugin-<version>-Windows.zip` from the
   [Releases page](https://github.com/Sam-GN/Omega-Plugin/releases).
2. Unzip it anywhere you can write to.
3. Double-click `OmegaPlugin.exe`.

Nothing to install. Java is inside the zip, so an older Java already on the machine cannot interfere.
Keep the folder together: the `.exe`, `app\` and `runtime\` are one unit. A `READ-ME-FIRST.txt` in
the folder repeats this and the troubleshooting steps below.

Windows x64 only, and beta: it works, on one platform, against one audio interface, and has not yet
been run on anyone else's. No part of it has ever been executed on Linux.

## First run

- Pick your audio interface in the device row, input and output.
- In **DRIVER**, choose ASIO if your interface's maker provides an ASIO driver. It is the lowest
  latency by a wide margin. WASAPI exclusive needs no extra driver and is the next best. Java Sound
  is the fallback and is not for playing through.
- **BUFFER** sets the latency. 64 frames is the low-latency setting; 128 is the comfortable one. If
  you load an amp capture, use 128.
- Amp captures (`.nam`) and cabinet impulse responses (`.wav`) are loaded with the LOAD button on the
  `NAM` and `IR` pedals. None ship with the program.

## Latency

Choose the driver in the DRIVER picker; the app falls back down the list on its own and says why.

| Backend | Round trip on the interface this was built against | Notes |
| --- | ---: | --- |
| ASIO | **6.4 ms** at 64 frames, **9.9 ms** at 128 | Needs a driver from your interface's maker. Lowest stable size measured: 16 frames. |
| WASAPI exclusive | 18.0 ms at 264 frames | No extra driver needed. Takes sole control of the device. |
| Java Sound | ~160-200 ms | Always available, never fast. For checking a signal path, not for playing. |

Those are the driver's own figures. For a true cable-to-cable measurement, patch a lead from the
output back into the input and run `--measure` (see below).

RATE and BUFFER sit next to each other because both are latency controls: the callback period is
frames divided by rate, so 128 frames is 2.90 ms at 44.1 kHz and 2.67 ms at 48 kHz. Only rates
**both** the input and the output accept are offered, and there is no resampler — bridging a
mismatched pair would cost more latency than this whole backend saves, so the app names the two
devices and their rates instead.

## If it sounds wrong

Crackles, dropouts or a long delay almost always mean the wrong driver loaded. Open a Command Prompt
in the folder and run:

```
OmegaPlugin.exe --list-backends > report.txt
```

`report.txt` names every audio backend that loaded and, for each that did not, why. If ASIO is
missing, install your interface's ASIO driver. If the delay is around 200 ms, you are on Java Sound.

To check the machine keeps up with the effects on and under memory pressure:

```
OmegaPlugin.exe --selftest --chain --stress <input-device> <output-device> 60 > report.txt
```

Watch `realtimeRatio`, not `deadlineMisses`. Below 100 % means audio was lost even if
`deadlineMisses` reads zero. If it drops, raise BUFFER.

The output is redirected to a file because `OmegaPlugin.exe` is a windowed program and prints nothing
to the console on its own. When reporting a bug, attach `report.txt`.

## For developers

Kotlin on the JVM, Swing for the UI, JNA for the ASIO and WASAPI backends. There is no native build
step. `CLAUDE.md` is the architecture guide and `CONTRIBUTING.md` the short list of rules that get a
patch turned down; `docs/` holds the rest.

Building needs **Java 21 or newer** on the machine (the Gradle toolchain will download one if it is
missing). Build with `./gradlew build` (`.\gradlew.bat build` on Windows). The result is
`build/libs/OmegaPlugin-<version>-all.jar`, together with the `omega.bat` / `omega.sh` launchers
written beside it. **Use the launchers.** They set the JVM flags the low-latency path depends on,
and a bare `java -jar` cannot be given them — the app detects that and says so. The launchers run the
same diagnostics as the `.exe`, printing to the console directly: `--list-backends`,
`--list-devices`, `--selftest` and `--measure`.

`gradlew.bat exeZip` builds the self-contained Windows x64 zip from the Releases page: a bundled
Java 21 and one real launcher, `OmegaPlugin.exe`, with the JVM flags inside it so there is no way to
start it without them. `gradlew.bat portableZip` builds the same bundle with `.bat` launchers
instead, where `omega.bat` prints to the window directly. Neither is part of `build`, because jlink
and jpackage cost tens of seconds on every compile.

A push to `main` is a release: the workflow reads the version from `build.gradle.kts`, builds the
zip and publishes a draft release tagged with it. Every change therefore bumps the version and adds
a `CHANGELOG.md` entry.

Linux has not been attempted. The backend seam is shaped for JACK and ALSA and the code has no
Windows assumption below it, but that is a design claim and not a test result. `docs/linux-compat.md`
is the work plan; treat anything it describes as unbuilt.

## Source and redistribution

Source code: <https://github.com/Sam-GN/Omega-Plugin>

This is a GPL program, so a binary has to reach people together with the source it was built from.
Publishing a release on the repository above satisfies that — the tag the release is built from *is*
the Corresponding Source, offered from the same place as the download, which is what GPLv3 §6(d)
asks for. If a build is ever handed out any other way, it needs either the source alongside it or a
written offer for it, valid three years — §6(a) and §6(b).

`LICENSE` is packed inside the jar and into `distZip`, so a copy handed over on its own still carries
its licence. JNA's own notices ride along in `META-INF/`, where LGPL 2.1 §6 wants them. The same text
is in the application under **☰ → Licences**, which is the one place to change the URL above:
`ui/Licences.kt`.

## Licence

Copyright (C) 2026  Sam Ganji &lt;sam.ganji.n@gmail.com&gt;

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either **version 3 of the
License, or (at your option) any later version**.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If not,
see <https://www.gnu.org/licenses/>.

The full text is in [LICENSE](LICENSE), taken verbatim from
<https://www.gnu.org/licenses/gpl-3.0.txt>.

### Third-party licences

Every component in the jar is GPLv3-compatible on its own terms. The reasons are recorded beside
the names because a compliance reader wants to see that each licence was looked at, not just listed.

| Component | Licence | Note |
| --- | --- | --- |
| Kotlin stdlib | Apache 2.0 | GPLv3-compatible. The Apache 2.0 text ships in the jar at `META-INF/AL2.0`. |
| JetBrains annotations 13.0 | Apache 2.0 | Not declared anywhere in the build — it arrives transitively under the Kotlin stdlib and ends up in the jar. GPLv3-compatible, the same as the stdlib. |
| JDK (runtime) | GPLv2 with Classpath Exception | The exception is what permits linking without infecting the application. The Windows zip bundles an Eclipse Temurin build; its `READ-ME-FIRST.txt` names the exact build, and the source is published by Adoptium at <https://github.com/adoptium/jdk21u>. |
| JNA (`jna` + `jna-platform`) | Apache 2.0 **or** LGPL 2.1 | Dual-licensed, and both options are GPLv3-compatible. This project **takes it under LGPL 2.1**, so its notice obligations are the LGPL's: the texts ride along in `META-INF/`. |

This is the contents of the jar, which is the set the licences apply to. The *declared* dependencies
are fewer — JNA is the only third-party library this project asks for by name, and it is there so
that ASIO and WASAPI can be reached with no native build step and no extra binaries to ship. Verify
the table against `gradlew dependencies --configuration runtimeClasspath` rather than against the
`dependencies` block, which is how the annotations entry came to be missing.

The Steinberg ASIO SDK is deliberately **not** used. The ASIO backend declares the `IASIO` COM vtable
directly, from its published layout, and contains no Steinberg code — so there is no native build
step, nothing vendored, and no second licence to carry.
