# macOS — what has to happen (Apple Silicon only)

**Status: a plan. Nothing in it has been run on a Mac.** Written 2026-11-02 against 2.1.4-beta,
on Windows, from the code, Apple's headers and documentation, and the record of the Linux port in
`docs/linux-compat.md`. It is written so that the implementation can be carried out by someone — or
some model — with less context than its author: every decision that could be made in advance has
been, every place the code has to change is named, every test is listed with what it must assert,
and every measurement that can only be made on a Mac says what to do with each result it could
give. **§0 is the current state** and must be updated as items land; wherever §0 and the plan
disagree, §0 wins, as in `docs/linux-compat.md`.

Three decisions the maintainer has made, and that this document does not reopen:

1. **Apple Silicon only.** No Intel build, no universal binary. GitHub's Intel runners are on their
   way out and nobody is going to buy an Intel Mac to run a guitar through.
2. **No Apple developer account.** The bundle is ad-hoc signed — which jpackage does by itself and
   which Apple Silicon requires of every binary — and nothing more. The first launch therefore goes
   through Gatekeeper's "Open Anyway" setting, once, and the manual says how.
3. **The same product as on the other two platforms.** The same window, the same pedals, the same
   portable download-and-run, the same About → Check for updates → Update that replaces the program
   in place. Nothing is cut for the Mac and nothing is added that Windows and Linux do not have.

It is shaped like `docs/linux-compat.md`, which was the same job for Linux. The difference is that
the Linux plan was written before anyone knew how the seam would hold up and this one is written
after it has held up twice more: `AudioBackend` now has four native implementations behind it
(ASIO, WASAPI, JACK, ALSA) of two different shapes, and CoreAudio is the ASIO shape — one clock,
a thread the system owns, a callback. The backend is the biggest piece of work and the least
uncertain one. The uncertain pieces are all around the bundle: Gatekeeper, the microphone
permission, and the two macOS protections that a program replacing itself runs into.

Distinct from `docs/theme-optional.md`, `docs/profile-optional.md` and `docs/pickup-optional.md`,
which propose features nobody has promised. This is not a feature — it is the work required to make
what already exists run somewhere else.

---

## Contents

0. Status
1. Where it stands today
2. Decisions already made, with their reasons
3. The map: every file that changes
4. M1 — the OS check and the registry
5. M2 — the CoreAudio backend
6. M3 — Java Sound on macOS
7. M4 — the bundle: `OmegaPlugin.app` in a zip
8. M5 — the installer: a bundle that replaces itself
9. M6 — the window on a Mac
10. M7 — where files live
11. M8 — CI: a third leg
12. M9 — the documents
13. The test plan on a real Mac, and what each result means
14. Order of work: what needs no Mac at all
15. Walk-throughs: the design against its own failure modes
16. Risks, with the fallback for each
- Appendix A — the CoreAudio HAL, as JNA sees it
- Appendix B — the `Info.plist` template
- Appendix C — the smoke test
- Appendix D — the microphone permission, read through the Objective-C runtime
- Appendix E — sources

---

## 0. Status

Nothing measured yet. Fill in the State column as items land, with the version that landed them,
and replace this sentence with the machine and macOS version the figures came from.

| Item | What | State |
| --- | --- | --- |
| **M1** | `MacPlatform.isMac`, `Backends.OPTIONAL`, `BackendId.CORE_AUDIO`, `Platform.MAC` | planned |
| **M2** | `audio/coreaudio/`: enumeration, capabilities, single-device stream, aggregate pair, listeners, latency, microphone permission | planned |
| **M3** | Java Sound on macOS — the bottom rung, nothing to build | planned (verify only) |
| **M4** | `macImage` / `macZip` tasks: ICNS, `Info.plist`, jpackage, `ditto` | planned |
| **M5** | `MacInstaller`, `Leftovers` on macOS, the words in `Updates` | planned |
| **M6** | Dark title bar, app name, Dock icon, Cmd+Q, trackpad wheel | planned |
| **M7** | `~/Library/Application Support/Omega-Plugin`, `~/Music/Omega-Plugin` | planned |
| **M8** | `build.yml` third leg, `release.yml` `macos` job, `SHA256SUMS.txt`, `ShippedNamesTest` | planned |
| **M9** | README, manual, CLAUDE.md, CONTRIBUTING.md, CHANGELOG, known-issues | planned |
| **T** | The test plan of §13 on real hardware | not started |

---

## 1. Where it stands today

**Everything above the audio seam runs unchanged.** `dsp/`, `ui/`, `preset/`, the rhythm
section, the loop box, the tuner, the updater's logic — pure JVM and Swing with Nimbus forced,
which is the one look-and-feel that honours colour overrides on every platform. Java 21 with
generational ZGC is supported on macOS aarch64. Run the fat jar on a Mac today and it opens the
whole window and streams on Java Sound, because `Backends.OPTIONAL` lists Windows and Linux
backends only. That is 160–200 ms of latency: the program works and fails the project's one hard
requirement.

**What is missing, in size order:**

- A **CoreAudio backend** — the whole of §5. One clock, the device's own IO thread, a callback with
  input and output buffers in the same call. Reached through JNA exactly as `IASIO` and `JackRt`
  are, from Apple's public headers, with no SDK and no licence question. About the size of
  `JackBackend` plus the aggregate-device code for an input and an output on different boxes.
- **Every place the code says "not Linux, so Windows".** There are two: `Platform.current()` in
  `update/Release.kt`, and the `else` branch of `Backends.Broken.id`. Everything else is already
  behind `LinuxPlatform.isLinux` or `os.name.contains("win")` and falls through correctly.
- **A third bundle** — jpackage's `.app`, zipped — and a third `Installer` for it.
- **Mac manners** in the window: a dark title bar, the right name in the menu bar, Cmd+Q that goes
  through `requestQuit`, and knobs that work from a trackpad.
- **The CI leg, the docs, and the first-run instructions** for Gatekeeper.

---

## 2. Decisions already made, with their reasons

Read these before writing code. Each is a choice that could go another way; the way it goes here
has been thought through, and the implementer should not reopen one without a measured reason.

| Decision | Why |
| --- | --- |
| **The HAL directly** — `AudioDeviceCreateIOProcID` on an `AudioObjectID` — not AUHAL, not `AVAudioEngine`, not Audio Queues. | The HAL is the one API that gives one callback with both directions, the lowest buffer sizes the device allows, and no mixer in the way. AUHAL is a wrapper over it that adds a render-callback indirection and nothing this program needs; `AVAudioEngine` is Objective-C and a graph; Audio Queues are for playback. Every low-latency Mac DAW talks to the HAL. |
| **An aggregate device for input and output on different devices**, created privately at `open`, with drift compensation on the input; **never a ring of our own**. | Apple Silicon Macs expose the built-in microphone and the built-in speakers as *separate* devices, so "different devices" is the normal case for anything but a USB interface used both ways. An aggregate makes the HAL do what WASAPI's two-clock ring does in `WasapiStream` — resample one crystal to the other — in the server, with one callback, and the single-device code path serves both cases. The ring would be 300 lines copied from WASAPI and measurably worse (7.7 xruns/s at 144 frames on two drifting devices, CLAUDE.md). The cost is ~60 lines of CoreFoundation dictionary building, for which `jna-platform` already has the bindings. |
| **`BackendId.CORE_AUDIO` ranks 0**, like ASIO. | It is the Mac's only native rung and the two never meet in one picker, as JACK shares WASAPI's rank. |
| **`Pairing.ANY_PAIR`**, not `SINGLE_DEVICE`. | Because of the separate built-in devices above. The same device both ways is detected at `open` by UID and runs the plain duplex path with no aggregate. |
| **`convertsInput = false`.** | The HAL converts inside the aggregate; the picker should offer the intersection of the two devices' rates, which `SampleRates.offer` already does for a backend without the converter (JACK's case). |
| **The IOProc's pointer arguments are declared `Long`, never `Pointer`.** | A `Pointer` parameter costs an allocation per callback in JNA's dispatch; five of them, 750 times a second, is the one thing the threading contract forbids. The project already passes buffer addresses as `Long` through `JackRt` and `AlsaRt` for this reason. Views onto the buffers are cached by address, as `JackStream.ViewCache` does. |
| **Realtime priority: nothing.** No `Rt.joinProAudio` equivalent, no `LinuxRt`. | The HAL's IO thread already runs under a time-constraint policy; it is the most privileged thread a user process gets on macOS and there is nothing to raise it to. `schedulingNote` says so. |
| **The bundle is jpackage's `--type app-image`, zipped with `ditto`, named `Omega-Plugin-<version>-macOS.zip`, containing `OmegaPlugin.app`.** No `.dmg`, no `.pkg`. | A zip is download-unpack-run with no installer, which is the product's rule. The zip is thrown away once unpacked so it may carry the version; the `.app` is what the user keeps and does not (CLAUDE.md, "What a user keeps carries no version"). `ditto` because Gradle's `Zip` follows symlinks and loses nothing else, but a bundle's signature is only certainly intact through Apple's own archiver. A `.dmg` would add a mount step the user has to understand and a read-only location the updater cannot write to. |
| **Ad-hoc signature only; the first launch goes through Privacy & Security → Open Anyway.** | Decision 2 above. jpackage ad-hoc signs when no `--mac-sign` is given, and Apple Silicon will not run an unsigned binary at all, so this is not optional and costs nothing. The build verifies the signature is there rather than assuming it. |
| **The updater replaces the bundle by rename, after running the new one with `--version`**, exactly as `AppImageInstaller` does with a file. | The same reasoning as Linux: a checksum proves the bytes, not that they start here. A `.app` is a folder, so the swap is two renames (old aside, new into place) instead of one, and the old folder is removed later by whoever finds it with nobody running from it. |
| **`ditto -x -k` unpacks the download, not `java.util.zip`.** | Java's unzip drops the executable bits on the launcher, `bin/java` and `lib/jspawnhelper`, and does not restore symlinks. `ditto` is at `/usr/bin/ditto` on every Mac, and it is the tool Apple uses for `.app` archives. It is injected into `MacInstaller` so the tests need not run it. |
| **An app running from a translocated path cannot apply, and says exactly why.** | Gatekeeper runs a quarantined app that was never moved from where it was unpacked from a random read-only path under `/AppTranslocation/`. `jpackage.app-path` then points at that copy, not the one the user has. Writing there fails; renaming the real one is impossible because the program does not know where it is. The line tells the user to move the app with the Finder once. |
| **Settings go in `~/Library/Application Support/Omega-Plugin`**, recordings in `~/Music/Omega-Plugin`. | The Mac convention, and `~/Music` exists on every Mac so nothing is invented (the objection to `~/Desktop` on Linux does not apply). `~/.config` would work and would be odd. |
| **Bundle identifier `io.github.sam-gn.omega-plugin`, fixed for ever.** | macOS keys the microphone grant and Gatekeeper's exception to the signature, but the identifier is what the user sees under Privacy & Security and what `Leftovers` reads from an `Info.plist` to prove a folder is ours. |
| **CI runs on `macos-15`.** | Apple Silicon, generally available, and not the newest: `macos-latest` is macOS 26 and moves without notice. Nothing is compiled here so the build machine's OS is not a floor the way Ubuntu's glibc is; the pin is for reproducibility only. |
| **Append-only on `BackendId` and `Platform`**, as with every enum a session or preset stores. | `Config` stores the chosen backend by name; a reorder would change nothing, a rename would reset everyone's choice. |

---

## 3. The map: every file that changes

New files:

| File | Contents |
| --- | --- |
| `src/main/kotlin/org/example/audio/mac/MacPlatform.kt` | `object MacPlatform { val isMac = os.name startsWith "Mac" }`. JNA-free, the twin of `LinuxPlatform`. |
| `src/main/kotlin/org/example/audio/coreaudio/CoreAudio.kt` | The bindings: the `CoreAudio` library interface, the `AudioObjectPropertyAddress` helper, FourCC constants, struct offsets, `osStatusName`. Appendix A is the specification. |
| `src/main/kotlin/org/example/audio/coreaudio/CoreAudioBackend.kt` | `CoreAudioBackend : AudioBackend` and `CoreAudioStream : AudioStream`. §5. |
| `src/main/kotlin/org/example/audio/coreaudio/Aggregate.kt` | Builds the CoreFoundation dictionary and creates/destroys the private aggregate device. §5.6. |
| `src/main/kotlin/org/example/audio/coreaudio/Microphone.kt` | Optional: the permission state through the Objective-C runtime. Appendix D. Loaded with `runCatching`; failure to load costs a line of diagnosis and nothing else. |
| `src/main/kotlin/org/example/update/MacInstaller.kt` | §8. Could live in `Installer.kt` beside the other two; a file of its own keeps that one readable. |
| `src/main/resources/mac/Info.plist` | The jpackage override. Appendix B. |
| `src/test/kotlin/org/example/audio/coreaudio/CoreAudioTest.kt` | Pure-JVM tests: FourCC, struct offsets, the aggregate dictionary's shape, `legal sizes` from a range, latency arithmetic. §5.13. |
| `src/test/kotlin/org/example/update/MacInstallerTest.kt` | §8.7. |

