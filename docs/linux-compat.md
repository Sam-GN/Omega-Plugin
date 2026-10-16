# Linux compatibility — what has to happen

**Status: plan. Nothing here is built.** Written 2026-08-17 against 1.0.59-alpha. Goal is **maximum
distribution coverage**, and the format chosen for that is **AppImage** — see §3 for why, and for
what it rules out.

**No part of this program has ever been executed on Linux.** Every prediction below comes from
reading the source, not from running it. Where something is genuinely unknown it says so rather than
guessing, because the difference between "will need a tweak" and "has never been tried" is the whole
value of this document.

Distinct from `theme-optional.md`, `profile-optional.md` and `pickup-optional.md`, which propose
features nobody has promised. This is not a feature — it is the work required to make what already
exists run somewhere else. It expands `known-issues.md` §5.

---

## Contents

1. [Where it stands today](#1-where-it-stands-today)
2. [The target list, deduplicated](#2-the-target-list-deduplicated)
3. [Distribution: AppImage](#3-distribution-appimage)
4. [W1 — the JACK backend](#w1--the-jack-backend)
5. [W2 — realtime scheduling](#w2--realtime-scheduling)
6. [W3 — a Linux runtime and the AppImage build](#w3--a-linux-runtime-and-the-appimage-build)
7. [W4 — the Java Sound fallback is Windows-shaped](#w4--the-java-sound-fallback-is-windows-shaped)
8. [W5 — display, scaling and small screens](#w5--display-scaling-and-small-screens)
9. [W6 — desktop integration and platform-flavoured UI](#w6--desktop-integration-and-platform-flavoured-ui)
10. [W7 — the one experiment runnable today](#w7--the-one-experiment-runnable-today)
11. [Per-distro notes](#11-per-distro-notes)
12. [How this gets tested](#12-how-this-gets-tested)
13. [Suggested order](#13-suggested-order)

---

## 1. Where it stands today

**Roughly 90 % of the source would run unchanged. The app would still be useless.** Everything that
does not carry over is audio backends, and without them the low-latency story is gone entirely.

### 1.1 Carries over with no work at all

| Layer | Why |
| --- | --- |
| **`dsp/`** — every effect, NAM, IR, the whole rhythm section | Mono float arrays and arithmetic. Does not import `audio/` and knows nothing about devices. All 305 tests are headless and pass on any JVM. |
| **`preset/`** | `PresetStore` already branches: `%APPDATA%` on Windows, `~/.config/omega-plugin` elsewhere. |
| **Recordings folder** | `MainWindow.defaultRecordingFolder` already branches to `$HOME`, with a comment explaining why it does *not* invent a `~/Desktop` the desktop environment may not use or may call `Escritorio`. |
| **`ui/`** | Nimbus is forced in `Theme.install()` precisely because, unlike the native look-and-feels, it honours colour overrides on Linux as well as Windows. Everything distinctive is hand-painted. |
| **Engine and backend registry** | `AudioEngine` is the callback body and nothing else. `Backends` reaches candidates by name and tolerates their absence. |
| **Build** | Gradle plus the foojay resolver provisions its own JDK 21, so the host's `JAVA_HOME` does not matter. |

### 1.2 Does not work, and must be fixed

Numbered so the workstreams below can point at them.

**F1. There is no Linux backend.** `Backends.OPTIONAL` names
`org.example.audio.jack.JackBackend` and `org.example.audio.alsa.AlsaBackend`; **neither source file
exists**. `Class.forName` throws `ClassNotFoundException`, which `load()` deliberately swallows
without a word — that catch is there so an ALSA backend missing on Windows is not reported as a
fault, and it has exactly the same silencing effect in reverse. → **W1**

**F2. The only available backend would be Java Sound**, whose own figures on the development machine
are a 2116-frame floor and a 7056-frame preference: 48 ms at absolute best, 160–200 ms in practice,
against a project requirement of 10–12 ms. → **W1**

**F3. No realtime scheduling.** `Rt.joinProAudio()` is MMCSS and Windows-only. There is no Linux
equivalent in the code, and the JVM cannot set `SCHED_FIFO` from pure Java. → **W2**

**F4. The portable bundle is Windows-only by construction.** A jlink image is per-platform and
`portableZip` builds from the Windows x64 toolchain — the archive name says so on purpose. → **W3**

**F5. `omega.sh` calls bare `java`**, so it needs a Java 21 on `PATH`. This is the same trap
`../build/libs/omega.bat` has on the development machine, where the PATH JVM is too old for generational
ZGC and the process dies before `Main.collectorWarning()` can explain why. → **W3**

**F6. The Java Sound tuning answers a Windows problem.** → **W4**

**F7. Fixed pixel sizes have never been seen at a small resolution or under fractional scaling.**
→ **W5**

**F8. `FileDialog` is the wrong choice on Linux.** → **W6**

**F9. No `.desktop` entry or installed icon**, so the app cannot appear in a launcher. → **W6**

**F10. A hardcoded path separator in a user-facing string.** `MainWindow.kt:1397` reads
`"Writing to logs\\omega.txt in the settings folder."` — **correct on Windows**, where the file
genuinely is at `%APPDATA%\Omega-Plugin\logs\omega.txt`, and wrong on Linux, where the same note
would print a backslash for a file at `~/.config/omega-plugin/logs/omega.txt`. The path itself is
built with `Path.resolve` and is right on both; only the message is fixed. Wants `File.separator` or
to be derived from the real path — **not** a forward slash, which would trade a Linux fault for a
Windows one. → **W6**

**F11. The degradation ladder has never been exercised.** → **W7**

### 1.3 Correct already, and worth not breaking

`AsioBackend.availability` evaluates `!Rt.isWindows` **first**, so the `when` short-circuits before
`AsioDriver.registered()` touches `Advapi32Util`. No JNA Windows class is loaded on Linux and there
is no `UnsatisfiedLinkError` to catch. `WasapiBackend` does the same. `--list-backends` would print
all three as unavailable with honest reasons.

**Keep that ordering.** Reversing the branches would turn a clean "ASIO is a Windows driver model"
into a `NoClassDefFoundError` caught three layers away and reported as something else.

---

## 2. The target list, deduplicated

Manjaro, CachyOS, SteamOS, Bazzite, ChimeraOS, Pop!\_OS, Ubuntu, Mint and Fedora are nine names and
**three** problems.

| Family | Members | What it forces |
| --- | --- | --- |
| Debian / Ubuntu | Ubuntu, Mint, Pop!\_OS | X11 still common; RT permissions need setting up by hand |
| Fedora | Fedora Workstation | Wayland default; `rtkit` present |
| Arch rolling | Manjaro, CachyOS | Newest JDK and PipeWire — breaks first, warns first |
| **Immutable / atomic** | **SteamOS, Bazzite, ChimeraOS** | **Cannot install into the root filesystem** |

The last row decides the packaging answer on its own. SteamOS has a read-only rootfs and
`steamos-readonly disable` is undone by updates; Bazzite is rpm-ostree. **`.deb`, `.rpm` and AUR
packages are impossible there** — they would cover six of nine and miss precisely the ones a
guitarist with a Steam Deck would want.

**The one piece of good news is large:** PipeWire is now universal across all nine — Fedora since 34,
Ubuntu since 22.10, Mint 21.3+, Pop, and every Arch-based one including SteamOS and Bazzite. That is
what makes a single backend cover the whole list; see **W1**.

---

## 3. Distribution: AppImage

**Decided: AppImage.** One file, no installation, runs on all nine including the immutable three,
and it is conceptually the same artefact as the Windows portable zip — the program plus the JVM it
needs, in a folder that assumes nothing about the host.

The alternative was Flatpak, and it is worth writing down why not, because it is the more
"correct" answer for Linux desktops generally:

| | AppImage | Flatpak |
| --- | --- | --- |
| Covers all nine | yes | yes |
| Device access | direct — no permissions to negotiate | needs `--device=all`, `--socket=pulseaudio` |
| Filesystem for NAM captures, IRs, recordings | direct | needs `--filesystem=home` |
| Realtime priority | direct | through a portal, more to go wrong |
| Discoverability | a file you download | Flathub, appears in software centres |
| Build complexity | close to what `portableZip` already does | manifest, runtime, submission |

For an audio application the sandbox is a cost rather than a benefit: every one of the things this
program does — grabbing a device, taking realtime priority, reading a capture file the user picked —
is a thing the sandbox exists to make harder. AppImage gets to the working state faster and with
fewer per-distro surprises.

**Not ruled out forever.** If Flathub distribution is ever wanted, the AppImage work is not wasted —
both need the same Linux jlink runtime from **W3**.

Requirements AppImage brings:

- **FUSE.** Present on all nine, but Ubuntu 22.04+ ships FUSE 3 while older AppImages want FUSE 2.
  Build with a current `appimagetool` and this is a non-issue; worth knowing when a user reports
  "nothing happens when I run it".
- **glibc floor.** An AppImage runs against the *host's* glibc. Building on the oldest target — an
  Ubuntu 22.04 container is the usual choice — is what makes it run on everything newer. Building on
  CachyOS and shipping to Mint would fail with a glibc version error.

---

## W1 — the JACK backend

**The single highest-leverage item on this list.** One backend, all nine distros, and it is the
difference between a 200 ms toy and the product.

### Why JACK and not ALSA

- **JACK is callback-driven with one clock for both directions**, which is exactly the shape
  `AudioBackend` already assumes. Architecturally it is closer to ASIO than WASAPI's two-clock ring
  is, and WASAPI was the harder of the two to fit.
- **`pipewire-jack` provides the JACK API on all nine distros without JACK being installed.** That is
  what collapses nine targets into one implementation.
- **The callback thread is created by JACK and already runs at realtime priority.** We attach to it,
  exactly as with ASIO's driver thread — which means **W2** is largely solved for free on this path.

ALSA direct would be the second option and materially worse: every capability query written by hand,
and `DeviceCapabilities.bufferSizes` must contain only sizes the driver genuinely accepts **at the
rate asked about** — the rule that already cost two measured bugs on Windows.

### What it involves

- Implement `AudioBackend` in `org.example.audio.jack` — the name is already in `Backends.OPTIONAL`,
  so nothing else in the app changes.
- Reach `libjack.so.0` through JNA. Already a dependency, already the technique used for `IASIO`.
- Attach the callback thread **once** at stream open with
  `CallbackThreadInitializer(daemon = true, detach = false)`. Attaching per callback is a
  hundreds-of-microseconds allocating operation and an instant failure at these periods.
- `StreamFormat.adapterLatencyFrames` is zero here — one clock, no ring — which is worth stating
  because it makes the cross-backend comparison honest.
- JACK owns the sample rate and the buffer size; the client does not choose them. That is what
  `DeviceCapabilities.fixedSampleRate` and `StreamEvents.onFormatChanged` already exist for, and this
  is the first implementation that actually exercises them.

### Things to verify rather than assume

- **The shim's library path.** On some distros `pipewire-jack` and real `jack2` conflict, and the
  shim wants `pw-jack` as a wrapper or `LD_LIBRARY_PATH` pointed at `/usr/lib/pipewire-0.3/jack`.
  Loading `libjack.so.0` by name should resolve either way — verify, do not assume, and fall back to
  the explicit path with a clear `Availability.Unavailable` reason if it does not.
- **Client naming and auto-connect.** A JACK client that connects itself to the wrong ports on start
  is worse than one that connects to nothing.

**Acceptance:** `--list-backends` shows JACK available with real device names; `--selftest --chain
--stress` holds realtime at a buffer that puts the round trip inside 10–12 ms.

---

## W2 — realtime scheduling

Replaces **F3**. Mostly free on the JACK path, and not free anywhere else.

- **On JACK**, the callback thread is JACK's own and already `SCHED_FIFO`. Nothing to do but attach
  correctly and not lower it.
- **Anywhere else** — an ALSA backend, or Java Sound — the thread is ours and the JVM cannot promote
  it. That needs `pthread_setschedparam` through JNA, which is available but is new native surface.
- **`Rt` needs a Linux sibling** so the backends stop reaching for a Windows-only object. There is no
  timer-resolution equivalent to raise: `beginHighResolutionTimer` is answering a Windows problem
  that does not exist here, and the Linux implementation of it is a no-op that returns false.

### The part that is not code

Whether the *user* is permitted realtime at all varies, and this is a support burden more than an
engineering one:

- **Fedora, Bazzite** — `rtkit` present by default, generally works out of the box
- **Manjaro, CachyOS** — `realtime-privileges` package, user added to the `realtime` group. CachyOS
  ships schedulers tuned for this and is likely the best case on the list
- **Ubuntu, Mint, Pop** — usually needs `/etc/security/limits.d/audio.conf` carrying
  `@audio - rtprio 95` and `@audio - memlock unlimited`, plus group membership
- **SteamOS, ChimeraOS** — available, but Game Mode is a different environment from Desktop Mode

**This wants a diagnostic, not just a doc.** The app already reports `realtimeRatio`, and the honest
failure here is "it crackles and the user has no idea they are not in the audio group". A startup
check that reports whether realtime priority was actually granted — in the same spirit as
`Main.collectorWarning()` — turns a mystery into an instruction.

---

## W3 — a Linux runtime and the AppImage build

Fixes **F4** and **F5**.

- **`portableRuntime` needs a Linux variant.** jlink can build a Linux image from a Windows host by
  pointing `--module-path` at the Linux JDK's `jmods`; building on Linux is simpler and is what CI
  would do anyway. The module list needs no change — `java.base`, `java.desktop`, `java.logging`,
  `java.management`, plus `jdk.unsupported` for JNA's reflective `sun.misc.Unsafe` reach and
  `jdk.management` for `getCurrentThreadAllocatedBytes`.
- **The AppImage wraps that same tree**: `AppRun`, a `.desktop` file, an icon, `lib/` and `runtime/`.
  Structurally the Windows portable zip with a different launcher.
- **`AppRun` must use the bundled runtime**, `$APPDIR/runtime/bin/java`, and never bare `java`. This
  is **F5**, and the Windows portable launcher already does it right —
  `%~dp0runtime\bin\java.exe` — while `../build/libs/omega.sh` does not.
- **Build on the oldest supported glibc**, in practice an Ubuntu 22.04 container. See §3.
- **Keep the GC flags.** `-XX:+UseZGC -XX:+ZGenerational` is part of the product, not a preference,
  and a manifest cannot carry VM options any more on Linux than on Windows.

**Acceptance:** the AppImage runs `--list-backends` and prints `Collector: generational ZGC. Good.`
on a machine with no Java installed at all.

---

## W4 — the Java Sound fallback is Windows-shaped

Fixes **F6**. Lower priority than it looks, because after **W1** Java Sound stops being the only
option — but it is still the bottom rung of the ladder and it should not be actively wrong.

Everything in `audio/javasound/` — `CushionRegulator`, the priming write, the line sizing in
`AudioConfig` — exists to answer one specific behaviour: `com.sun.media.sound.DirectAudioDevice`
implementing blocking `read`/`write` as a poll, against a Windows scheduler tick of 15.6 ms. On Linux
the JDK's provider talks to ALSA and the blocking behaviour is different.

So those constants are tuned for a problem that may not exist there, may exist differently, or may be
replaced by another. **Unknown in both directions** — Linux could hold far tighter buffers, or the
regulator could fight a driver that needs none of it. `CushionRegulator` has no setpoint and corrects
at a rate limit; against different blocking behaviour that is exactly the kind of loop that
misbehaves quietly rather than failing.

The tunables already exist for the experiment: `-Domega.audio.captureMs`, `-Domega.audio.playbackMs`,
`-Domega.audio.cushionMs` and `-Domega.audio.drift=false`. **Verify with `--selftest`, not by ear.**

There is also a device-naming problem worth expecting: the JDK opens **ALSA**, not PipeWire, so
enumeration surfaces `default` and `hw:0,0` rather than anything a person recognises. The input and
output pickers are only as useful as the names in them.

---

## W5 — display, scaling and small screens

Fixes **F7**.

### The Steam Deck is a real target and a real problem

**1280×800.** The board currently wants roughly 1600 px to lay out without wrapping. On a Deck it
would wrap to three or four rows, and the second bar row would wrap as well. It would *work* —
`BarLayout` and `ChainBoard` both wrap correctly — but nothing in the design has ever been checked at
that size.

### Fixed sizes that will not re-derive

Most widths in the app come from `getFontMetrics(...).stringWidth(...)` and will re-derive correctly
under a different font. These will not:

- the device combos pinned to 190 px
- the logging note in `DarkMenu`, sized once for the longer of its two messages
- `PushButton`'s `HEIGHT`, `DEFAULT_MIN_WIDTH` and `PADDING`
- `EffectPanel`'s `KNOB_WIDTH`, `MIN_BODY` and `PEDAL_HEIGHT`

None is fatal. All are places where text can clip or sit oddly.

### Fonts

`Theme` uses `Font.SANS_SERIF`, a *logical* font — Segoe UI on Windows, DejaVu Sans or similar on
Linux, with different metrics. DejaVu is present on all nine, so the fallback is safe; the layout
will reflow rather than break.

### Wayland, XWayland and scaling

JDK 21's AWT has no native Wayland toolkit, so everything runs through **XWayland** whatever the
session is. That works, with two consequences:

- **Fractional scaling through XWayland is blurry.** Wants `-Dsun.java2d.uiScale` handling, and
  probably a UI scale setting rather than trusting the environment.
- **`isUndecorated = true` with hand-painted chrome** — `MainWindow` and all three `ConfirmDialog`
  variants — behaves differently per window manager. Undecorated windows lose snapping on some.
  Focus handling is worth watching for both `setLocationRelativeTo` and the click-away commit in
  `DarkTextField`, which moves focus to a sink panel.
- **SteamOS Game Mode runs gamescope**, a deliberately restrictive compositor. A windowed application
  belongs in Desktop Mode; do not expect Game Mode to be sensible and say so in the docs.

---

## W6 — desktop integration and platform-flavoured UI

Fixes **F8**, **F9**, **F10**.

- **`FileDialog` should branch.** The current choice is deliberate and correct on Windows — "it is
  the platform's real dialog, so it looks and behaves like every other one" — but on X11 the JDK
  implements the dialog itself and the result is genuinely poor. `JFileChooser` with
  `DarkChrome.apply()` would look *better* there, which inverts the reasoning. Affects the preset
  import, the impulse-response loader and the NAM loader.
- **`.desktop` entry and icon.** `../src/main/resources/icons` already has 16 through 512 px PNGs. The
  AppImage needs both at its root, and they are what make the app appear in a launcher at all.
- **`Desktop.getDesktop().open()`** — used for the logs and recordings folders — needs `xdg-open`,
  present on all nine. Already guarded with `isDesktopSupported()` and run off the event thread.
- **`MainWindow.kt:1397`** hardcodes a backslash in the ☰ → Logging note. Right on Windows, wrong on
  Linux. `File.separator`, or derive it from the path `Log` actually opens — see **F10** for why a
  forward slash is not the fix.

---

## W7 — the one experiment runnable today

Fixes **F11**, and needs no Linux machine.

The largest single assumption in this document is that the fallback ladder behaves when the native
backends are absent. CLAUDE.md already prescribes the test: **run with JNA off the classpath and
confirm the app starts, streams on Java Sound, and explains itself.**

That exercises the registry's `Throwable` handling, the `Availability.Unavailable` reasons and the
Java Sound path as sole backend — which is the whole of §1.2's **F1** and **F2** behaviour — and
leaves only the genuinely Linux-specific unknowns.

**Not done yet, and there is no record of it ever having been done.** A degradation path that has
never been exercised is one that does not work.

---

## 11. Per-distro notes

Only the things that differ. Everything not mentioned is covered by the AppImage.

| Distro | Worth knowing |
| --- | --- |
| **Ubuntu / Mint / Pop!\_OS** | RT permissions usually need `/etc/security/limits.d/audio.conf` and group membership. X11 still common, which is the *easier* case for us. Ubuntu 22.04 is the glibc build floor. |
| **Fedora** | Wayland by default, `rtkit` present. The closest thing to a reference desktop target. |
| **Manjaro** | `realtime-privileges` package; `manjaro-pipewire` metapackage. Rolling, so it sees new JDK and PipeWire versions first. |
| **CachyOS** | Custom schedulers and optional RT kernels — likely the best latency on the list, and the best place to find the ceiling. |
| **SteamOS** | Immutable; AppImage or Flatpak only. Desktop Mode for this app, not Game Mode. 1280×800 on a Deck. |
| **Bazzite** | rpm-ostree, Flatpak-first, gaming-tuned kernel and PipeWire. If it works here, SteamOS and ChimeraOS follow. |
| **ChimeraOS** | Arch-based and immutable; treat as SteamOS. |

---

## 12. How this gets tested

Nine distros cannot be tested. **Three** cover the axes:

1. **Fedora Workstation** — Wayland, PipeWire, rtkit, dnf. Stands in for itself and approximates
   Ubuntu, Mint and Pop.
2. **Bazzite** — immutable, Flatpak-first, gaming kernel. If it works here, SteamOS and ChimeraOS
   follow.
3. **CachyOS or Manjaro** — Arch rolling, newest everything. Catches "breaks on the next version"
   before users do.

**VMs are fine for everything except latency.** A virtual machine's audio path says nothing about
whether 10–12 ms is reachable — that has to be measured on metal, with `--selftest --chain --stress`,
and ideally confirmed with `--measure` and a patch lead.

The headless suite already runs anywhere and needs no device, so CI on Linux is free the moment there
is a Linux job.

---

## 13. Suggested order

1. **W7** — the JNA-off-the-classpath test. Costs an afternoon, runs on Windows, and validates the
   biggest assumption before any Linux work starts.
2. **W1** — the JACK backend. Everything else is polish until this exists.
3. **W2** — realtime verification and the startup diagnostic. Mostly free on the JACK path, but the
   diagnostic is what stops "it crackles" being unanswerable.
4. **W3** — Linux jlink and the AppImage. First artefact anyone else can run.
5. **W5** — HiDPI and the small-screen pass. The Deck is a genuine target for a practice tool.
6. **W6** — desktop integration and the `FileDialog` branch.
7. **W4** — Java Sound re-tuning. Last, because by then it is a fallback rather than the only path.

**A note on why this order matters beyond convenience.** CLAUDE.md records that a seam validated by
one native implementation is probably the wrong seam. This one has two, and both are Windows. **W1**
is therefore not only the feature that makes Linux usable — it is the first genuine test of whether
`AudioBackend` is the right abstraction at all, and the most likely place to discover it is not.