Changed files:

| File | Change |
| --- | --- |
| `audio/AudioBackend.kt` | `BackendId.CORE_AUDIO("CoreAudio", 0)`, appended. Optionally `AudioStream.callbackThreadId` (§5.12). |
| `audio/Backends.kt` | `"org.example.audio.coreaudio.CoreAudioBackend" to "Mac"` in `OPTIONAL`; a `.coreaudio.` case in `Broken.id` **before** the `else`. |
| `update/Release.kt` | `Platform.MAC("macOS", "-macOS.zip")`, appended; `current()` becomes a three-way `when`. |
| `update/Updates.kt` | `installer()` gets a `Platform.MAC` branch; `howToInstall(MAC)`. |
| `update/Leftovers.kt` | A macOS branch: old and staged bundles. §8.5. |
| `update/Updater.kt` | Nothing: the `setExecutable` line is Linux-only already and a zip needs no bit. |
| `preset/PresetStore.kt` | `defaultFolder()` macOS branch. §10. |
| `ui/MainWindow.kt` | `defaultRecordingFolder()` macOS branch; `Desktop` quit and About handlers. §9. |
| `ui/Knob.kt`, `ui/LoopBoard.kt` | Wheel: accumulate `preciseWheelRotation`. §9.5. |
| `Main.kt` | `--list-backends` prints the macOS `Realtime:` and `Microphone:` lines. |
| `build.gradle.kts` | `macIcon`, `macResources`, `macInput`, `macImage`, `macZip`; `gcArgs` gains two `apple.awt` properties. §7. |
| `.github/workflows/build.yml`, `release.yml` | §11. |
| `src/test/resources/update/releases.json` | A `-macOS.zip` asset on the releases that have the others. |
| `src/test/kotlin/org/example/audio/BackendSeamTest.kt` | The `only this platform's backends are offered` test learns the third platform. |
| `src/test/kotlin/org/example/update/ShippedNamesTest.kt`, `ReleasePickerTest.kt`, `LeftoversTest.kt` | §8.7, §11. |
| `README.md`, `manual.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, `docs/known-issues.md`, `ui/Licences.kt` | §12. |

---

## 4. M1 — the OS check and the registry

The smallest item and the one everything else hangs on.

- **`MacPlatform`** in `audio/mac/`, by the same rule as `LinuxPlatform`: one `val`, computed from
  `System.getProperty("os.name", "").startsWith("Mac")`, in a file that imports nothing from JNA.
  Java reports `Mac OS X` on every macOS to date, including 15 and 26. Do not test
  `os.name.contains("mac")` anywhere else; call this.
- **`Backends.OPTIONAL`** gains `"org.example.audio.coreaudio.CoreAudioBackend" to "Mac"`. The
  filter is `os.startsWith(platform)`, so `"Mac"` matches `Mac OS X`. The backend's own
  `availability` still checks `MacPlatform.isMac` first, as JACK checks Linux, so the filter is
  about what to *show*.
- **`Backends.Broken.id`** currently ends `else -> BackendId.ALSA`. Add
  `className.contains(".coreaudio.") -> BackendId.CORE_AUDIO` above the `else`, or a Mac whose JNA
  failed to load would show a broken "ALSA" entry.
- **`BackendId.CORE_AUDIO("CoreAudio", 0)`**, appended after `JAVA_SOUND`. Rank 0.
- **`Platform.MAC("macOS", "-macOS.zip")`**, appended. `current()`:
  `when { LinuxPlatform.isLinux -> LINUX; MacPlatform.isMac -> MAC; else -> WINDOWS }`.
- **`BackendSeamTest.only this platform's backends are offered`** gets a third branch: on
  `os.startsWith("Mac")`, none of the Windows or Linux ids and `CORE_AUDIO` present. The Windows and
  Linux branches each add `CORE_AUDIO` to the set they must not contain — that is the guard that
  this work changes nothing on the other two platforms.
- **`--selftest --backend=coreaudio`** must resolve: check how `selfTest` matches the `--backend=`
  flag (by `displayName` or enum name, case-insensitively) and make sure `coreaudio` matches
  `CoreAudio`.

---

## 5. M2 — the CoreAudio backend

### 5.1 The shape

`CoreAudioBackend` is `JackBackend` with the server replaced by `coreaudiod`: one process-wide
object, a list of devices, one stream at a time, a callback on a thread that is not ours and is
already realtime, and listeners that set flags for a monitor thread to turn into `StreamEvents`.
Read `JackBackend.kt` end to end before starting; most of its structure — `ViewCache`, the
`CallbackThreadInitializer` once per callback object, the `running` flag checked inside the
callback, the `monitorLoop`, `process` splitting a large period into `blockFrames` pieces, the
`try/catch (Throwable)` that silences and counts — copies over unchanged.

### 5.2 The bindings

All in `CoreAudio.kt`, specified in Appendix A. Two JNA styles, as the project already uses them:

- **Setup and teardown through an interface mapping** (`Native.load("CoreAudio", CoreAudio::class.java)`):
  `AudioObjectGetPropertyDataSize`, `AudioObjectGetPropertyData`, `AudioObjectSetPropertyData`,
  `AudioObjectHasProperty`, `AudioObjectAddPropertyListener`, `AudioObjectRemovePropertyListener`,
  `AudioDeviceCreateIOProcID`, `AudioDeviceDestroyIOProcID`, `AudioDeviceStart`,
  `AudioDeviceStop`, `AudioHardwareCreateAggregateDevice`, `AudioHardwareDestroyAggregateDevice`.
  None of these is called on the IO thread, so the interface mapping's per-call allocation is fine.
- **Nothing native is called from the IO thread at all.** The IOProc hands over the buffer lists;
  reading them is memory access through cached `Pointer`/`FloatBuffer` views. There is no
  `jack_port_get_buffer` equivalent to direct-map.
- **CoreFoundation** for the aggregate dictionary and for `CFStringRef` properties comes from
  `com.sun.jna.platform.mac.CoreFoundation`, already on the classpath through `jna-platform`.

The one class-loading hazard: the listener and IOProc callback *interfaces* must be loaded and
their trampolines created at `open`, on the opening thread, not inside the first callback. JNA does
this when `Native.setCallbackThreadInitializer` and `AudioDeviceCreateIOProcID` are called with the
object, so ordering is natural; the warning is against lazily constructing anything the callback
touches.

### 5.3 Enumeration (`devices()`)

Read `kAudioHardwarePropertyDevices` on the system object (`AudioObjectID` 1): size first, then
the array of `UInt32` ids. For each device:

- `kAudioDevicePropertyDeviceUID` (a `CFStringRef`; copy the string, `CFRelease` it) → `BackendDevice.id`.
  Stable across reboots and reconnects, which an `AudioObjectID` is not.
- `kAudioObjectPropertyName` → `BackendDevice.name`.
- `kAudioDevicePropertyStreamConfiguration` in the **input** scope, then the **output** scope: each
  returns an `AudioBufferList` whose buffers are the device's streams in that direction and whose
  `mNumberChannels` sum to the channel count. Zero in a direction means the device does not do it.
- Skip a device whose UID begins with the prefix this backend gives its own aggregates (§5.6), so a
  stream that is open does not list its own aggregate as a second device. A user's aggregate made in
  Audio MIDI Setup is a legitimate duplex device and is listed.

Order: the system default output (`kAudioHardwarePropertyDefaultOutputDevice`) first, then the
default input, then the rest in HAL order. `Backends.preferred()` picks a backend that can see a
device; the UI then takes the first device it is offered, and on a Mac that should be the thing
making sound rather than whatever the HAL enumerated first.

**Enumeration must never start a device.** Reading properties does not touch the microphone
permission; creating and starting an IOProc with input does. `--list-backends` runs on CI, where a
permission prompt has nobody to answer it and the job hangs. The rule has the same force as "never
throw from `devices()`".

Cache the device list and each device's capabilities until `rescan()`, as WASAPI does; both are
cheap here, but the rule that `rescan` exists for stale answers still applies. Optionally, a
listener on `kAudioHardwarePropertyDevices` on the system object can set a flag the UI's Rescan
reads; not required for parity with the other backends, which also need a Rescan press.

### 5.4 Capabilities (`capabilities(device, sampleRate)`)

- **Rates:** `kAudioDevicePropertyAvailableNominalSampleRates` returns an array of
  `AudioValueRange` (two `Float64`). A range with `min == max` is one rate; a genuine range is
  offered as the project's candidate rates inside it (44 100, 48 000, 88 200, 96 000 — the WASAPI
  list). `preferredSampleRate` is `kAudioDevicePropertyNominalSampleRate`, the rate the device is
  set to now, which is what the picker opens on.
- **Buffer sizes:** `kAudioDevicePropertyBufferFrameSizeRange` (one `AudioValueRange`), then the
  powers of two inside it plus the current `kAudioDevicePropertyBufferFrameSize` if it is not one.
  USB class devices typically report 14–4096; the built-ins similar. CoreAudio honours any size in
  the range, but the picker offers powers of two because that is what every other backend offers and
  what the manual talks about. **Per process:** the buffer size is this client's, not the device's,
  so another program's setting is never read back here and ours never changes theirs.
- **`preferredBufferFrames`:** 128 if the range allows it. CLAUDE.md's figures put the knee at
  32–64 and recommend 128 with a NAM capture; no Mac figure exists yet, so start where ASIO starts
  and let §13 move it.
- **`pairing = ANY_PAIR`**, `fixedSampleRate = null`, `hasControlPanel = false`,
  `unusableReason = null` unless the device has no streams in the direction asked about.

`sampleRate` is accepted and ignored: unlike WASAPI, a frame count here is a frame count at any
rate.

### 5.5 Opening a single device (`open`, same UID in and out)

1. Resolve the UID back to a live `AudioObjectID` (enumerate again; the id may have changed since
   the list was shown). Not found → `AudioBackendException("<name> is no longer connected")`.
2. **Rate.** If `kAudioDevicePropertyNominalSampleRate` differs from `request.sampleRate`, set it
   and wait for it to read back equal — poll at 10 ms up to 2 s; the change is asynchronous and the
   HAL may deliver a few callbacks at the old rate if the stream starts first. If it never reads
   back, throw with the rate the device stayed at. This is a *device* setting shared with every
   other program on the Mac, like JACK's buffer size; unlike JACK the original is **not** restored on
   close, because DAWs on macOS do not, and a user who set 48 kHz in Omega expects to find the
   device at 48 kHz.
3. **Buffer.** Set `kAudioDevicePropertyBufferFrameSize` to `request.bufferFrames`; read it back;
   the granted value is the one that goes in `StreamFormat`. Above `MAX_FRAMES` (8192, as JACK) →
   throw.
4. **Formats.** For every stream (`kAudioDevicePropertyStreams`, per scope), read
   `kAudioStreamPropertyVirtualFormat`. The HAL presents streams to clients as 32-bit float
   native-endian linear PCM by default, interleaved per stream; assert `mFormatID == 'lpcm'`,
   `kAudioFormatFlagIsFloat` set, `mBitsPerChannel == 32`. Anything else → throw naming the stream.
   Do not convert; a device that has been put into some other virtual format by another program is
   rare enough that a sentence beats a codec. Record each stream's channel count: the user's
   `inputChannel` and `outputChannels` are indices across *all* streams in that direction, so
   stream 0 with 2 channels and stream 1 with 8 means channel 3 is stream 1, channel 1.
5. **Listeners** (§5.8), then **`AudioDeviceCreateIOProcID`** with the callback object. The
   `CallbackThreadInitializer(daemon = true, detach = false, "omega-coreaudio-render")` goes on the
   IOProc callback before this call. The listener callbacks get their own initializer named
   `omega-coreaudio-notify`.
6. `start()` is `AudioDeviceStart`; `stop()` is `AudioDeviceStop`; `close()` is stop, destroy the
   IOProc id, remove the listeners, destroy the aggregate if there is one, **in a `finally`**, and
   null the callback references only after `AudioDeviceDestroyIOProcID` has returned — the HAL
   holds the trampoline address until then.

### 5.6 Opening a pair (different UIDs in and out): the aggregate

Build a `CFMutableDictionary` and call `AudioHardwareCreateAggregateDevice`; the result is an
`AudioObjectID` that the single-device path above then opens as if the user had picked it.

The dictionary, with the string keys the headers define (verify each against `AudioHardware.h` on
the Mac; the names are the constants, the strings are the values):

| Key constant | String | Value |
| --- | --- | --- |
| `kAudioAggregateDeviceUIDKey` | `uid` | `"omega-aggregate-<pid>-<n>"` — the prefix §5.3 skips |
| `kAudioAggregateDeviceNameKey` | `name` | `"Omega-Plugin"` |
| `kAudioAggregateDeviceIsPrivateKey` | `private` | `CFNumber 1` — visible to this process only, so Audio MIDI Setup and other programs never see it |
| `kAudioAggregateDeviceMainSubDeviceKey` (older headers: `…MasterSubDeviceKey`) | `master` | the **output** device's UID: the engine runs at the output's rate, and the aggregate's clock is the main sub-device's |
| `kAudioAggregateDeviceSubDeviceListKey` | `subdevices` | a `CFArray` of two dictionaries |

Each sub-device dictionary: `kAudioSubDeviceUIDKey` (`uid`) → the device's UID, and
`kAudioSubDeviceDriftCompensationKey` (`drift`) → `CFNumber 1` on the **input** (the non-main
device), 0 on the output. Drift compensation is the HAL resampling the input's clock onto the
output's, which is exactly what `WasapiStream` does by hand; here it costs a few frames of latency
inside `coreaudiod` and no code.

`CFDictionaryCreateMutable` **must** be given `kCFTypeDictionaryKeyCallBacks` and
`kCFTypeDictionaryValueCallBacks`, and `CFArrayCreate` `kCFTypeArrayCallBacks`; with null callbacks
a dictionary compares keys by pointer and the HAL's lookup of `"uid"` finds nothing. The three are
exported globals: `NativeLibrary.getInstance("CoreFoundation").getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")`.
Every `CFTypeRef` created here is `CFRelease`d once the aggregate exists; the HAL has copied it.

After creation the aggregate **may not be ready at once**: poll `kAudioDevicePropertyStreamConfiguration`
in both scopes until both report channels, 10 ms steps, 2 s limit, then proceed as §5.5. Destroy it
in `close()`'s `finally` with `AudioHardwareDestroyAggregateDevice`; a private aggregate also dies
with the process, which is the safety net if the program is killed.

Rates for a pair: `SampleRates.offer` intersects the two devices' lists (`convertsInput = false`),
so the request arrives at a rate both can do; set it on the aggregate, which sets both. Buffer range
for a pair: the UI sizes from the capabilities it asked for; the aggregate's own range is read at
open and the granted size reported, as always.

### 5.7 The IOProc: the realtime path

The callback interface, with **every pointer as `Long`**:

```kotlin
fun interface IOProc : Callback {
    fun callback(
        device: Int,          // AudioObjectID
        now: Long,            // const AudioTimeStamp*
        inputData: Long,      // const AudioBufferList*
        inputTime: Long,      // const AudioTimeStamp*
        outputData: Long,     // AudioBufferList*
        outputTime: Long,     // const AudioTimeStamp*
        client: Long,         // void*
    ): Int                    // OSStatus; always 0
}
```

Seven parameters. A binding that declares six reads the input buffer from the wrong slot and the
mistake presents as noise or a crash inside native code; it has happened to others.

Inside, in this order, allocating nothing after the first few callbacks:

1. `t0 = System.nanoTime()`.
2. Resolve `outputData` through a `ViewCache` keyed by address to a `Pointer`; read
   `mNumberBuffers` at offset 0; for each buffer `i`: `mNumberChannels` at `8 + 16 i`,
   `mDataByteSize` at `12 + 16 i`, `mData` at `16 + 16 i`. `frames = mDataByteSize / (mNumberChannels * 4)`
   of the first output buffer — **the frame count comes from the buffer, never from the size that
   was requested.** Resolve each `mData` to a `FloatBuffer` view, cached by address with its size;
   a buffer address never seen costs one view, and the HAL rotates a small fixed set.
3. The same for `inputData`. A device with no input delivers a list with `mNumberBuffers == 0`;
   a disabled stream delivers a buffer whose `mData` is null and whose `mDataByteSize` still says
   how big it would have been. Treat both as silence.
4. If `!running`: zero every output buffer, return 0.
5. For `offset` in steps of `blockFrames`: de-interleave the chosen input channel from its stream's
   view into `inputBlock` (`view.get(frame * channels + channel)`), `callback.render(inputBlock,
   outputBlock, n)`, write `outputBlock` into every chosen output channel of its stream, **zeros
   into every other output channel** — the HAL does not clear output buffers for the client, and a
   channel left alone replays whatever was last there.
6. `catch (Throwable)`: silence every output buffer, `xruns++`, carry on.
7. Settle counting, `framesProcessed`, `realtimeRatio`, `deadlineMisses`, `worstCallbackMicros`,
   exactly as `JackStream.process`. `periodNanos = blockFrames * 1e9 / rate`.

The HAL calls with the frame count this client asked for, so step 5 normally runs once; the loop
is there for the same reason JACK's is — a device that delivers more than `prepare` sized for must
not hand an effect more frames than it owns.

### 5.8 Listeners and the monitor thread

Three `AudioObjectAddPropertyListener`s on the device (the aggregate, for a pair), each a tiny
callback that stores to a volatile and returns; the monitor thread, at normal priority every
250 ms, turns them into `StreamEvents`, as `JackStream.monitorLoop` does:

| Selector | Flag | Monitor action |
| --- | --- | --- |
| `kAudioDeviceProcessorOverload` | `overloads++` | Add to `xruns`. This is the HAL saying the IO cycle ran late; it is the Mac's `xrun`. |
| `kAudioDevicePropertyDeviceIsAlive` | `deviceGone = true` | `running = false`, `events.onDeviceLost("<name> was disconnected")`. |
| `kAudioDevicePropertyNominalSampleRate` | `rateChanged = true` | Re-read the rate; if it differs from `format.sampleRate`, `format = format.copy(sampleRate = …)`, `events.onFormatChanged(format)`. Another program (Audio MIDI Setup, a DAW) changed it under us; the engine re-prepares, as it does for an ASIO panel change. |

**Notifications need a run loop, and a Java process has none it can rely on.** Since 10.6 the HAL
delivers property notifications on the process's main run loop; under `--selftest` nothing runs
one, and in the GUI it is AppKit's thread, not ours. In the backend's constructor set
`kAudioHardwarePropertyRunLoop` on the system object to a null `CFRunLoopRef` (an 8-byte zero),
which tells the HAL to run its own notification thread — the call `cubeb` and PortAudio make.
`runCatching` it; and **the monitor thread also polls `kAudioDevicePropertyDeviceIsAlive` on each
tick**, so a notification that never arrives costs a quarter of a second, not the feature.

The HAL may also stop calling the IOProc without saying so (a sleeping Mac, a USB hub resetting):
the monitor compares `framesProcessed` across ticks and, after two seconds of no progress while
`running`, raises `onDeviceLost("<name> stopped delivering audio")`. The same check belongs in the
other callback backends and is not there; a Mac is where sleep happens most.

### 5.9 Latency

Per direction, in the device's scope: `kAudioDevicePropertyLatency` + `kAudioDevicePropertySafetyOffset`
+ `kAudioStreamPropertyLatency` of the first stream + the granted buffer frames. The sum of the two
directions is what Apple's own code and every DAW report as round trip. `adapterLatencyFrames = 0`
for one device; for an aggregate the drift compensation adds a few frames that nothing reports, and
the document says so where the figure is shown rather than guessing one. `--measure` is still the
only truth, as the table in CLAUDE.md says of ASIO.

### 5.10 Errors in words

`AudioBackendException` messages a player can act on, built from `osStatusName` (Appendix A) and
the device name: "`<name>` would not accept 48 000 Hz; it stayed at 44 100", "`<name>` is in use
by another program in a way that excludes this one (`!hog`)", "`<name>` was disconnected". A raw
`OSStatus` of `-10851` tells nobody anything; its FourCC `!dat` and the sentence "unsupported
format" do.

### 5.11 The microphone permission

macOS asks the user whether a program may use the microphone the first time it starts input on
*any* capture device — a USB guitar interface is a microphone to TCC. Four facts shape the design:

1. **The prompt is attributed to the responsible app and needs `NSMicrophoneUsageDescription` in
   that app's `Info.plist`.** From the bundle, that is `OmegaPlugin.app` and §7 puts the key there.
   From Terminal (`gradlew run`, `--selftest`), Terminal is the responsible app and is asked instead.
2. **A denied program gets silence, not an error.** The IOProc runs, the input buffers are zeros,
   the meter never moves. Nothing in the HAL tells the client.
3. **The grant is keyed to the code signature.** An ad-hoc signature changes with every build, so
   after every update macOS asks again — one dialog with Allow, at the first Start. The manual says
   so (§12). macOS has also been seen to deny silently with the toggle still on after a signature
   change; the fix is the toggle off and on, and the manual's troubleshooting says that too.
4. **A test on CI must never reach it.** §5.3's rule: enumeration starts nothing.

Two things the backend does about it:

- **Say when it is denied.** Appendix D reads the state through the Objective-C runtime with three
  functions and one class; `Microphone.state()` returns `ALLOWED`, `DENIED`, `NOT_ASKED` or `UNKNOWN`
  (the bindings failed to load). `--list-backends` prints a `Microphone:` line. `open` with input
  on a `DENIED` state throws before touching the device: "macOS is not letting Omega-Plugin use the
  microphone, which is how it reaches your interface. System Settings → Privacy & Security →
  Microphone, switch Omega-Plugin on, then Start again." That sentence is worth more than the whole
  backend to the one user it reaches.
- **Notice silence when the state could not be read.** With `UNKNOWN`, the monitor thread watches
  the input: if after five seconds of running every input block has been exactly zero and the state
  is not `ALLOWED`, `Log.warn` once and raise a `StreamEvents` note the status line shows — the
  existing `onFatal` is too strong; add nothing to the seam and instead have the window's status
  line read `schedulingNote`-style text from a new `AudioStream.healthNote: String?` (default null),
  which this backend sets to the sentence above. A guitarist who has not played a note in five
  seconds sees a line that is true either way.

### 5.12 What `--selftest` learns here

Two lines the Mac is the reason for, both useful on every backend:

- **`Realtime:`** — `--list-backends` on macOS prints
  `Realtime: CoreAudio schedules its own IO thread; nothing to set up.`, from `MacPlatform.isMac`.
- **IO-thread allocation.** The engine's allocation test cannot see a backend's callback, and the
  `Long`-not-`Pointer` rule of §5.7 is exactly the kind of thing that works until a refactor. Add
  `AudioStream.callbackThreadId: Long` (default 0), set by the callback's first run from
  `Thread.currentThread().threadId()`; `--selftest` reads
  `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(id)` at start and end and prints
  `IO thread allocated <n> kB over <m> callbacks`. `jdk.management` is already in
  `portableModules`. Pass: under 64 kB after the first second, the engine test's own budget.

### 5.13 Tests that need no Mac

`CoreAudioTest`, pure JVM, in the project's style — numbers worked out by hand, not read back from
the code:

- `fourcc("dev#")` equals `0x64657623`; `fourcc` of every selector in Appendix A round-trips to its
  four characters.
- Struct offsets: `AudioBuffer` is 16 bytes with `mData` at 8; `AudioBufferList`'s first buffer is
  at 8; `AudioStreamBasicDescription` is 40 bytes with `mChannelsPerFrame` at 28;
  `AudioTimeStamp` is 64 bytes. Asserted against literal integers.
- Legal sizes from a range: `(14.0, 4096.0)` yields `16, 32, …, 4096`; `(512.0, 512.0)` yields
  `512`; the current size is always included; a nonsense range `(0, 0)` yields something usable.
- Channel mapping: streams of `[2, 8]` channels map index 3 to `(stream 1, channel 1)` and index 9
  to `(stream 1, channel 7)`; index 10 is refused.
- The aggregate description: given two UIDs, the builder produces a map (test the Kotlin
  intermediate, not the `CFDictionary`) with `master` = the output UID, two sub-devices, `drift` 1
  on the input only, `private` 1, a UID with the prefix §5.3 skips.
- Latency arithmetic: input `(latency 32, safety 64, stream 0, buffer 128)` and output
  `(…)` sum as `StreamFormat.reportedRoundTripMs` expects, with `adapterLatencyFrames = 0`.
- `osStatusName(-10851)` is `!dat` and names "unsupported format".

`BackendSeamTest`'s existing promises — never empty, never throws, ordered, reasons present — run
on the macOS CI leg against the real registry with no device and must pass there.

---

## 6. M3 — Java Sound on macOS

Nothing to build. The JDK's macOS Java Sound is a CoreAudio client with the same blocking API the
Windows one has, and `JavaSoundBackend` already adapts to it. Verify under §13 that it opens and
plays, and that `--list-backends` ranks it below CoreAudio. Its line in the picker is the proof
that the degradation path works when JNA does not — the test CLAUDE.md insists on, run by starting
the bundle with the JNA jar's natives made unloadable (`-Djna.boot.library.path=/nonexistent
-Djna.nounpack=true` through `JAVA_TOOL_OPTIONS`) and seeing CoreAudio listed as unavailable with
a reason and the app streaming on Java Sound.

---

## 7. M4 — the bundle: `OmegaPlugin.app` in a zip

Structurally `exeImage` with a different launcher, as `appImage` is. Five tasks, all macOS-only in
the way `appImage` is Linux-only (a `doFirst` that errors elsewhere):

### 7.1 `macIcon` — an `.icns` written by hand

The same reasoning as `windowsIcon`: one icon source, nothing committed that is derived. ICNS is a
container of typed elements, and since 10.7 the elements for these sizes may be PNG byte-for-byte:

| Type | Size | Source |
| --- | --- | --- |
| `icp4` | 16 | `omega-16.png` |
| `icp5` | 32 | `omega-32.png` |
| `icp6` | 64 | `omega-64.png` |
| `ic07` | 128 | `omega-128.png` |
| `ic08` | 256 | `omega-256.png` |

File: `icns` + total length (big-endian `UInt32`, including this 8-byte header); then per element:
4-char type, big-endian `UInt32` length **including its own 8-byte header**, PNG bytes. Big-endian
throughout, where the `.ico` was little-endian. 24 and 48 have no PNG-capable ICNS type and are
left out. Without a 512 the Dock icon is a little soft on a Retina screen; add `ic09` when a 512 PNG
exists in `src/main/resources/icons`. Output `build/mac/OmegaPlugin.icns`. Verify on the Mac with
`sips -g all build/mac/OmegaPlugin.icns` or by looking at the bundle in the Finder.

### 7.2 `macResources` — the `Info.plist` override

jpackage's `--resource-dir` takes replacement files by fixed names; for the application's plist
the name is `Info.plist` and the default is `Info-lite.plist.template`, which `--verbose` confirms
with a line of the form `Using default package resource Info-lite.plist.template [Application Info.plist] (add Info.plist to the resource-dir to customize)`.
The override must contain every key the default contains — Appendix B is the default's keys plus
ours. Copy `src/main/resources/mac/Info.plist` into `build/mac/resources/`. The `DEPLOY_*`
placeholders are substituted by jpackage; the file in the repository keeps them.

What the override adds, and why:

- `NSMicrophoneUsageDescription`: "Omega-Plugin needs the microphone permission to hear your
  guitar through your audio interface." JDK 21's default has the key with a generic sentence;
  ours says what the user is actually allowing.
- `LSApplicationCategoryType public.app-category.music`.
- `LSMinimumSystemVersion 11.0`: the first macOS on Apple Silicon. The runtime's own floor is
  Temurin's; CI prints the JDK's `release` file so the notice can state the build (§11).
- `CFBundleDisplayName Omega-Plugin`.

### 7.3 `macInput`, `macImage` — jpackage

`macInput` is `exeInput` plus the three texts: the fat jar renamed `OmegaPlugin.jar`, `LICENSE`,
`README.md`, and `RUNTIME-NOTICE.txt` written like `linuxRuntimeNotice` — jpackage copies `--input`
into `Contents/app/`, so they travel inside the bundle, which is where GPLv3 §4 and the runtime's
GPLv2-with-Classpath-Exception notice want them. Do **not** use `--app-content` for them: files
added at the `Contents` root make the bundle structurally invalid and ad-hoc signing then fails
with the added file named as a subcomponent (JDK-8332110).

`macImage` runs jpackage from the Java 21 toolchain (`bin/jpackage`, no `.exe`), `--type
app-image`, with:

```
--name OmegaPlugin
--app-version <exeVersion>                 # digits only; the suffix is dropped as on Windows
--input build/mac/input
--main-jar OmegaPlugin.jar
--main-class org.example.MainKt
--runtime-image build/portable/runtime     # portableRuntime, jlinked on the Mac runner
--icon build/mac/OmegaPlugin.icns
--resource-dir build/mac/resources
--mac-package-identifier io.github.sam-gn.omega-plugin
--mac-package-name Omega-Plugin            # CFBundleName, the menu bar; 16 characters at most
--description Omega-Plugin
--vendor Sam-GN
--copyright "GPLv3-or-later; see LICENSE"
--dest build/mac/image
--java-options <each of gcArgs>            # one per flag, as exeImage does
--java-options -Dstdout.encoding=UTF-8
--java-options -Dstderr.encoding=UTF-8
```

No `--mac-sign`. Without it jpackage ad-hoc signs the whole bundle, inside out, with the identity
`-`. Verified in the JDK 21 source (tag `jdk-21+35`): `StandardBundlerParam.SIGN_BUNDLE`'s default
is `params -> false`, and `MacAppImageBuilder.doSigning` then takes its `else if
(OperatingSystem.isMacOS())` branch, `signAppBundle(params, root, "-", null, null)`. **Then verify
rather than trust**, in the same task, with `/usr/bin/codesign --verify --deep
--strict build/mac/image/OmegaPlugin.app` and `codesign -dv … 2>&1 | grep -q 'Signature=adhoc'`;
if either fails, run `codesign --force --deep --sign - OmegaPlugin.app` once and verify again. A JDK
that changed its default would otherwise ship a bundle Apple Silicon refuses to start.

Output: `build/mac/image/OmegaPlugin.app`, inside it `Contents/MacOS/OmegaPlugin` (the launcher),
`Contents/app/OmegaPlugin.jar` and `OmegaPlugin.cfg` (the VM options), `Contents/runtime/Contents/Home`
(the jlink image, with `libjli.dylib` *copied* into `Contents/runtime/Contents/MacOS` by jpackage),
`Contents/Info.plist`, `Contents/Resources/OmegaPlugin.icns`.

`gcArgs` gains two properties that every launcher may carry and only macOS reads:
`-Dapple.awt.application.name=Omega-Plugin` and
`-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua` (§9.1). A `-D` is never rejected by
a JVM, so this is safe on the other two platforms, and it keeps `gradlew run` on a Mac looking like
the bundle.

### 7.4 `macZip` — `ditto`

```
/usr/bin/ditto -c -k --keepParent build/mac/image/OmegaPlugin.app build/distributions/Omega-Plugin-<version>-macOS.zip
```

`--keepParent` so the zip's top entry is `OmegaPlugin.app`, not `Contents`. `--sequesterRsrc` is
not needed (no resource forks) and has been reported to confuse as often as help; leave it out.
Like `exeZip` and `appImage`, `build` does not depend on this; it is a release artefact. Verify
the round trip in the same task: `ditto -x -k` the zip into a temp folder and `codesign --verify
--deep --strict` the result.

### 7.5 What a user sees

A zip they double-click (Archive Utility; Safari may have unpacked it already), an app they drag
to Applications, and — the first time only — Gatekeeper's refusal, because the quarantine flag the
browser set travels onto every file in the bundle. The manual's words for that are in §12. Nothing
else: no installer, nothing to install, Java inside as on the other two platforms.

---

## 8. M5 — the installer: a bundle that replaces itself

`MacInstaller(launcher: Path?, unpack: (zip, into) -> Unit = ::ditto, probe: (Path) -> String? = ::tryRunning, launch: (Path) -> Unit = ::open)`,
an `Installer` beside the other two, built in `Updates.installer()` from
`System.getProperty("jpackage.app-path")`. The launcher that sets it is jpackage's shared
`AppLauncher.cpp`, one source for all three platforms, which hands the JVM its own executable path
(`SysInfo::getProcessModulePath()`); `WindowsInstaller` already relies on the property and has been
run against it in 2.0.4. On macOS the value is expected to be `…/OmegaPlugin.app/Contents/MacOS/OmegaPlugin`;
§8.6's `--check-update` line prints it, and T7 is where that expectation is confirmed.

### 8.1 Where the program is

`bundle = launcher.toRealPath().parent.parent.parent`, required to end in `.app` and to contain
`Contents/Info.plist`; otherwise `NOT_FROM_BUNDLE`. `toRealPath` because the user may start it
through a symlink, and it is the bundle on disk that gets replaced, as with the AppImage. The
bundle's *name* is whatever the user has — `OmegaPlugin.app`, `Omega.app`, `Guitar.app` — and the
new one takes that name, exactly as the renamed-image test insists for Linux.

### 8.2 `cannotApply()`

In order, each a constant with a test:

| Condition | Constant | Words |
| --- | --- | --- |
| No `jpackage.app-path` or not a bundle | `Installer.NOT_FROM_BUNDLE` | the existing sentence |
| The path contains `/AppTranslocation/` | `TRANSLOCATED` | "macOS is running this copy from a temporary place it makes for a freshly downloaded program, so it does not know where your copy really is. Move Omega-Plugin to your Applications folder (or any folder) with the Finder, open it from there, and it will be able to update itself." |
| The bundle's parent folder fails the write probe (`WindowsInstaller.isWritable`, reused) | `NOT_WRITABLE` | "Could not write to the folder holding Omega-Plugin, `<folder>`, so this copy cannot replace itself." |
| `/usr/bin/ditto` is not executable | `NO_DITTO` | "This Mac has no `ditto`, which the update needs to unpack the download." (Never seen; the check costs a stat.) |

Translocation is why this entry exists at all: a user who unzips in Downloads and double-clicks,
as every Windows user does, runs from a randomised read-only path until they move the app once. The
sentence is the whole fix. Under `Program Files`' equivalent — `/Applications` owned by an admin
with a standard user running — the write probe fails and the download goes to Downloads with the
Linux-style sentence.

### 8.3 `downloadFolder()` and `stagedName()`

The bundle's parent, so the final renames stay on one volume. `stagedName(asset) = ".$asset.new"`,
a hidden regular file ending in `.new`, which `Leftovers.isPartial` already recognises.

### 8.4 `stage(download, progress)`

Everything that can fail happens here, while the program still runs and the old bundle is untouched:

1. `cannotApply()?.let { throw UpdateException(it, reachedServer = true) }` — asked again, the last
   gate.
2. `progress("Unpacking…")`. `staged = parent.resolve(".$installedName.staged")`; delete it if a
   previous attempt left one (`deleteTree`); `unpack(download, staged)`. The default `unpack` is
   `ProcessBuilder("/usr/bin/ditto", "-x", "-k", zip, into)`, 120 s limit, exit 0 required.
3. Find the bundle inside: `staged/OmegaPlugin.app` — or any one `*.app` directory, as
   `findBundleRoot` allows one level — and require `Contents/MacOS/<something executable>` and
   `Contents/app/OmegaPlugin.jar` and a non-empty `Contents/runtime`, the §"empty folder" lesson
   from Windows restated for a bundle.
4. `progress("Testing the new version…")`. `probe(newBundle)`: run
   `newBundle/Contents/MacOS/<launcher> --version`, 60 s limit, stdout must contain a line starting
   `Updates.VERSION_BANNER`. The environment is this process's, unchanged — there is no AppImage
   runtime to strip and no AppImageLauncher. Failure → `WOULD_NOT_START (<why>)`, `deleteTree(staged)`,
   `deleteIfExists(download)`, throw.
5. **The swap**, two renames in the parent folder:
   `old = parent.resolve(".$installedName.old-$pid")`; `Files.move(bundle, old, ATOMIC_MOVE)`;
   then `Files.move(newBundle, bundle, ATOMIC_MOVE)`. If the second fails, move `old` back and
   throw "Could not put the new version in place: …" — the user still has a program. The running
   JVM has the jar, the runtime's dylibs and the launcher open or mapped from `old`; macOS keeps an
   open file reachable by its inode through a rename and even an unlink, so nothing the old process
   still loads can fail. (Windows cannot do this, which is why `WindowsInstaller` needs a helper.)
6. `deleteIfExists(download)`; `deleteTree(staged)` if anything is left of the wrapper folder.
7. `progress("Ready to restart…")`; return `{ launch(bundle) }`, where the default `launch` is
   `ProcessBuilder("/usr/bin/open", "-n", bundle.toString())`. `open` goes through Launch Services,
   which registers the new bundle under the old path and keeps the Dock tile pointing at the app it
   always pointed at; a direct `exec` of the launcher would also work but would not refresh what
   Launch Services knows. The old bundle is **not** deleted here — see 8.5.

The order mirrors `MainWindow.shutdown`: the window saves the session and config, releases the
devices, runs this `launch`, closes the log, exits. The new process therefore never reads a session
the old one is writing.

### 8.5 `Leftovers` on macOS

Two things an update leaves, both hidden siblings of the bundle, both removed by the **next**
start of the program (`Leftovers.sweep` runs on a low-priority thread after the window is up, never
on `--version`):

- `.<name>.app.old-<pid>` — the previous version. Removed when `ProcessHandle.of(pid).isEmpty`, or
  when untouched for `PARTIAL_AGE_MILLIS`; **and only if** `Contents/Info.plist` inside it names
  `CFBundleIdentifier` `io.github.sam-gn.omega-plugin` (read with a regex, not a plist parser) —
  proof it is ours, as `isOurs` demands a desktop entry and a jar on Linux. The deletion is
  `WindowsInstaller.deleteTree`'s cousin: descend into real folders only, never follow a link.
- `.<name>.staged` — a stage the program died inside. Removed when untouched for `PARTIAL_AGE_MILLIS`
  and holding an `*.app` with our identifier.

**If deletion fails with "Operation not permitted" (EPERM), move the folder to the Trash instead**
with `java.awt.Desktop.getDesktop().moveToTrash(file)` (supported on macOS; check
`Desktop.Action.MOVE_TO_TRASH`). Since macOS 13 the App Management permission governs *modifying
another app's bundle*, and deleting the files inside a bundle that is not the running one is exactly
that. Apple's rule is that an app may update itself and apps of the same team, and ad-hoc signatures
have no team, so whether the new build counts as the same app as the old is a thing to measure
(§13, T9), not assume. Moving a folder to the Trash is a rename, which the permission does not
cover. The log says which happened. Deleting nothing is also acceptable: it is 60 MB until the user
empties the Trash or the next sweep.

The existing `sweepPartials` already removes the hidden `.new` zip and `.omega-download` part in the
bundle's parent and in Downloads.

### 8.6 The words elsewhere

- `Updates.howToInstall(MAC)`: "To update, unpack the zip and move the new OmegaPlugin over your old
  copy, so the Dock and your shortcuts open the new version."
- `Updates.installer()`: `Platform.MAC -> MacInstaller(launcher = jpackage.app-path …)` inside the
  existing `guarded { }`, so a path the locale cannot spell still gives a dialog.
- `UpdatePanel` needs nothing; it speaks through `Platform.displayName` and the installer's reasons.
- `Main.checkUpdate()` prints one more line before the verdict, on every platform:
  `Installed: <jpackage.app-path or APPIMAGE or "(not from a bundle)"> — <cannotApply() or "can replace itself">`.
  It touches no network and changes nothing, so it keeps `--check-update`'s contract; it is the
  cheapest way to see what the installer believes on a machine one cannot attach a debugger to, and
  the smoke test's `--check-update` step shows it on every CI leg.

### 8.7 `MacInstallerTest`

Against temp folders with `unpack`, `probe` and `launch` injected, in `InstallerTest`'s style; the
"bundle" is a folder named `X.app` with `Contents/Info.plist`, `Contents/MacOS/OmegaPlugin`,
`Contents/app/OmegaPlugin.jar` and `Contents/runtime/x` written by the test, and the injected
`unpack` copies a prepared folder rather than running `ditto`.

- `mac - not run from the bundle cannot apply but still has somewhere to download to`
- `mac - a translocated path is refused with the words to act on` (a launcher under
  `…/AppTranslocation/<uuid>/d/OmegaPlugin.app/…`; assert `TRANSLOCATED`)
- `mac - a folder that cannot be written to is refused` (POSIX permissions removed; skip on Windows
  with an assumption, as the Linux tests do where they must)
- `mac - the new bundle is run before the old one is touched` (the probe sees the old bundle intact
  and is given the staged path)
- `mac - a bundle that will not run changes nothing, starts nothing, and leaves no stage`
- `mac - the swap keeps the user's name for the bundle and leaves the old one aside under the pid`
  (bundle named `Guitar.app`; after `stage`, `Guitar.app` holds the new jar, `.Guitar.app.old-<pid>`
  the old one, the zip is gone, the stage folder is gone; `launch` has not run; `finish()` launches
  `Guitar.app`)
- `mac - a download without a bundle inside is refused and cleaned up`
- `mac - a bundle missing its runtime or jar is refused before the swap`
- `mac - if the second rename fails the old bundle is put back` (make the install path
  un-renameable by placing a non-empty directory of the same name mid-swap through the injected
  `probe`'s side effect, or by injecting the move; assert the original bundle is back and the error
  names the step)
- `mac - started through a symlink, the bundle is replaced and the link survives`
- `LeftoversTest`: `an old macOS bundle is removed once nothing runs from it`, `an old bundle
  whose process is alive stays`, `a folder that only resembles an old bundle is never touched`
  (no `Info.plist`, or another identifier), `a stage still being unpacked is left alone`.
- `ReleasePickerTest`: a `-macOS.zip` asset is matched for `Platform.MAC` and not for the others;
  the canned `releases.json` gains the asset on 1.8.1 and 1.8.0 and keeps 1.7.9 Windows-only, so
  "not for macOS yet" has a case.

---

## 9. M6 — the window on a Mac

Five things, all small, all behind `MacPlatform.isMac` or harmless elsewhere. **Verify every one by
screenshot on the Mac** (the project's rule for UI work), and expect to find a sixth.

### 9.1 A dark title bar and the right name

Without `apple.awt.application.appearance`, a Swing frame on macOS gets a light title bar whatever
the window paints, and over `Theme.BACKGROUND` that reads as a fault. The JDK accepts `system` (follow
the Mac) or an `NSAppearance` name; `NSAppearanceNameDarkAqua` keeps the frame dark on a Mac in
light mode too, which is right for a program whose every pixel is dark. It must be set before AWT
initialises, so it is a `-D` in `gcArgs` (§7.3), not a `System.setProperty` in `main` — `Theme.install`
runs after Swing has started. `apple.awt.application.name=Omega-Plugin` names the application menu
under `gradlew run`; the bundle's `CFBundleName` names it from the `.app`.

### 9.2 The Dock icon

`AppIcon.applyToTaskbar` already sets `Taskbar.iconImage` where supported, which on macOS is the Dock
tile. From the bundle the `.icns` does it first; from `gradlew run` this is the only icon. Nothing to
change; check it shows.

### 9.3 Cmd+Q and the application menu

On macOS, Quit from the application menu or Cmd+Q does not pass through `windowClosing`; by default
AWT exits the process, skipping `requestQuit` — the unsaved-lanes question, `saveSession`, the
device release that an exclusive backend needs. In `MainWindow`'s constructor:

```kotlin
if (Desktop.isDesktopSupported()) {
    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
        desktop.setQuitHandler { _, response -> requestQuit(); response.cancelQuit() }
    }
    if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
        desktop.setAboutHandler { showAbout() }   // whatever ☰ → About calls
    }
}
```

`cancelQuit()` always: `requestQuit` either exits the process itself through `shutdown` or the user
declined, and in both cases AWT must not also exit. The handler runs on the event thread, where
`ConfirmDialog.ask` already works. `isSupported` keeps Windows and Linux untouched.

### 9.4 The red button, the green button, full screen

The red close button is `windowClosing` → `requestQuit`, unchanged. The green button zooms (fills
the screen without native full screen) unless the frame opts in; `WindowPlacement` records
`MAXIMIZED_BOTH`, which macOS reports for a zoomed frame. Check that a zoomed window comes back
zoomed and that `WindowPlacement.resolve` is happy with a notch-bearing screen's work area.

### 9.5 The wheel from a trackpad

`Knob` and `LoopBoard` step on `e.wheelRotation`. On macOS a trackpad or a Magic Mouse delivers a
stream of events whose integer rotation is **0** and whose `preciseWheelRotation` is a fraction
(−0.02 and the like), so knobs do not move; a mouse wheel delivers ±1 and works. Both call sites
accumulate `preciseWheelRotation` in a `Double` field and act on each whole unit, keeping the
remainder:

```kotlin
wheelAccumulator += e.preciseWheelRotation
val steps = wheelAccumulator.toInt()        // towards zero
if (steps != 0) { wheelAccumulator -= steps; parameter.position -= steps * WHEEL_STEP; changed() }
```

Reset the accumulator when `WheelGesture` ends the gesture, so a half-notch does not survive to the
next knob. `WheelGesture.take` is unchanged: a fractional event is still a notch for ownership. A
trackpad also fires wheel events with the Shift modifier for horizontal motion; ignore events whose
`isShiftDown` is set when the component only turns vertically. Test on Windows that a plain wheel
still steps exactly once per notch — `preciseWheelRotation` is ±1.0 there.

### 9.6 Things to look at and probably leave alone

- **Fonts.** `Font.SANS_SERIF` maps to Helvetica Neue / SF; sizes 10–13 px may read a touch
  smaller than on Windows. Look; change nothing unless it is unreadable.
- **File dialogs.** AWT `FileDialog` is the native panel; `JFileChooser` is Swing-painted. Both
  work; check which one each LOAD/SAVE uses and that the dark styling applies to the Swing one.
- **The splash.** `Main` takes the Windows path (build the window on the next event). AppKit
  paints promptly; if the logo misses its first paint, route macOS through `Splash.whenPainted` as
  Linux is.
- **`Desktop.open(folder)`** opens the Finder. Works.
- **HiDPI.** Java 2D scales for Retina; the custom-painted controls draw through `Graphics2D` and
  scale with it. `sun.java2d.uiScale.enabled` is ignored on macOS.
- **Keyboard.** Esc, Space and Enter dispatch through `KeyEventDispatcher`s and are not menu
  accelerators; nothing maps Ctrl. Cmd is not used anywhere and need not be.

---

## 10. M7 — where files live

`PresetStore.defaultFolder()`:

```kotlin
if (os.contains("win")) { … }                                   // as now
if (MacPlatform.isMac) return Path.of(home, "Library", "Application Support", FOLDER)   // Omega-Plugin
// XDG, then ~/.config/omega-plugin, as now
```

`MainWindow.defaultRecordingFolder()`: on macOS `~/Music/Omega-Plugin` when `~/Music` is a
directory, else `~/Omega-Plugin`. The manual's table of files (§"Where your files live") gains a
macOS column; so does the README's sentence on where settings live.

The log stays at `logs/omega.txt` under the settings folder, so the ☰ → Logging note already
reads right with `File.separator`.

---

## 11. M8 — CI: a third leg

**`build.yml`**: `os: [ windows-latest, ubuntu-22.04, macos-15 ]`. The version check stays on
Ubuntu alone. After `./gradlew build`, a macOS-only step runs `./gradlew macZip --stacktrace` and
the smoke test of Appendix C, and uploads the zip as an artefact for fourteen days as the AppImage
is. `setup-java` with Temurin 21 provisions the aarch64 JDK; the foojay resolver would too.

**`release.yml`**: a `macos` job on `macos-15`, `needs: version`, mirroring `linux`: checkout at
the sha, Temurin 21, `./gradlew build macZip`, the smoke test, upload `Omega-Plugin-*-macOS.zip`
with `if-no-files-found: error`. `publish` becomes `needs: [ version, windows, linux, macos ]`;
the checksum line adds `Omega-Plugin-*-macOS.zip`; the `files:` list adds it. **A release still
exists only with every platform present**, so a red Mac job publishes nothing, as the header of
that file promises.

**The smoke test** (Appendix C) proves, from the zip and not from `build/`: the bundle unpacks,
is ad-hoc signed and verifies, its `Info.plist` carries our microphone sentence and identifier,
it starts headlessly on a runner with no audio device, runs generational ZGC, lists CoreAudio as
available (which proves JNA's native loaded under the ad-hoc signature), answers `--version`, and
completes a TLS handshake with GitHub (`jdk.crypto.ec`). It never runs `--selftest`: that starts
input, which on a runner raises a microphone prompt nobody can answer, and the runners' audio
devices are missing or slow by GitHub's own issue tracker.

**`ShippedNamesTest`** pins the new names: the zip pattern `Omega-Plugin-${project.version}-macOS.zip`
in the build (versioned, like the Windows zip — thrown away once unpacked), `--name OmegaPlugin`
for the bundle, and both workflows naming `Omega-Plugin-*-macOS.zip`.

**The runtime notice** printed by the Linux build from the JDK's `release` file is printed here too
(`runtimeProvenance()` already reads it), so `RUNTIME-NOTICE.txt` in the bundle names the exact
Temurin build. Temurin is required for the same reason as on Linux: a distribution JDK leaves
`IMPLEMENTOR_VERSION` out and the task stops.

---

## 12. M9 — the documents

Every rule in CLAUDE.md about documents applies: no markdown links, cross-references in backticks,
the manual changes when a control changes, `CONTRIBUTING.md` only for rules that get a patch turned
down.

**README.md**, under *Get it*, a **macOS** paragraph after Linux's, in the same register:

> **macOS (Apple Silicon)** — download `Omega-Plugin-<version>-macOS.zip`, double-click it to unpack
> `OmegaPlugin.app`, and drag the app to your Applications folder — or any folder you keep programs
> in, but move it out of Downloads once, or macOS keeps running it from a temporary place and it
> cannot update itself. The first time you open it, macOS will refuse: this program is not signed
> with an Apple developer certificate, and it says so rather than pretending. Click **Done**, open
> **System Settings → Privacy & Security**, scroll down to the note about OmegaPlugin and click
> **Open Anyway**, then confirm. That is needed once. The first time you press **Start**, macOS asks
> whether Omega-Plugin may use the microphone — that is your audio interface; allow it. An update
> installed from inside the program is not stopped by Gatekeeper again, but macOS asks about the
> microphone again after each one, because the program's signature changes with every build.

Also: the *Updating* paragraph gains "on a Mac the new `OmegaPlugin.app` is tested and takes the old
one's place"; the settings sentence gains `~/Library/Application Support/Omega-Plugin`; "x64 only"
becomes "x64 on Windows and Linux, Apple Silicon on macOS"; the *Network* section is unchanged and
true.

**manual.md**: *What you need* names an Apple Silicon Mac and any class-compliant interface; *Installing
and updating* gets the Gatekeeper and microphone steps above, written for a player; *DRIVER* gains
a CoreAudio row ("the only choice on a Mac; the interface's own driver is CoreAudio"); *Where your
files live* gains the macOS column; *When something is wrong* gains "no sound in on a Mac: Privacy
& Security → Microphone" and "Update says it cannot replace itself: move the app out of Downloads
once". *Running the diagnostics* gains the Mac command:
`/Applications/OmegaPlugin.app/Contents/MacOS/OmegaPlugin --list-backends`.

**CLAUDE.md**: `audio/coreaudio/` and `audio/mac/` in the architecture list; the decisions of §2
that a future change could break, in the "Adding a backend" rules (enumeration never starts a
device; pointers as `Long` in the IOProc; the aggregate for pairs); a "macOS" paragraph under "Check
for updates" (rename swap, translocation, App Management, the microphone re-prompt); the latency
table gains a CoreAudio row once §13 has a number; the testing count. **CONTRIBUTING.md**: only
"the macOS bundle is unsigned by decision; do not add signing steps or an Apple account to the
workflows" and "a backend must never start a device from `devices()` or `capabilities()`".

**CHANGELOG.md**: the version that ships macOS is a minor bump — `2.2.0-beta` — with the measured
figures in the method-stated form the file insists on, and an honest "measured on one Mac" line.
**docs/known-issues.md**: whatever §13 finds and leaves. **`ui/Licences.kt`** lines 123–126 name
where the runtime's notices sit; add the macOS bundle (`Contents/app/RUNTIME-NOTICE.txt`, beside
the runtime's `legal/`); `LicencesTest` pins that text.

---

## 13. The test plan on a real Mac, and what each result means

In this order, on an Apple Silicon Mac with a class-compliant USB guitar interface and headphones.
Each test names the command, the expected result, and what to do with each other result. Write the
figures down as they come; the CHANGELOG, §0 and CLAUDE.md's table are written from this list.

**T1 — the registry loads.** From a checkout: `./gradlew run --args="--list-backends"` (or the fat
jar with `omega.sh`).
- Expected: `CoreAudio  available`, every device with channel counts, rates, a buffer range, a
  `Collector: generational ZGC. Good.` line, the `Realtime:` line, a `Microphone:` line.
- CoreAudio UNAVAILABLE with an `UnsatisfiedLinkError`: JNA's native did not load. Check
  `codesign -dv` on the extracted `libjnidispatch.jnilib` under `~/Library/Caches/JNA/temp` or
  `java.io.tmpdir`; try `-Djna.nounpack=true -Djna.boot.library.path=<a folder with the jnilib>`.
  This blocks everything; fix before going on.
- No devices: `kAudioHardwarePropertyDevices` size or stride wrong; print the raw byte count.
- A device with 0 in / 0 out that Audio MIDI Setup shows with channels: the stream-configuration
  read is in the wrong scope or sums `mNumberBuffers` instead of `mNumberChannels`.

**T2 — one device, the headline number.**
`--selftest --backend=coreaudio --buffer=64 <interface> <interface> 60 --chain --stress --tone`,
headphones in the interface.
- Expected: a clean 1470 Hz tone, `realtimeRatio ≥ 0.999`, `xruns 0`, `deadlineMisses 0`, worst
  callback well under the 1.45 ms period at 44.1 kHz (1.33 at 48), `IO thread allocated < 64 kB`.
- Tone but `xruns > 0` with `realtimeRatio` healthy: `kAudioDeviceProcessorOverload` fired — the
  callback body is late. Look at worst callback; try 128. If 128 is clean, 128 is the Mac's
  recommendation and the CHANGELOG says so.
- `realtimeRatio < 0.995` with `deadlineMisses 0`: the collector or the thread being descheduled.
  Confirm `Collector:` says ZGC; check the IO-thread allocation line — a number in megabytes means
  a `Pointer` crept into the callback signature or a view is being recreated every call.
- Silence, meters dead, everything reporting 1.0000: the dead-stream trap from `known-issues.md` §1.
  The IOProc is not being called: `AudioDeviceStart` returned an error that was swallowed, or the
  IOProc id is wrong. Print the `OSStatus` of every HAL call once, by name.
- A crash in native code at the first callback: the IOProc signature has the wrong number or type
  of parameters (§5.7).
- Garbled tone: channel mapping (§5.5 step 4) or an output buffer written with the wrong stride —
  print the first output stream's `mNumberChannels` and `mDataByteSize` once.
- Then repeat at **32, 128, 256**, and at **48 and 96 kHz** if the interface does them. Write every
  row.

**T3 — a pair: the aggregate.**
`--selftest --backend=coreaudio --buffer=128 <interface> "MacBook" 60 --chain --stress --tone`
(the built-in speakers out, the interface in).
- Expected: opens, tone from the speakers, a handful of xruns at most over a minute, the latency
  figure larger than T2's by the drift compensation.
- `AudioHardwareCreateAggregateDevice` fails (`!obj`, `what`): the dictionary. Check the three
  callbacks constants (§5.6) first; then the string keys against `AudioHardware.h`.
- Creates but never reports streams in both scopes: the sub-device dictionaries' `uid` values are
  not the devices' `kAudioDevicePropertyDeviceUID` strings exactly.
- Opens but xruns continuously: drift compensation is on the wrong sub-device, or the main
  sub-device is the input. Swap and re-measure.
- If the aggregate cannot be made to work in a day: ship with pairs refused by a sentence ("On a
  Mac, choose the same device for input and output; a different output needs an aggregate device
  made in Audio MIDI Setup") and a `known-issues.md` entry. The single-device path is the product;
  the pair is a convenience.

**T4 — the microphone permission.** From the **bundle** (T7 first), with the permission reset:
`tccutil reset Microphone io.github.sam-gn.omega-plugin`.
- Expected: pressing Start shows macOS's dialog with our sentence; Allow → sound; Deny → within
  five seconds the status line says what to do, and `--list-backends` says `Microphone: denied`.
- No dialog and silence: the `Info.plist` key did not make it (`PlistBuddy -c Print:NSMicrophoneUsageDescription`),
  or the launch was through Terminal and Terminal is the responsible app.
- The dialog, Allow, still silence: the known TCC stale-grant state — toggle in Settings; write the
  troubleshooting line.

**T5 — a rate change under a running stream.** Stream from the window; in Audio MIDI Setup set the
interface to another rate.
- Expected: the status line reports the new rate, the stream keeps playing, no exception. If the
  rate silently stays or the stream dies, the listener never fired: check that the run-loop
  property was set to null (§5.8), and that the monitor also polls.

**T6 — unplug the interface while streaming.**
- Expected: `onDeviceLost` within half a second, the window stops cleanly, Rescan lists what is
  left, Start on the built-ins works. A hang here is `AudioDeviceStop` on a dead device — call it
  with a flag set that makes `close` skip the HAL when the device is gone, as JACK's `serverGone`.

**T7 — the bundle and Gatekeeper.** Download the CI zip in Safari, unpack, double-click in Downloads.
- Expected: Gatekeeper refuses with "not opened"; Privacy & Security shows Open Anyway; after it the
  app runs; About → Check for updates says it cannot apply because it is translocated
  (`ps -o comm= -p <pid>` shows `/private/var/folders/…/AppTranslocation/…`). Move the app to
  Applications with the Finder, open again: About now says it can update.
- The app does not open at all after Open Anyway, "damaged" or killed: the ad-hoc signature is
  missing or broken — `codesign --verify --deep --strict` the unpacked bundle. The CI step should
  have caught it; if the zip from CI verifies and the Safari-unpacked one does not, Safari's
  unpacking is the difference and the manual says to use Archive Utility (double-click the zip in
  the Finder) — or `--sequesterRsrc` was needed after all.

**T8 — the update, end to end, against a release that does not exist.** Make a canned source folder
(`FileSource`'s layout, as the Windows path was driven in 2.0.x) holding a `releases.json` naming a
higher version with a `-macOS.zip` asset and `SHA256SUMS.txt`, pointing at a second CI zip. Launch
the installed bundle from Terminal with the property through the environment —
`JAVA_TOOL_OPTIONS="-Domega.update.source=/path" /Applications/OmegaPlugin.app/Contents/MacOS/OmegaPlugin`
— the launcher's `.cfg` cannot be edited without breaking the signature.
- Expected: Check finds it, Update downloads, "Testing the new version…", "Ready to restart…", the
  program exits, the new one opens **without a Gatekeeper prompt** (the download was never
  quarantined), About shows the new version, `ls -la /Applications` shows `.OmegaPlugin.app.old-<pid>`,
  and the next start removes it (or trashes it — the log says which).
- Gatekeeper *does* prompt for the new bundle: `xattr -l` the new bundle; if `com.apple.quarantine`
  is on it, `ditto` propagated it from the zip — the zip was downloaded by something that set it. From
  `HttpsURLConnection` it should not be; if it is, `xattr -dr com.apple.quarantine` on the staged
  bundle before the swap is the fix (a `ProcessBuilder` call on a folder the program made itself).
- The rename fails with "Operation not permitted": App Management (§8.5, risk R3). Record the macOS
  version; grant Omega-Plugin App Management in Settings and repeat to confirm the cause; then
  implement the message of R3.

**T9 — App Management on the old bundle.** After T8, on macOS 13 or later, start the new version
and watch the log for the sweep.
- Deleted: fine. "Operation not permitted" → trashed: fine, note it in the manual. Neither: the
  sweep's identifier check failed; read what it read.

**T10 — the window.** Screenshots of: the title bar (dark), the application menu's name, the Dock
icon, the splash and veil, a knob turned by a trackpad two-finger scroll, a LOAD dialog, the loop
box popped out, the window at a Retina scale, the window restored after zoom. Fix what is wrong;
record what was fixed.

**T11 — quitting.** Cmd+Q with unsaved lanes → the dialog, Cancel keeps running; Cmd+Q while a
take records → the file is finished; the red button → the same path; the session comes back.

**T12 — the fallback.** `JAVA_TOOL_OPTIONS="-Djna.nounpack=true -Djna.boot.library.path=/nonexistent"`,
open the bundle: CoreAudio listed as unavailable with a reason, Java Sound streams. This is the
degradation path exercised, which CLAUDE.md says is the only kind that works.

**T13 — `--measure`**, if a lead from the interface's output to its input is to hand: the true
round trip at 64 and 128, beside the driver's claim. The one number that is not somebody's claim.

---

## 14. Order of work: what needs no Mac at all

Nearly everything can be written, compiled and unit-tested on Windows, and the macOS CI leg will
build and smoke-test the bundle without anyone owning a Mac. Only §13 needs hardware.

**Phase A — on Windows, every test green on all three CI legs:**

1. M1 (§4) and the `BackendSeamTest` branch. Push: the Mac leg compiles and runs the suite.
2. M8 (§11) with a stub: the workflows' Mac leg running `build` only, so every later push is
   checked on all three.
3. M5 (§8) and its tests. Pure file manipulation; the tests run everywhere.
4. M7 (§10), M6's code (§9: the quit handler, the wheel accumulator — test the wheel on Windows).
5. M4 (§7): the Gradle tasks. `macIcon` can be run and its output inspected on Windows (it is
   bytes); `macImage` and `macZip` run only on the Mac leg, where the smoke test judges them.
6. M2 (§5): the backend, compiled against the bindings, with `CoreAudioTest` for everything that is
   arithmetic. It cannot be run here, but every line that is not a HAL call can be tested, and the
   structure is `JackBackend`'s, which has been run.
7. M9 (§12) drafts, marked as unverified where they describe behaviour nobody has seen.

**Phase B — the CI bundle on someone's Mac:** T7, T8, T9, T10, T11, T12, T4. No interface needed;
the built-in microphone stands in for one. Half a day.

**Phase C — hardware:** T1, T2, T3, T5, T6, T13. A day with the interface, more if T3 fights.

Then §0, the CHANGELOG, CLAUDE.md's table, `known-issues.md`, and the version bump to 2.2.0-beta
in the push that turns the Mac leg from "builds" into "ships".

---

## 15. Walk-throughs: the design against its own failure modes

Each scenario, what the design does, and where that is enforced. Written to find holes before the
code does.

- **A user unzips in Downloads, opens, updates.** Translocated → `TRANSLOCATED` sentence in About,
  the download goes to Downloads with `howToInstall`. They move the app, reopen: translocation is
  gone (the Finder move clears it), About says it can update. *Enforced:* `cannotApply`, test.
- **Two copies running from two folders, both updating.** Each has its own parent folder, its own
  hidden `.new`, its own `.old-<pid>`. `Leftovers` in one never touches the other's `.old` while
  that pid lives. *Enforced:* pid in the name, `ProcessHandle.of`.
- **The program is killed between the two renames.** The user has `.Guitar.app.old-<pid>` and no
  `Guitar.app`. This window is two syscalls wide; if it happens, the next sweep cannot run (there is
  no program). Mitigation: the staged bundle is tested *before* the first rename, so the only
  failure left is a crash of the machine, and the manual's "unzip it again" covers it. Accepted.
- **The second rename fails.** The old is moved back; the stage is deleted; the error names the
  step. *Enforced:* test. The one way the second rename fails after the first succeeded is a race
  with something creating the name — or App Management (R3).
- **The new bundle will not start on this Mac** (a future runtime needing a newer macOS). The
  probe fails on `--version`; nothing is touched; the download is deleted. *Enforced:* probe
  before swap, test. `--version` must stay a flag every release answers — the same rule as Linux,
  pinned by the smoke test.
- **The user renamed the app, put it in the Dock, made an alias.** The swap keeps the name, so the
  Dock tile and the alias point at the same path, which holds a whole program at every instant
  except between the two renames. *Enforced:* test with `Guitar.app`.
- **Another program changes the interface's rate mid-stream.** The listener (or the poll) reports
  it; the engine re-prepares at the new rate as for ASIO; the tuner survives (2.1.4). *Enforced:*
  T5.
- **The interface is unplugged.** `DeviceIsAlive` → `onDeviceLost`; `close` skips HAL calls on a
  dead device. *Enforced:* T6, `deviceGone` flag.
- **The Mac sleeps with the stream running.** The IOProc stops; the monitor sees no progress for
  two seconds and raises `onDeviceLost("stopped delivering audio")`; the user presses Start. Better
  than a stream that reports 1.0000 for ever. *Enforced:* §5.8's progress check.
- **A stream opens while the device is in another program's exclusive hold** (hog mode): the HAL
  answers `!hog`; the sentence names it. *Enforced:* `osStatusName`.
- **TCC denied.** `Microphone.state()` says so before the device is touched; or, bindings failed,
  five seconds of silence produce the line. *Enforced:* §5.11; T4.
- **The CI runner has no audio device.** `devices()` returns empty or the Null Audio Device;
  nothing starts; `--list-backends` prints `(no devices)` and the leg passes. *Enforced:* the
  enumeration rule; the smoke test never runs `--selftest`.
- **The IOProc thread is replaced by the HAL.** A new thread attaches once more (hundreds of
  microseconds, once); JNA's `CallbackThreadInitializer` handles a thread it has not seen. Accepted;
  the same holds for ASIO.
- **A device with two input streams.** Channel index resolves across streams. *Enforced:*
  `CoreAudioTest` mapping test.
- **The HAL delivers more frames than requested.** The loop processes in `blockFrames` pieces; the
  view cache re-creates a view whose size grew. *Enforced:* §5.7 step 5; the view cache stores size.
- **A JDK whose jpackage stops ad-hoc signing.** The `macImage` task verifies and signs itself.
  *Enforced:* `codesign --verify` in the task and in the smoke test.
- **The user's Mac is in light mode.** The frame stays dark (`NSAppearanceNameDarkAqua`); the
  native file panel is light, which is right. *Enforced:* `gcArgs`.
- **A knob under a trackpad.** Fractional rotations accumulate; a wheel's ±1.0 still steps once.
  *Enforced:* §9.5, tested on Windows for the wheel.
- **Cmd+Q with a take recording.** `requestQuit` → `shutdown` → `finishRecording`, the same path as
  the ✕. *Enforced:* the quit handler, T11.
- **The settings folder from a hand-run fat jar before the bundle existed** (`~/.config/omega-plugin`):
  a Mac user who ran the jar during development would lose settings when the bundle looks in
  `Application Support`. Nobody has; no migration. Noted here so nobody wonders.

---

## 16. Risks, with the fallback for each

| # | Risk | Likelihood | Fallback |
| --- | --- | --- | --- |
| R1 | JNA's `libjnidispatch` for darwin-aarch64 fails to load under an ad-hoc signed bundle. | Low — JNA ships it signed, and without hardened runtime there is no library validation. | `jna.boot.library.path` pointing at a copy inside `Contents/app/`, ad-hoc signed by `macImage`. T1. |
| R2 | The non-quarantined replacement bundle is still stopped by Gatekeeper on a current macOS. | Low — Gatekeeper's assessment is triggered by the quarantine attribute; `HttpsURLConnection` and `ditto` set none. | `xattr -dr com.apple.quarantine` on the staged bundle before the swap (§13 T8). If even that fails, the update becomes Download-only on macOS with the words "unpack and move it over your copy", which still works. |
| R3 | **App Management** refuses the rename of the old bundle or the deletion of its contents on macOS 13+. | Medium for deletion, low for rename (renames are directory operations in the parent, which Homebrew does without the permission; deletion inside another app's bundle is what it blocks). | Deletion: trash instead (§8.5). Rename: catch EPERM and say "macOS stopped Omega-Plugin from replacing itself. Allow it under System Settings → Privacy & Security → App Management, or unpack the download by hand", with the verified zip left in Downloads. |
| R4 | The aggregate device cannot be created or will not run cleanly from a JNA client. | Medium — the CF plumbing is fiddly and the dictionary keys must be exact. | Ship single-device only; pairs refused with the Audio MIDI Setup sentence (§13 T3). |
| R5 | A `Long`-typed callback parameter is not read as a pointer on some JNA version. | Low — JNA derives the FFI type from the Java type and on AArch64 a pointer and an `int64` share registers. | Declare `Pointer` for the two buffer-list parameters only, accept two small allocations per callback, measure with §5.12's line, and note it in CLAUDE.md as a known cost. |
| R6 | HAL notifications never arrive in a Java process even with the run loop set to null. | Low–medium. | The monitor thread polls `DeviceIsAlive`, the rate and progress every 250 ms (§5.8); notifications are then a speed-up only. |
| R7 | Temurin 21 aarch64's minimum macOS is above 11.0, so `LSMinimumSystemVersion` lies. | Low; set the key from the JDK's own `Contents/Info.plist` `LSMinimumSystemVersion` at build time if CI shows a mismatch. | Read it in `macResources` and substitute. |
| R8 | GitHub retires `macos-15`. | Certain, eventually. | Move to the next pinned Apple Silicon label; nothing about the bundle depends on the runner's OS. |
| R9 | The microphone grant after every update annoys users. | Certain; one click per update. | Documented. The only cure is a Developer ID, which decision 2 rules out. |
| R10 | `--selftest` on CI would hang on a TCC prompt. | Certain if ever added. | Never run it on CI; the smoke test is enumeration-only, and CLAUDE.md says so. |

---

## Appendix A — the CoreAudio HAL, as JNA sees it

**Library:** `Native.load("CoreAudio", CoreAudio::class.java)` — JNA resolves a bare name to
`/System/Library/Frameworks/CoreAudio.framework/CoreAudio` on macOS. CoreFoundation through
`com.sun.jna.platform.mac.CoreFoundation.INSTANCE`, which has `CFStringCreateWithCharacters`,
`CFStringGetCString`, `CFStringGetLength`, `CFDictionaryCreateMutable`, `CFDictionarySetValue`,
`CFArrayCreate`, `CFNumberCreate`, `CFRelease`, and the ref types `CFStringRef`,
`CFMutableDictionaryRef`, `CFArrayRef`, `CFNumberRef`; check the 5.19 javadoc for
`CFStringRef.createCFString(String)` and `stringValue()` helpers before writing them.

**Types.** `AudioObjectID` is `UInt32` (`Int` in Kotlin); the system object is `1`.
`OSStatus` is `Int32`; `0` is success. `Boolean` is `UInt8`.

**Functions** (all `OSStatus` unless stated):

```
AudioObjectHasProperty(Int id, Pointer address) -> Byte
AudioObjectGetPropertyDataSize(Int id, Pointer address, Int qualifierSize, Pointer qualifier, IntByReference size)
AudioObjectGetPropertyData(Int id, Pointer address, Int qualifierSize, Pointer qualifier, IntByReference ioSize, Pointer data)
AudioObjectSetPropertyData(Int id, Pointer address, Int qualifierSize, Pointer qualifier, Int size, Pointer data)
AudioObjectAddPropertyListener(Int id, Pointer address, PropertyListener proc, Pointer client)
AudioObjectRemovePropertyListener(Int id, Pointer address, PropertyListener proc, Pointer client)
AudioDeviceCreateIOProcID(Int device, IOProc proc, Pointer client, PointerByReference procId)
AudioDeviceDestroyIOProcID(Int device, Pointer procId)
AudioDeviceStart(Int device, Pointer procId)
AudioDeviceStop(Int device, Pointer procId)
AudioHardwareCreateAggregateDevice(Pointer cfDictionary, IntByReference outId)
AudioHardwareDestroyAggregateDevice(Int id)
```

**Callbacks:**

```
IOProc:            (Int device, Long now, Long inputData, Long inputTime, Long outputData, Long outputTime, Long client) -> Int
PropertyListener:  (Int object, Int addressCount, Long addresses, Long client) -> Int
```

**Structs, by byte offset** (all little-endian on Apple Silicon; sizes verified by `CoreAudioTest`):

| Struct | Size | Fields |
| --- | --- | --- |
| `AudioObjectPropertyAddress` | 12 | `mSelector` u32 @0, `mScope` u32 @4, `mElement` u32 @8 |
| `AudioBuffer` | 16 | `mNumberChannels` u32 @0, `mDataByteSize` u32 @4, `mData` ptr @8 |
| `AudioBufferList` | 8 + 16 n | `mNumberBuffers` u32 @0, pad @4, `mBuffers[i]` @ 8 + 16 i |
| `AudioValueRange` | 16 | `mMinimum` f64 @0, `mMaximum` f64 @8 |
| `AudioStreamBasicDescription` | 40 | `mSampleRate` f64 @0, `mFormatID` u32 @8, `mFormatFlags` u32 @12, `mBytesPerPacket` @16, `mFramesPerPacket` @20, `mBytesPerFrame` @24, `mChannelsPerFrame` @28, `mBitsPerChannel` @32, `mReserved` @36 |
| `AudioTimeStamp` | 64 | `mSampleTime` f64 @0, `mHostTime` u64 @8, `mRateScalar` f64 @16, `mWordClockTime` u64 @24, `mSMPTETime` 24 bytes @32, `mFlags` u32 @56, `mReserved` u32 @60 |

Allocate property addresses once per use site as `Memory(12)` and reuse; none is built on the IO
thread.

**FourCC.** `fun fourcc(s: String): Int = (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code`.
Verify every value below against the SDK's `AudioHardware.h` and `AudioHardwareBase.h` on the Mac
(`xcrun --show-sdk-path`, then `System/Library/Frameworks/CoreAudio.framework/Headers/`); the
names are stable and the characters are what they have been since 10.6.

| Constant | FourCC | Of | Type |
| --- | --- | --- | --- |
| `kAudioObjectPropertyScopeGlobal` | `glob` | scope | |
| `kAudioObjectPropertyScopeInput` | `inpt` | scope | |
| `kAudioObjectPropertyScopeOutput` | `outp` | scope | |
| `kAudioObjectPropertyElementMain` | `0` | element | |
| `kAudioHardwarePropertyDevices` | `dev#` | system | `AudioObjectID[]` |
| `kAudioHardwarePropertyDefaultInputDevice` | `dIn ` | system | `AudioObjectID` |
| `kAudioHardwarePropertyDefaultOutputDevice` | `dOut` | system | `AudioObjectID` |
| `kAudioHardwarePropertyRunLoop` | `rnlp` | system | `CFRunLoopRef` (set to null) |
| `kAudioObjectPropertyName` | `lnam` | device | `CFStringRef` |
| `kAudioDevicePropertyDeviceUID` | `uid ` | device | `CFStringRef` |
| `kAudioDevicePropertyTransportType` | `tran` | device | u32; aggregate is `grup` |
| `kAudioDevicePropertyStreams` | `stm#` | device, scoped | `AudioObjectID[]` |
| `kAudioDevicePropertyStreamConfiguration` | `slay` | device, scoped | `AudioBufferList` |
| `kAudioDevicePropertyNominalSampleRate` | `nsrt` | device | f64 |
| `kAudioDevicePropertyAvailableNominalSampleRates` | `nsr#` | device | `AudioValueRange[]` |
| `kAudioDevicePropertyBufferFrameSize` | `fsiz` | device | u32 |
| `kAudioDevicePropertyBufferFrameSizeRange` | `fsz#` | device | `AudioValueRange` |
| `kAudioDevicePropertyLatency` | `ltnc` | device, scoped | u32 |
| `kAudioDevicePropertySafetyOffset` | `saft` | device, scoped | u32 |
| `kAudioStreamPropertyLatency` | `ltnc` | stream | u32 |
| `kAudioStreamPropertyVirtualFormat` | `sfmt` | stream | `AudioStreamBasicDescription` |
| `kAudioDevicePropertyDeviceIsAlive` | `livn` | device | u32 |
| `kAudioDeviceProcessorOverload` | `over` | device | notification only |
| `kAudioFormatLinearPCM` | `lpcm` | format id | |
| `kAudioFormatFlagIsFloat` | `1` | format flag | |
| `kAudioFormatFlagIsPacked` | `8` | format flag | |
| `kAudioFormatFlagIsNonInterleaved` | `0x20` | format flag | HAL streams do not set it |

**`OSStatus` names** for `osStatusName`, each the FourCC read as a signed `Int32`:
`stop` not running, `what` unspecified, `who?` unknown property, `!siz` bad property size, `nope`
illegal operation, `!obj` bad object, `!dev` bad device, `!str` bad stream, `unop` unsupported
operation, `!dat` unsupported format (`-10851`), `!hog` permissions (another program holds the
device exclusively). Anything else prints as its four characters if printable, else the number.

**The aggregate dictionary keys** (string values; constants in `AudioHardware.h`):
`uid`, `name`, `private`, `master` (named `kAudioAggregateDeviceMainSubDeviceKey` in current
headers and `…MasterSubDeviceKey` in older ones — the string did not change), `subdevices`;
sub-device: `uid`, `drift`. CoreFoundation's three exported callback tables:
`kCFTypeDictionaryKeyCallBacks`, `kCFTypeDictionaryValueCallBacks`, `kCFTypeArrayCallBacks`.

---

## Appendix B — the `Info.plist` template

`src/main/resources/mac/Info.plist`, copied to the resource directory by `macResources`. Every key
of JDK 21's `Info-lite.plist.template`, in its order, plus ours; the `DEPLOY_*` tokens are
jpackage's and must stay. Check with `jpackage --verbose` that the override is picked up
(`Using custom package resource [Application Info.plist] (loaded from Info.plist)`).

```xml
<?xml version="1.0" ?>
<!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>LSMinimumSystemVersion</key>
  <string>11.0</string>
  <key>CFBundleDevelopmentRegion</key>
  <string>English</string>
  <key>CFBundleAllowMixedLocalizations</key>
  <true/>
  <key>CFBundleExecutable</key>
  <string>DEPLOY_LAUNCHER_NAME</string>
  <key>CFBundleIconFile</key>
  <string>DEPLOY_ICON_FILE</string>
  <key>CFBundleIdentifier</key>
  <string>DEPLOY_BUNDLE_IDENTIFIER</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleName</key>
  <string>DEPLOY_BUNDLE_NAME</string>
  <key>CFBundleDisplayName</key>
  <string>Omega-Plugin</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>DEPLOY_BUNDLE_SHORT_VERSION</string>
  <key>CFBundleSignature</key>
  <string>????</string>
  <key>LSApplicationCategoryType</key>
  <string>public.app-category.music</string>
  <key>CFBundleVersion</key>
  <string>DEPLOY_BUNDLE_CFBUNDLE_VERSION</string>
  <key>NSHumanReadableCopyright</key>
  <string>DEPLOY_BUNDLE_COPYRIGHT</string>
  <key>NSHighResolutionCapable</key>
  <string>true</string>
  <key>NSMicrophoneUsageDescription</key>
  <string>Omega-Plugin needs the microphone permission to hear your guitar through your audio interface.</string>
DEPLOY_FILE_ASSOCIATIONS
</dict>
</plist>
```

`LSApplicationCategoryType` is fixed rather than `DEPLOY_APP_CATEGORY` because the build passes no
`--mac-app-category`; if a later jpackage insists on the placeholder, put it back and pass
`--mac-app-category music`. The default's microphone sentence names the launcher; ours names the
interface, which is what the user is being asked about.

---

## Appendix C — the smoke test

Run from the zip, in both workflows, on the macOS runner. Reaching GitHub is the pass for the last
line, whatever it answers.

```bash
zip=build/distributions/Omega-Plugin-*-macOS.zip
work="$RUNNER_TEMP/smoke"; rm -rf "$work"; mkdir -p "$work"
/usr/bin/ditto -x -k $zip "$work"
app="$work/OmegaPlugin.app"
test -d "$app" || { echo "::error::the zip does not unpack to OmegaPlugin.app"; exit 1; }

# Ad-hoc signed, whole and valid, or Apple Silicon will not start it.
/usr/bin/codesign --verify --deep --strict "$app"
/usr/bin/codesign -dv "$app" 2>&1 | grep -q 'Signature=adhoc'

# The microphone sentence and the identifier the updater and Leftovers rely on.
/usr/libexec/PlistBuddy -c 'Print :NSMicrophoneUsageDescription' "$app/Contents/Info.plist" | grep -q 'audio interface'
/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Contents/Info.plist" | grep -qx 'io.github.sam-gn.omega-plugin'

# Starts headless, finds its runtime, runs the right collector, and JNA's native loaded under the
# ad-hoc signature (CoreAudio is "available", with or without devices). Never --selftest here: it
# starts input, and a runner cannot answer a microphone prompt.
launcher="$app/Contents/MacOS/OmegaPlugin"
"$launcher" --list-backends | tee report.txt
grep -q "Collector: generational ZGC. Good." report.txt
grep -q "^  CoreAudio .* available" report.txt
"$launcher" --version | grep -q "^Omega-Plugin "
"$launcher" --check-update
```

---

## Appendix D — the microphone permission, read through the Objective-C runtime

Optional, isolated in `Microphone.kt`, loaded inside `runCatching`: a failure to load means
`UNKNOWN` and the silence watch of §5.11 does the job. Three C functions from `libobjc` and one
framework:

```kotlin
private interface ObjC : Library {
    fun objc_getClass(name: String): Pointer?
    fun sel_registerName(name: String): Pointer
    fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Pointer): Long
}
// Native.load("objc", ObjC::class.java); the framework must be loaded so the class exists:
// NativeLibrary.getInstance("/System/Library/Frameworks/AVFoundation.framework/AVFoundation")
// AVMediaTypeAudio is an exported NSString*: that library's getGlobalVariableAddress("AVMediaTypeAudio").getPointer(0)

fun state(): State {
    val cls = objc.objc_getClass("AVCaptureDevice") ?: return UNKNOWN
    val sel = objc.sel_registerName("authorizationStatusForMediaType:")
    return when (objc.objc_msgSend(cls, sel, audioType).toInt()) {
        0 -> NOT_ASKED      // AVAuthorizationStatusNotDetermined
        1, 2 -> DENIED      // Restricted, Denied
        3 -> ALLOWED        // Authorized
        else -> UNKNOWN
    }
}
```

`objc_msgSend` with one pointer argument returning an integer is an ordinary C call on AArch64 and
JNA makes it fine; do not try to call methods taking blocks or structs this way. Never call it on
the audio thread. `--list-backends` prints `Microphone: allowed / denied — System Settings → Privacy
& Security → Microphone / not asked yet / could not be read`.

---

## Appendix E — sources

Looked at on 2026-11-02; each is named in prose so this file holds no links. Facts stated above
that rest on one of these are marked where it matters; everything else is the project's own code.

- jpackage's `MacAppImageBuilder` and `StandardBundlerParam` at the `jdk-21+35` tag: `SIGN_BUNDLE`
  defaults to `false`, and `doSigning` then signs with identity `-`; `Info-lite.plist.template`
  written as `Info.plist`; `libjli.dylib` copied, not linked. The shared `AppLauncher.cpp` at the
  same tag, which passes the launcher's own path to the JVM on every platform. The JDK 21 `Info-lite.plist.template` itself, for the keys of Appendix B.
  JDK-8272639 (the microphone key added to the template) and JDK-8313579 (closed Won't Fix, so the
  key is still in the default). JDK-8332110 (ad-hoc signing fails when `--app-content` adds files
  at the bundle root).
- Oracle's *Override jpackage Resources* page for Java 21: the `--resource-dir` file names, the
  rule that an override must contain every default property, and `--verbose` naming the file.
- Apple's Gatekeeper documentation and the Sequoia release notes as relayed by Michael Tsai's blog
  and the Eclectic Light Company's articles on quarantine and App Translocation: Gatekeeper judges
  files carrying `com.apple.quarantine`; Sequoia removed the Control-click override; a quarantined
  app launched from where it was unpacked is translocated until moved by the Finder.
- The Eclectic Light Company and Lapcat Software on the App Management permission (macOS 13+):
  writes inside another app's bundle fail with EPERM even as root; Homebrew's issues 24367 and pull
  22967 for the same.
- GitHub issues on YARG (#1695) and Parley (#75) and Rust projects' write-ups on TCC: an ad-hoc
  signature's designated requirement is its `cdhash`, so every build is a new app to TCC and the
  microphone grant does not carry over.
- Apple's CoreAudio mailing list (Jeff Moore, 2002 and 2010) and the `AudioHardware.h` comment on
  `kAudioHardwarePropertyRunLoop`: the HAL uses the process's main run loop since 10.6 and a null
  value gives it its own thread; `cubeb`'s `cubeb_osx_run_loop` does exactly this.
- The JUCE forum threads on CoreAudio latency: device latency + safety offset + stream latency +
  buffer per direction, and that only a loopback measurement is the truth.
- `cpal`'s and RtAudio's CoreAudio hosts for the `kAudioDeviceProcessorOverload` listener; the
  PortAudio ticket on registering it twice.
- JDK-8169898 and JDK-8166591: `getWheelRotation` is 0 for a trackpad's partial deltas on macOS;
  `getPreciseWheelRotation` carries the value.
- JDK-8235363, the CSR for `apple.awt.application.appearance`: `system` or an `NSAppearance` name
  such as `NSAppearanceNameDarkAqua`; FlatLaf's macOS page for the timing (before AWT starts).
- The GitHub `actions/runner-images` README: `macos-15` and `macos-26` are Apple Silicon,
  `macos-latest` is 26, `macos-14` deprecated; its issues #13668, #10902 and #9330 on missing or
  slow audio devices and a microphone prompt that hangs a job.
- Wikipedia's *Apple Icon Image format* and the `icns-rs` source for the ICNS element types and
  big-endian layout.
- `ditto`'s manual page and Apple Developer Forums threads on zipping `.app` bundles with
  `--keepParent`.
- JNA's `com.sun.jna.platform.mac.CoreFoundation` javadoc (5.13) for the functions Appendix A
  names.
