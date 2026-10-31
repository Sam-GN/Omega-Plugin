# Changelog

All notable changes to Omega-Plugin are recorded here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[Semantic Versioning](https://semver.org/).

Measured figures are stated with the method that produced them. Where something is an estimate or
unverified, it says so.

---

## [1.7.2-beta] — 2026-10-31

### Added — a user manual, and a link to it from About

`docs/manual.md` explains every control in the program for somebody who has never used it: what to
plug in, the device row and what each picker means, latency in plain words, how knobs, switches,
dropdowns and dragging work, every pedal with its knobs' ranges and defaults, the FX slot types,
split mode, the loop box, the rhythm section and its menu, recording, presets, the ☰ menu, what the
session restores, the status line and the health readout, keys and footswitches, where the files
live, a troubleshooting list, the command-line diagnostics, and a glossary.

README.md links to it near the top. **☰ → About** carries a *User manual* line that opens the same
page in the browser; where no browser can be reached, the address is put on the clipboard and the
line says so. `Licences.MANUAL_URL` is built from `SOURCE_URL`, so the two cannot name different
repositories.

---

## [1.7.1-beta] — 2026-10-31

### Fixed — the Licences screen names the Linux notice too

The screen said the bundled Java runtime was named in `READ-ME-FIRST.txt` beside the Windows
bundle, and nothing else. A Linux user has `RUNTIME-NOTICE.txt` inside the AppImage instead, and
has had it since 1.1.0; the screen now names both, and the runtime's own `legal/` folder next to
each. It also lists the launcher stub `appimagetool` prepends to the AppImage, the AppImage runtime
under the MIT licence. No obligation was unmet — the files were always there — but a compliance
list that leaves out half the bundles reads as if something was forgotten.

---

## [1.7.0-beta] — 2026-10-31

### Added — ALSA converts a mismatched input, as WASAPI does

A pair with no rate in common — the Pocket Master at 44.1 kHz only into a Razer headset at 48 kHz
only, both direct — used to be refused by name on ALSA. The rate picker now offers the output's
rates there too, marks one the input cannot do with **C**, and the input runs at its own rate and is
converted on the way in, through the same fixed-ratio `Resampler` WASAPI uses.

ALSA's loop is read a period, process it, write a period, and a converted period is never a whole
engine period, so a converted stream runs a second loop: the output's clock drives it, the input is
read in smaller periods of its own and converted into a reservoir, and exactly one period comes out
each time. The reservoir keeps its level inside a window by holding back or skipping one frame a
period, which takes up the drift between the two devices' crystals as well. Its depth and the
converter's delay are counted in the reported round trip. A pair that shares a rate runs the same
loop it always has.

Measured with `--selftest --chain --stress`, 60 s:

| Pair | Rate | Round trip | xruns |
| --- | --- | ---: | ---: |
| HDA in, HDMI out, same rate — the unchanged path | 48 kHz, 256 | 16.00 ms | 0 |
| Pocket Master (44.1 only) in, HDMI out | 48 kHz C, 256 | 16.40 ms | 0 |
| Razer microphone (48 only) in, HDMI out | 44.1 kHz C, 256 | 20.43 ms | 0 |

Pocket Master into the Razer itself, output silenced: 0 xruns in 120 s, realtime 1.0000. At 96 kHz
and 512 frames the converted path dropped out in one run of two — as did the unconverted one on the
same card, so that is the card's limit, not the converter's.

A USB output takes several periods in a burst as it starts, and the reservoir can come up a few
frames short in the first third of a second. That is counted as start-up, as the other counters
already treat it, not as a dropout.

`docs/known-issues.md` §7 is closed and removed. Seven new tests, 388.

### Fixed — the opening on Linux

On Linux a white rectangle came up before the logo, and the board jumped about a few times after
the window appeared. Both were X11 asking for a window's paint as a separate event, later than
Windows does. Measured on KDE Plasma:

- The splash was mapped about half a second before it was first painted — the time the main window
  takes to build, in the X server's default white. It now has the background colour from the start,
  and the window is built only once the splash has painted (17-22 ms after it appears).
- The session restore, which reorders pedals and fades slots in, could run before the window's
  first paint and always ran as the veil lifted. Early frames on Linux cost 45-65 ms, so those
  animations outlasted the 280 ms fade. The veil now holds the logo until nothing in the window has
  moved for 150 ms — about 0.8 s after the window opens — and fades a snapshot, one image copy a
  frame. No pedal moves after the fade begins.

Windows takes the same path it did; every change is behind the Linux check.

---

## [1.6.2-beta] — 2026-10-30

### Added — the opening

The logo sits dead centre of the screen from the moment the application reaches its toolkit until
the window has its first frame, then fades out as the window fades in behind it. Before, that half
second was an empty window. The same logo is drawn on the same point by both the splash and the
window's veil, so the eye sees one logo and a window forming around it. Only the GUI shows it; the
diagnostics never do.

---

## [1.6.1-beta] — 2026-10-30

### Fixed — the window opens in its final shape

With the eye on, the first frames after launch showed the one remaining pedal growing out of the
corner of the board with its title clipped, then the loop board fading in and pushing it off the
bottom. Two startup steps were animating from a state nobody had seen: the eye was applied before
the first layout, so that layout eased every pedal out of a point, and loop mode came back after
the window was shown, through the same fade a click uses. Both now land without animation while the
window is still being built, and the whole window comes in under a short fade instead.

---

## [1.6.0-beta] — 2026-10-30

### Added — a 44.1 kHz input works with a 48 kHz output, and the other way round

The rate picker now offers the output's rates. One the input cannot do itself is marked **C**: the
input opens at its own rate and is converted on the way in, through the same fixed-ratio filter the
amp and the loop box already use. The status line names the device, both rates and the cost, which
is half the filter — 0.7 ms at 44.1 kHz — and the figure is counted in the reported round trip. The
output is never converted: the round trip is set by the hardware's periods in time, and the
engine's rate only changes how many frames that is. A rate the input can do natively is still
preferred when both are on offer.

This is the WASAPI path, exclusive and shared; shared mode is where it matters most, since there
each device runs at whatever Windows set it to and a USB microphone at 44.1 kHz with speakers at
48 kHz was simply refused. ASIO is one device at one rate and is untouched. ALSA still offers only
rates both devices share — see `docs/known-issues.md` §7.

Up to 1.5.x the rule was never to resample, on the belief that a converter would cost more than
the backend saved. Measured against this filter it is a fraction of the ring hop that was already
there. Nine new tests; the pair that used to be refused now negotiates at 48 kHz with the input
converted.

---

## [1.5.10-beta] — 2026-10-30

### Changed — the loop box opens paused

Switching loop mode on used to put the transport in play, with the button reading PAUSE, before
there was anything to play. It now opens paused and the button reads PLAY; REC or PLAY starts it.

---

## [1.5.9-beta] — 2026-10-30

### Fixed — every button registers every click

The loop box's buttons, a pedal's LOAD and ✕, the strip's eye, loop and fork, the pop-out and the
title bar's three buttons acted on AWT's click event, which only arrives when the pointer did not
move a pixel between the press and the release. A firm press that drifts was press, drag, release
and nothing happened, so a click in so many had to be made twice. They now fire on the release when
it lands inside the button, as Start and Rescan already did and as a native button does. Dragging
off before releasing still cancels.

---

## [1.5.8-beta] — 2026-10-30

### Fixed — a new lane's ✕ no longer arrives lit

Removing an empty lane with ✕ took the button out from under the pointer, so it never saw the mouse
leave and kept its hover. Lane rows are kept and reused, and the next + brought that row back with
its ✕ already red. A button now drops its hover when it leaves the tree or is hidden.

---

## [1.5.7-beta] — 2026-10-30

### Changed — the view follows a recording too

While the first lane records there is no loop yet, but there is a playhead: the front of the
recording, running from the top. It is drawn now, and the time view follows it by the same rule as
playback — paging forward while it is in view, left alone once you have scrolled elsewhere.

---

## [1.5.6-beta] — 2026-10-30

### Added — the time view follows the playhead

While the playhead is in view, the view keeps it there: it pages forward when the playhead runs
off the right edge and jumps back when the loop comes round. Scroll away yourself and it stops
following, since you are looking somewhere else on purpose, until the playhead is in view again —
scrolled back to, or come round to.

---

## [1.5.5-beta] — 2026-10-30

### Changed — the first recording counts as it goes; waveforms show their shape

- While the first lane records, the loop readout counts the seconds as they go, up to the minute,
  instead of waiting for the lane to be stopped before showing a time.
- Waveforms are drawn at three times the time resolution — ninety pixels a second rather than
  thirty, so the minute is 5400 pixels wide under the scrollbar — and each lane is scaled to its
  own loudest sample, so a quiet take fills its lane as a loud one does. A guitar's attacks and the
  gaps between them show where before they averaged into a smooth band.

---

## [1.5.4-beta] — 2026-10-30

### Added — click a waveform to play from there; ✕ clears before it removes

- **Click anywhere on a lane's waveform** and the loop plays from that moment; drag to scrub.
  Every lane shares the playhead, so it moves on all of them. The move lands at the top of the next
  audio block, so nothing is torn mid-block, and it is refused while a lane is recording, since
  that lane is being written at the playhead.
- **✕ clears a lane that holds something**, keeping the lane; pressed again on the now-empty lane
  it removes it. The first lane is never removed, so its ✕ is dimmed once it is empty. Clearing the
  last lane with anything in it frees the loop length, as before.

---

## [1.5.3-beta] — 2026-10-30

### Added — play and pause for the loop box

A transport button under the lanes, as wide as their button blocks and aligned with them, to the
left of the time scrollbar. Pause fades every lane to silence and holds the playhead; play resumes
from there. Pausing ends a recording where it is, and REC while paused starts the transport again.
The readout says "paused at" while it is.

---

## [1.5.2-beta] — 2026-10-30

### Changed — a knob answers to the mouse only on the dial

A knob's component is a box wide enough for its name and its readout, and the whole box used to
take the mouse: a wheel notch or a drag that began beside the dial, in what looks like pedal, turned
it. Now only the dial itself, with a few pixels of grace, and the readout where a click opens the
editor, respond. Beside and between the knobs the pedal shows through, and a press there picks the
pedal up to move it, as the picture always suggested.

---

## [1.5.1-beta] — 2026-10-30

### Fixed — scrolling no longer stops on a knob

Each wheel notch went to whatever was under the pointer at that instant. Scrolling from an empty
part of the board slid a knob under the still mouse, and the next notch turned the knob instead:
the scroll stopped and a setting had moved. Notches that arrive close together are now one gesture
whose first notch picks the target — the scroll pane, or a knob — and every notch after it goes to
the same place however the content has moved. Moving the mouse ends the gesture, so a hand that
goes from one knob to the next is still turning knobs. A deliberate turn is unchanged; the loop
board's waveforms, which scroll time under the wheel, follow the same rule.

---

## [1.5.0-beta] — 2026-10-30

### Added — a loop box

A loop icon on a new strip above the boards opens the loop board: up to four lanes of a minute
each, stacked above the pedal board, each with a waveform, a level knob and a block of square
buttons. One horizontal scrollbar under them moves all four through the minute, and a playhead runs
across them.

- **REC** records a lane from the top of the loop and goes red; a second press, or Escape, Space or
  Enter anywhere in the app, stops it. **The first lane you record sets the loop's length.** Every
  lane after it waits for the top of the loop, starts there to the sample, and stops by itself when
  the loop comes round. The length is free again once every lane is empty.
- **What is recorded is the finished sound**, after the master and without the lanes already
  playing, so a new lane never doubles an old one.
- **ORDER** is where a lane plays back. `AFTER` is under the live guitar, as recorded. `THRU` is
  into the start of the chain, ahead of both boards, so the pedals run over it again: record a riff
  with the overdrive, then hear it through the delay, and change the delay while it loops.
- **ON** is the lane's toggle, lit when it is heard. **KEY** waits for the next key and binds it to
  that toggle, so a footswitch that acts as a keyboard works hands free; the binding is remembered,
  and KEY then Escape clears it.
  The three stop keys cannot be bound.
- **SAVE** writes a lane as a mono float WAV, the recorder's format. **LOAD** takes any sound file
  the JDK reads, mixes it to mono, keeps its first minute and converts it to the stream's rate.
- **✕** removes a lane and **+** adds one at the bottom, up to four. Both are on every lane;
  the one that cannot be used there — ✕ on the first lane, + anywhere but the last — is dimmed.
  KEY followed by Escape clears a lane's binding.
- **Pop-out**, at the loop board's right, moves it into a window of its own with the app's own
  title bar — minimise, maximise, close — and remembers where you put it. Close brings it back into
  the stack. Neither touches the audio.
- **The lanes never go away** short of quitting. Loop off stops playback with a fade and keeps
  every lane; stopping the stream keeps them; changing the sample rate resamples them.

### Changed — a strip for the switches

The eye, the loop and the fork now sit on a strip of their own between the top bars and the boards,
drawn a size larger and spaced wider. The eye is one setting for both boards again, since a strip
has one eye. Nothing on a board is pinned to the top of the window any more: a board's name and its
LEVEL are part of the board and scroll with it.

### Under the hood

- `Looper` runs on the audio thread as three calls a block around the chain, allocating nothing:
  every lane's buffer is sized in `prepare`, a minute at the stream's rate. The engine's allocation
  test runs with three lanes playing and a fourth recording.
- `--selftest --chain` carries the loop box the same way, so its cost is measured with the rest.
- Seventeen new tests, 370 in all.

---

## [1.4.2-beta] — 2026-10-30

### Changed — split mode arrives without moving anything

- **The board no longer shifts when split is switched on.** The header lane was sized for the LEVEL
  knob only while the knob was showing, so switching split on pushed every row of pedals down to
  make room for it and switching it off pulled them back up. The lane has that room from the start
  now, with one board and with two, and the knob and the board's name fade into it. The second board
  still fades in below, and the first board's rows glide to their half of the window instead of
  jumping there; the hairline between them fades with the second board.
- **Nor does it shift when the scrollbar appears.** When two boards need more height than the
  window has, the scrollbar used to arrive all at once and take its width from the viewport, so the
  rows, centred in it, lurched sideways by half a scrollbar. Its column is now reserved from the
  start, and the thumb fades in when there is something to scroll and out when there is not.
- **Each board's eye is its own.** Hiding the switched-off pedals on the first board used to hide
  them on the second as well, and both were one remembered setting. They are two now, remembered
  separately, so one board can be trimmed to what is on while the other shows everything.
- **The second board's ✕ is gone.** The fork beside the first board's eye is the one way into
  split mode and the one way out of it; two ways out of the same thing was one too many.

One new test, 353 in all: the two eyes are remembered independently, in both directions.

---

## [1.4.1-beta] — 2026-10-29

### Fixed — a dropdown's width no longer follows the word on it

Every TYPE dropdown — on the FX slots, the Overdrive, the Amp and the IR — is now sized for its
longest option from the start, so picking a shorter one does not narrow the button, the pedal and
then the wrap of the whole board. A pedal may still change width because its knobs changed, or
because LOAD appeared on the IR's loaded-file type; it no longer changes because a word did.

---

## [1.4.0-beta] — 2026-10-29

### Added — an AMP pedal, voiced as five amplifier families

A new pedal between the overdrive and the capture, with a TYPE dropdown like the Overdrive's:
`American Clean`, `Tweed`, `British Crunch`, `British Chime` and `Modern High Gain`. Six knobs —
Gain, Bass, Mid, Treble, Presence (Cut on the chime family) and Master — of which the pedal shows
four, with the EQ's expand button for the other two. It arrives switched off on every existing
preset, since none of them mention it.

**These are voicings, not models.** A component-level model of one amplifier takes years and a
neural capture of one is what the NAM pedal loads; a capture of the real thing wins, every time.
What a voice has is the stages an amp has, with a family's gain structure and tone curve, so it
sounds like a blackface clean or a plexi crunch rather than like any particular one of them. It is
for having an amp when there is no capture, and for the ten-second switch between families.

- **Preamp**: two to four cascaded biased soft-clip stages, each with a coupling high-pass and a
  gentle low-pass after it. Two stages into each other behave nothing like one at twice the gain.
- **Tone stack**: a low shelf, a mid peak and a high shelf at the family's frequencies, with the
  family's *mid at noon* — scooped on the American stacks and the modern one, full on the plexi —
  which is the thing a player recognises first.
- **Power amp with sag**: an envelope follower drops the gain after a loud transient and lets it
  back over a couple of hundred milliseconds. The tweed sags most; the test asserts it does.
- **Two-times oversampling** around the whole path. Clipping at the device rate aliases, and
  aliasing is the fizz that makes cheap amp simulations sound cheap.
- Tested at every knob's loudest with a square and a sine at 44.1 and 96 kHz to stay inside full
  scale, silent on silence despite the biased stages, more distorted with more gain on every voice,
  cleaner on the clean voice than the high-gain one at the same knob, and the tone stack moving
  the frequencies it names.

### Added — synthesised cabinets on the IR pedal

The IR pedal has the same dropdown. `Loaded IR` is first and the default — it is the file, as
before, and the better cabinet. After it, `1x12 Open`, `2x12 Open`, `4x12 Closed` and `1x10 Small`:
the broad shape of a speaker in a box as five filters — a steep cut below the cone's resonance, a
low-mid bump for the box, a presence peak, and a 24 dB-per-octave roll-off above a few kilohertz,
which is the single biggest thing a cabinet does. They need no file, which is every first run, and
an amp voice without one sounds like a wasp.

- A synthesised cabinet has a MIC knob, from OFF-AXIS to ON-AXIS, that tilts the roll-off and scales
  the presence peak, and a Level. The readout says the words at the ends and a percentage between.
- LOAD and its ✕ belong to `Loaded IR` and hide on the other types. A file loaded before switching
  to a cabinet is still there on switching back.
- Old presets name no type and carry their file, so they land on `Loaded IR` with it, unchanged.
- Tested: every cabinet silent on silence, at least 20 dB down at 10 kHz against 1 kHz, the 4x12
  darker and thumpier than the 1x12, on-axis brighter than off-axis, and the same curve at 96 kHz
  as at 44.1.

### Changed

- `--selftest --chain` now carries the amp on `Modern High Gain` and the IR pedal on `4x12 Closed`:
  the heaviest the shipping chain can be without a file, which the figures in CLAUDE.md are owed
  against. Measured on this machine over the virtual cable at 264 frames with `--stress`: worst
  callback 0.28 ms with the amp and cabinet in the chain, against 0.30 ms without them in the
  1.2.0 measurement — the addition is below the variation between runs at that buffer. The
  oversampled amp is a few dozen operations a sample; it was never going to be the expensive part.
- A `Parameter` can name its two ends, which is how MIC reads.
- Sixteen new tests, 352 in all.

---

## [1.3.0-beta] — 2026-10-29

### Added — the Overdrive is five drives, from a dropdown

The Overdrive pedal has a TYPE dropdown on its header, like an FX slot, and the knob row is the
chosen type's own: three on a Screamer, two on a fuzz, four on a tube stage. One pedal in one fixed
place on the board, doing the job of a row of them. Switching type is a short fade rather than a
click, is allowed while the stream runs, and every type is built and warmed before the stream
starts, so the first time one is chosen it is already compiled.

| Type | Knobs | What it is |
| --- | --- | --- |
| Smooth | Drive, Tone, Level | the pedal's original `tanh` soft clip, unchanged, and the default |
| Screamer | Drive, Tone, Level | high-pass at 720 Hz into a mildly asymmetric soft clip, a +4 dB hump at 800 Hz |
| Rat | Distortion, Filter, Volume | hard clip; Filter is a low-pass that closes as it is turned up, 6 kHz down to 400 Hz |
| Fuzz | Fuzz, Volume | full bass into a hard asymmetric clip; the bias grows as the note dies, so it sputters on the decay |
| Tube | Gain, Bass, Treble, Level | soft clip with a fixed bias for second harmonic, then a bass and a treble shelf |

These are voicings in the style of those pedals — the clipping shape and the filters that family
has — not models of a circuit. A NAM capture is what a real one sounds like.

- **Presets.** A preset records the Overdrive under its own name in a new `modules` block: which
  type, and every type's knobs, so switching away from a dialled-in Rat and back returns it. A
  preset from before this version has no such block and flat `Overdrive.Drive` keys; those land on
  Smooth, which has the same three knobs under the same names, so every existing preset loads
  sounding exactly as it did. A preset saved now and opened by an older build loses only the type.
- **Every drive is tested at its loudest**, with a full-scale square and a sine at 44.1 and 96 kHz,
  to stay inside full scale: a drive that clipped the engine would be the loudest possible bug.
  Each is also asserted silent on silence — the two biased ones subtract the clipper's resting value,
  so switching the pedal on is not a thump — and asserted to actually add harmonics to a sine.
  Smooth is checked sample for sample against the original formula.

### Changed — inside

- The type-switching core of `FxSlot` — one instance of every type, the fade across a switch, the
  per-type knob capture — is now `TypedModule`, and both `FxSlot` and `Overdrive` are one. The slot
  keeps its numbering and its plus and ✕; the Overdrive keeps its fixed name and place.
- The five drives live in `dsp/Drives.kt`. `SmoothDrive` is the old `Overdrive` class renamed.
- Eleven new tests, 336 in all.

---

## [1.2.2-beta] — 2026-10-29

### Added — two or three drum patterns in every bar

The kit used to offer three feels in 4/4 and one each in 3/4, 6/8 and 12/8, and nothing in 2/4.
Every signature now has at least two drum patterns, and the new ones are chosen to be different
things to play against rather than variations on one. All are grids in `Feels.grooves`, synthesised
like everything else in the rhythm section — nothing sampled ships.

- **2/4** — `March` (kick on both beats, snare on 2), `Polka` (kick on the beats, snare on the
  off-beats), `Train` (the country train beat: snare sixteenths with the backbeat accented, over a
  kick on both beats — the one pattern in the catalogue with four steps to the pulse).
- **3/4** — `Jazz waltz` (the swung ride figure 1, 2, and-of-2, 3, with a cross-stick on 3) and
  `Rock waltz` (full snare on 3, kick pushing in on the and-of-2), beside the existing `Waltz`.
- **6/8** — `Jig` (kick on both pulses, side-stick on the third eighth of each) and `Driving` (the
  rock 6/8: backbeat on the second pulse, kick on the last eighth leading back to 1), beside
  `Ballad`.
- **12/8** — `Driving` (slow blues with every pulse accented on the hats and a kick on the third
  eighth of 1 and 3) and `Doo-wop` (soft triplets throughout, side-stick on 2 and 4), beside
  `Slow blues`.

`Driving` is named the same in 6/8 and 12/8 on purpose: SIG changes keep the feel by name where the
new bar has one, so moving between the two compound bars stays on the same kind of groove. 4/4 is
unchanged at `Rock`, `Half-time` and `Shuffle`. The warm-up walks the whole catalogue, so every new
pattern is compiled before the stream starts, and the existing test that checks every pattern's step
table against its bar covers them all.

---

## [1.2.1-beta] — 2026-10-29

### Fixed — the board's header stays on top when it scrolls

The eye, the split fork and a board's LEVEL are pinned to the top of what is on screen, but they
were painted *beneath* the pedals: Swing paints children from the highest index down, and the header
was added after them. In its lane nothing overlapped it, so nobody saw it. Scroll a board — which
split mode makes routine — and the pedals slid straight over the controls.

The header is now raised to the front of the stacking order whenever it is placed and again when a
dragged pedal is dropped (a lifted pedal is raised for the length of its drag and had been staying
there), and it has an opaque backing in the board's own background colour, so the controls stay
legible over a pedal passing underneath rather than merely drawn over it.

---

## [1.2.0-beta] — 2026-10-29

### Added — split mode: two boards, one guitar

A fork icon beside the eye at the board's top-left. Press it and a **second board** fades in under
the first, carrying every module the first has — gate to reverb, its own FX slots, its own NAM and
IR — fed the same guitar and heard with it. One sound can now be a clean amp and a driven one at
once, or a dry signal beside a delayed one. The icon is grey in both states, as every header glyph
is, and struck through while there is one board: the line says what is true now, and pressing it
removes it. The second board's ✕, at the far right of its header, is the other way out.

- **Each board gets a LEVEL** in its header, shown only in split mode: the same compact knob and
  readout as MASTER, click to mute. It sits at the **end** of its board, just before the two are
  summed, for the reason the master moved there (below) — a level ahead of the drive would change
  the distortion as well as the loudness. Default 71 %, which through the knob's squared taper is a
  gain of 0.504, a hair under -6 dB. Two boards fed the same guitar are correlated, so their
  amplitudes add; two at that gain sum back to the level one board had, which is what keeps
  switching split on from clipping. With split off the LEVELs are inactive — ramped to unity and
  then not touching the buffer, bit for bit — so a one-board sound is exactly what it was.
- **Switching is allowed while the stream runs**, unlike adding or removing a module. Nothing is
  added to a chain: the second board is always prepared and warmed, and the engine fades its share
  of the output in and out over the master's own 11 ms rather than switching it. Off, the second
  board is run only until its fade finishes and then not at all, so it costs nothing idle. The
  engine test asserts the fade never steps the output by more than a hundredth, and that the
  callback still allocates nothing with both boards running.
- **Saved.** A preset or session written in split mode carries the second board as a nested
  `split` block — a whole preset inside the preset, because both boards use the same module names
  and one map cannot hold two Delays. A sound saved with one board writes no such block and reads
  exactly as it did before, and a build from before this version reads a split preset as its first
  board. With split off the second board is not saved, but it keeps its settings for as long as the
  program runs, so switching off and on again returns what was there.
- **Two amp captures need a bigger buffer, and the program says so.** One capture measured at
  97.7 % of a 64-frame period, so two will not fit at 64 and are on the line at 128. When both boards
  carry a capture at under 256 frames, the status line says to set BUFFER to 256 or more — when
  split is switched on, when the capture is loaded, and when the stream starts. Said rather than
  enforced: a faster machine may manage, and `realtimeRatio` tells the truth either way.
- `--selftest --chain --split` runs the shipping chain on two boards, summed, which is the heaviest
  the program can be put without a file on disk. Measured on this machine over the virtual cable at
  264 frames with `--stress`: worst callback 0.30 ms with one board and 0.23 ms with two, which is
  to say the second board's cost is below the variation between runs at that buffer size. The chain
  without captures was never the expensive part; two NAM captures are, and that is the case the
  256-frame advice is for.

### Changed — the master is now last

The master volume used to be **first** in the chain, ahead of the gate. That made it an input trim
rather than a master: turning it down fed less into the compressor, the gate and the overdrive, so
it changed how hard the drive was pushed as well as how loud the sound was. It is now after the
boards are summed and before the rhythm section, and changes only the loudness of the finished sound.

- Existing presets load unchanged — the master was never in a preset's order, only pinned by code.
  But the drive, the gate and the compressor now hear the pickup directly rather than through the
  master's default 80 %, which was a gain of 0.64. A sound dialled in before this plays about 4 dB
  hotter into the drive and the gate opens a touch more easily. Expect to nudge a gain or a
  threshold on a sound you had set up.
- The metronome is unaffected either way: it is mixed after the master and has its own LEVEL.
- `MasterVolume` and the new `BoardLevel` are both a `VolumeStage`, one class with the ramped mute
  and the taper, so the two cannot drift apart.

### Changed — inside

- `MainWindow` no longer owns the pedals. Everything that belongs to one board — the effects, the
  chain, the picture, which file its NAM loaded, which of its FX slots is first — moved into
  `ui/Board.kt`, so it can exist twice. The window keeps what there is one of: the master, the
  devices, the session, the presets.
- `AudioEngine` takes an optional second chain and an optional post chain. The warm-up covers all
  three, and the headless callers that pass one chain are unchanged.
- `ChainBoard` has a header lane with two ends: the eye and the fork on the left, the LEVEL and the
  ✕ on the right, pinned to the top of what is on screen as the eye always was. A new `BoardStack`
  holds one or two boards in the scroll view and fades the second in and out.
- The eye is one preference for both boards: pressing it on either hides switched-off pedals on both.
- Twelve new tests, 325 in all: the second bus, its fade, the post stage on the sum, allocation with
  two boards running, the nested preset, and the level stage's bit-transparency when inactive.

---

## [1.1.0-beta] — 2026-10-29

### Added — Linux, as an AppImage

The program runs on Linux now, natively. The first version that does, so the minor number moves.
Every figure below was measured on one machine: a Manjaro laptop under KDE on Wayland, PipeWire
1.6.9, an HDA Intel PCH card with no HDMI display attached. It is not the guitar interface the
Windows figures were taken on, which matters for the 64-frame result further down.

**Two new backends**, both through JNA as ASIO and WASAPI are, and with no native build step:

- **JACK** (`audio/jack/`). Linux's equivalent of ASIO: one clock, a callback on the server's
  realtime thread, no adapter latency. It also reaches PipeWire, through PipeWire's JACK library.
  Each client that owns hardware ports is listed as a device, so under PipeWire every sound card
  appears separately and input and output can be picked independently. The server owns the rate,
  which is shown read-only. Choosing a buffer asks the server to resize; whatever it grants is
  mirrored back, and the original size is put back on close. The client connects only the ports the
  user chose. If a PipeWire server is running, its JACK library is tried by explicit path before the
  system `libjack.so.0`. That covers Debian and Ubuntu, which ship it off the library path, and
  machines that also have jack2 installed with no server running — this one is both.
- **ALSA** (`audio/alsa/`), the fallback that is always present, because every distribution's sound
  server sits on `libasound`. `Default` goes through the sound server: PipeWire or PulseAudio, while
  other programs keep their sound. Each `hw:` device goes straight to the card. Capture and playback
  are listed separately, because they are separate PCMs with separate capabilities. A card held by
  the sound server says so in the picker instead of failing at Start.
  The thread is ours, so it is made realtime: `SCHED_FIFO` directly if the account allows it,
  otherwise through rtkit over D-Bus, which most desktops grant without any setup. This account
  has `rtprio` 0 and rtkit granted priority 20.

The ladder on Linux is JACK, then ALSA, then Java Sound.

Measured with `--selftest --chain --stress`, 20 s:

| Path | Buffer | Result |
| --- | ---: | --- |
| ALSA, analog in → HDMI out, both direct | 256 @ 48 kHz | **clean**: ratio 0.9998, 0 xruns, worst 0.28 ms of 5.33 |
| ALSA, PipeWire `Default` in → HDMI out | 256 @ 48 kHz | clean |
| ALSA, direct | 128 and 64 | **fails** on this card — see below |
| JACK, jackd dummy driver | 128 (server's own) | clean |
| JACK, jackd dummy driver, resized by the client | 64 | 0.4 xruns/s; the dummy server had no realtime priority |

The failures at 128 and 64 frames belong to this card, not the backend. ALSA's own C tool
`alsaloop`, given the same pair of devices, logged 222 xruns at 5.3 ms of latency, 2 122 at 2.7 ms
and 91 at 1.3 ms. Omega runs that pair clean at 5.3 ms. **No guitar interface has been tested on
Linux yet**, and the low-latency verdict waits on one.

**A bug found and fixed on the way.** The first ALSA loop read whatever input was available.
After a partial read it then waited for a whole further period, because a wait wakes only once
`avail_min` (one period) is available. It fell a little further behind the card on every cycle
until playback ran dry: an xrun every third period at 256 frames. It now waits until the full
remainder is there before reading.

**Packaging.** `./gradlew appImage` builds `Omega-Plugin-<version>-Linux.AppImage`: one file, a
trimmed Java 21 inside it, and nothing to install. That includes SteamOS and Bazzite, where a
`.deb` or `.rpm` cannot be installed. `AppRun` uses the bundled runtime and never a `java` from
PATH, with the same JVM flags as the Windows launcher. The image carries a `.desktop` entry and an
icon, plus a notice naming the bundled runtime's exact build and where its source is. The bundled
runtime was run with an empty environment: it found its own Java, loaded JNA, ALSA and rtkit, and
ran generational ZGC. The GUI opened under KDE Wayland and ran without an exception. Only its
log output was checked; nobody has looked at the window yet.

### Changed — the release is two files

`release.yml` now has a `version` job, then `windows` and `linux` packaging jobs in parallel, then
`publish`, which attaches both files to one draft release. If either platform fails, nothing is
published. The Linux job runs on **ubuntu-22.04**: an AppImage runs against the host's glibc, so it
has to be built on the oldest system it promises to run on. `appimagetool` is pinned to 1.9.1 by
URL and by SHA-256, because it is an executable fetched and run with the release token in scope.

`build.yml`'s Linux leg moved to ubuntu-22.04 too. It now also packs the AppImage, runs
`--list-backends` from it and checks for generational ZGC, so a packaging break fails the pull
request instead of the release. The AppImage is kept as a build artifact for 14 days.

### Changed — the registry loads only this platform's backends

`Backends.OPTIONAL` pairs each class with its OS, and a backend is loaded only on that OS. Before,
a Linux user would have seen ASIO and both WASAPIs as "unavailable", entries they could never have.
**On Windows the list is exactly what it was**: the JACK and ALSA classes now exist, but they are
never loaded there. `BackendSeamTest` pins both directions and runs on both CI legs.

### Fixed — the degradation path, exercised and dated

The JNA-off-the-classpath test from `CLAUDE.md` was run against this version, on Linux. Java Sound
streamed and passed, and both native backends gave a reason for being unavailable. It also found
a fault. The Linux OS check lived in the same class as the JNA declarations, so the check meant to
keep a backend away from native code was itself what loaded it. `--list-backends` then died on an
uncaught `NoClassDefFoundError` after printing everything else. The check now lives in its own
JNA-free object (`LinuxPlatform`), and the realtime line reports "could not be checked" instead of
crashing.

### Changed — small things a Linux user would have hit

- The ☰ → Logging note builds its path with the platform's separator. On Windows it still reads
  `logs\omega.txt`.
- `DeviceCapabilities.unusableReason` lets a backend name the specific problem, such as an ALSA
  card held by the sound server. Before, every unusable device got the WASAPI sentence, which tells
  the user to try exclusive mode or ASIO. It defaults to null, so the Windows backends say what
  they always said.
- `AudioStream.schedulingNote` reports whether the audio thread got realtime priority. It is
  logged, printed by `--selftest`, and turns the status line red when the answer is no. It
  defaults to null, so Windows is unaffected.
- `--list-backends` on Linux ends with a `Realtime:` line, worked out by trying a promotion on a
  throwaway thread. When it fails, the line gives the fix for each distribution family.

**Not changed:** the file dialogs. `docs/linux-compat.md` expected `FileDialog` to be poor on
Linux, but with GTK present the JDK uses the GTK dialog. They have not yet been opened on Linux.

## [1.0.67-beta] — 2026-10-15

### Fixed — `gradlew` was committed without its executable bit

The first push ran both workflows for the first time. Windows passed on both jobs; Ubuntu failed on
the first line that touches the wrapper with `./gradlew: Permission denied`. The file was fine — LF
endings, pinned by `.gitattributes` — but git stored it as mode `100644`, because it was added from
Windows, where there is no executable bit to read and `core.filemode` is off. Git Bash on the Windows
runner runs a script regardless of its mode, which is why one leg passed and hid the other.

Fixed in the index with `git update-index --chmod=+x gradlew`, which is the only way to set it from
Windows; the mode is now `100755`. Recorded in `CLAUDE.md` so that a re-added wrapper does not come
back without it.

### Changed — the release waits for the build

The same first push showed the other half of the problem. `release.yml` ran on the push directly, so
while Ubuntu was failing, Windows was packaging and publishing a draft of a commit that had just been
shown not to build everywhere. The two workflows were independent, and a release is not supposed to
be.

`release.yml` now triggers on the `build` workflow completing on `main`, and its job runs only when
that run's conclusion is success — a failed or cancelled build also "completes", so the condition is
explicit. It checks out the commit the build passed on (`workflow_run.head_sha`) rather than the
branch tip, so a push landing mid-run cannot slip into a release it was never tested for.
`workflow_dispatch` still runs it by hand. The cost is that a release starts a few minutes later,
after both build legs finish.

### Changed — `CLAUDE.md` no longer describes the repository as unpublished

It said there were no commits and that the workflows were unexercised. Both are now false, and the
paragraph instead states the rule the workflows enforce: a push to `main` is a release, so every
change bumps the version.

## [1.0.66-beta] — 2026-10-15

### Changed — README reads top-down for a guitarist, and only then for a developer

The old README opened with build commands, mentioned the Releases zip in the third paragraph as
something `gradlew.bat exeZip` produces, said "the diagnostics above still work from it" about
diagnostics it never listed, and ended its build section with "Requires Java 21 or newer" right after
the paragraph saying Java is bundled. A musician arriving from the release page got a developer's
document with the one thing they needed — download, unzip, double-click — nowhere in it.

It now runs: what it is, **Get it** (the zip from Releases, nothing to install, keep the folder
together), **First run** (devices, DRIVER, BUFFER, loading a capture), **Latency**, **If it sounds
wrong** (the two commands from the bundle's read-me, with the output redirected because the `.exe`
prints nothing to a console on its own), and only then **For developers**, where the Java 21
requirement lives beside the build it applies to. The licence sections are unchanged.

## [1.0.65-beta] — 2026-10-15

### Changed — beta

The alpha suffix goes. Nothing in the code earned it in this version; what changed is that the
repository is about to be public, and a version string is the first thing a stranger reads. Alpha
said "do not expect this to work". The program holds a 64-frame ASIO stream under an allocation flood
for sixty seconds at a time, every one of its 304 tests passes on a machine with no audio device, and
the one thing still missing before it could be handed to anyone — the bundled runtime's source, below
— is fixed here. Beta is the honest word: it works, on one platform, against one interface, and has
not yet been run on anyone else's.

### Fixed — the Windows bundle now says where its Java runtime's source is

`READ-ME-FIRST.txt` in both zips named Eclipse Temurin and kept its notices in `runtime\legal\`, and
said nothing about source. Redistributing an OpenJDK binary carries that binary's own obligation —
GPLv2 §3, since that is the JDK's licence — and a pointer at where Adoptium publishes the source is
what discharges it. Open since 1.0.3x, where this changelog said "not yet written down anywhere in
the bundle".

The read-me now names the **exact build** and the repository its source lives in, read at build time
from the toolchain's own `release` file (`IMPLEMENTOR_VERSION`, `IMPLEMENTOR`, `SOURCE_REPO`) rather
than typed in: foojay provisions the toolchain, so a number copied by hand would be wrong the first
time it moved, and a build name that was not the one shipped discharges nothing. A toolchain whose
`release` file lacks any of the three fails the bundle tasks by name rather than writing a read-me
with a hole in it. The README's third-party table and **☰ → Licences** carry the same pointer.

### Changed — the Apache 2.0 text in the jar is now deliberate

The Kotlin standard library and the JetBrains annotations are Apache 2.0, and Apache 2.0 §4(a) asks
that recipients get a copy of the licence. Neither jar carries one. The fat jar has had the text all
along — JNA ships it as `META-INF/AL2.0` — so the obligation was met, but by accident, and an
accident is one dependency bump from not being true. The Licences dialog and the README now name that
path, so the copy is accounted for rather than incidental.

## [1.0.64-alpha] — 2026-09-09

### Changed — a push to `main` is the release, and the version decides

`release.yml` used to fire on a `v*` tag and refuse to run if the tag disagreed with the version in
`build.gradle.kts`. It now fires on a push to `main`, reads the version, and — if no tag matches it
yet — builds the bundle and publishes a draft release at `v<version>`, creating the tag itself.

Two things follow, and the second is the reason for the change:

- **Merging is the whole ceremony.** No separate tag to remember, and one fewer command between
  finishing work and having something to hand somebody.
- **The version can no longer disagree with the tag**, because only one of them is written by hand.
  The old guard could only *detect* a mismatch and fail; there is now nothing to mismatch. It also
  means the "bump the patch version every change" rule is enforced by the machine rather than by
  memory: an unbumped version publishes nothing.

**A push whose version is already released fails.** Landing on `main` is releasing, so an unbumped
version is a mistake rather than a quiet no-op, and the run says which: *"v1.0.64-alpha is already
released. Every push to main must bump the version in build.gradle.kts and add a CHANGELOG entry."*

The same check now runs in `build.yml`, on one of the two runners, so a pull request that forgets to
bump fails *before* the merge instead of turning `main` red afterwards. A workflow cannot reject a
push — it runs once the push is already accepted — so blocking a merge outright needs pull requests
into `main` plus the `build` check marked required, under Settings → Branches. That setting is the
only part of this that lives outside the repository.

`concurrency: release` queues rather than cancels, because two pushes in quick succession each have
a version of their own worth publishing, and cancelling would drop one. `fetch-depth: 0` is
load-bearing — the default single-commit checkout fetches no tags, so every version would look
unreleased and every push would try to publish. Test results are uploaded on failure, as `build.yml`
already did.

`build.yml` is unchanged: still compiling and running the 304 tests on Windows and Ubuntu for every
push to `main` and every pull request. It publishes nothing; it is there so a broken test shows up
before a release is attempted rather than during one.

## [1.0.63-alpha] — 2026-09-09

### Changed — a release page with one download on it

`release.yml` attaches one asset, now named **`Omega-Plugin-<version>-Windows.zip`**. The fat jar,
the loose `omega.bat` and `omega.sh`, the `.bat`-launcher `portableZip` and the standalone `LICENSE`
are no longer uploaded, and the release job no longer builds `portableZip` at all.

The archive was `OmegaPlugin-<version>-windows-x64-exe.zip`, which names the launcher format and the
architecture — neither of which means anything to a guitarist, and "exe" inside a `.zip` reads as a
contradiction. It is still x64-only, because the bundled runtime is; that belongs in the release
notes, not in a filename nobody can act on. The workflow's glob moved with it, since a stale pattern
there would produce a release with no download on it at all.

The audience is guitarists, not developers. Five downloads ask a musician to work out the difference
between them before they can play a note, and picking wrong — the jar, on a machine with Java 17 —
fails in a way that reads as "the app is broken" rather than "wrong download". The exe bundle is the
only one that needs nothing installed, so it is the only one offered.

Nothing licence-related is lost. GPLv3 §4 wants the licence *with the program*: LICENSE is inside
that zip, and so is README.md with the third-party table and the source URL. §6(d) wants the source
from the same place, and GitHub attaches the tag's source archives to every release by itself.

A Linux AppImage becomes the second asset once there is a Linux audio backend worth shipping inside
it; `docs/linux-compat.md` holds that plan. Until then, one.

## [1.0.62-alpha] — 2026-09-09

### Changed — the exe bundle ships a single launcher

`exeZip` now contains `OmegaPlugin.exe` and nothing else to click. The `omega.exe` console face
added in 1.0.61 is gone, along with the `--add-launcher` property file that produced it: a second
executable beside the one people are meant to double-click is clutter in a release folder.

The diagnostics did not go with it. `OmegaPlugin.exe --list-backends > report.txt` produces the same
report — a GUI-subsystem binary attaches to no console, but a redirect gives it a perfectly good
handle — and `READ-ME-FIRST.txt` now says so. Verified through `cmd.exe`: `--list-backends` and
`--selftest --chain --stress` both run to completion and cmd waits for them.

That redirect is also why this launcher gained `-Dstdout.encoding=UTF-8` and its `stderr` twin, which
`omega.bat` deliberately does not have: there it is half a pair with `chcp 65001`, but this launcher
has no console to keep in step with and its output only ever reaches a file. Left alone the JDK falls
back to cp1252 and writes a lone `0x97` for an em-dash, which is invalid UTF-8 anywhere the report is
later pasted.

## [1.0.61-alpha] — 2026-09-09

### Added — `exeZip`: a Windows bundle with a real launcher instead of a `.bat`

`gradlew.bat exeZip` produces `OmegaPlugin-<version>-windows-x64-exe.zip` — 38 MB, the same size as
`portableZip`, holding `OmegaPlugin.exe` to double-click, `omega.exe` for `--list-backends` and the
rest of the diagnostics, `app/` with the fat jar and a bundled Java 21 in `runtime/`.

The cosmetic half is that a `.bat` cannot carry an icon and reports itself to Task Manager as
`java`. The half that matters is that jpackage writes the VM options into `app/OmegaPlugin.cfg`,
which the launcher reads — so the ZGC flags stop depending on the user having started the program
the documented way. `Main.collectorWarning()` keeps its job, but there is no longer a route past it.
Verified rather than assumed: `omega.exe --list-backends` prints `Collector: generational ZGC.
Good.` and enumerates ASIO, so all eight flags arrive and the native backends still load.

**Two launchers, because the program has two faces**, as in `portableZip`: `OmegaPlugin.exe` is
GUI-subsystem so no console lingers behind the window, and `omega.exe` is `win-console=true` via
`--add-launcher` so the diagnostics have somewhere to print. Superseded in 1.0.62.

Four details worth keeping:

- **It reuses `portableRuntime` through `--runtime-image`** rather than letting jpackage run its own
  jlink. jpackage's automatic mode infers modules with jdeps, and `portableModules` carries two that
  jdeps cannot see — `jdk.unsupported`, which JNA needs reflectively, and `jdk.management`. Inferred,
  the bundle would have started, opened the window and then found no native backend at all, which
  presents as "only Java Sound on this PC" rather than as a packaging fault.
- **The `.ico` is generated, not committed**, by `windowsIcon`, from the PNGs already in
  `src/main/resources/icons` and already the window's icon. A checked-in `.ico` would be a second
  copy of the icon that nothing ever re-derives, which makes it the copy that goes stale. ICO is a
  directory of independent images and each entry may be a PNG verbatim since Vista, so the task
  concatenates the seven files and writes the 16-byte directory entries in front of them — no
  `ImageIO`, so no re-encode that could lose the alpha.
- **`--description` is the product name, not a sentence.** jpackage puts it in VERSIONINFO's
  `FileDescription`, and Windows treats that field as the application's *name* — it is what the
  taskbar tooltip, the grouped-window header and Task Manager's Name column show. Set to a prose
  description first, which made the taskbar call the app "Realtime guitar effects processor"; it now
  reads `Omega-Plugin`, and the console launcher `Omega-Plugin diagnostics`. Chrome ships
  `FileDescription=Google Chrome` for the same reason.
- **`--app-version` gets `1.0.61`, not `1.0.61-alpha`.** It lands in the exe's VERSIONINFO resource,
  where Windows takes only `major[.minor[.patch]]` digits and refuses the suffix outright. That one
  field is the only place the version is truncated; the jar, the zip and the folder inside it keep it
  in full, and the About screen reads the manifest.

Both bundles' `READ-ME-FIRST.txt` now comes from one `readMeText()` parameterised on the launcher
names, because every word of that troubleshooting advice applies to both and the copy that lived
inside `portableScripts` would have gone stale the first time this bundle changed a line of it.

`release.yml` builds and attaches both zips, the exe bundle first. Its Windows-runner comment now
says why the job cannot move: a jlink image is per-platform and a jpackage launcher is native to the
machine that built it. Neither task needs WiX, since `--type app-image` is a folder with a launcher
in it rather than an `.msi`.

**Not an installer.** No start-menu entry, no uninstaller, no file associations — those need
`--type msi` and WiX on the runner. Unsigned either way, so SmartScreen will warn on first run until
there is a code-signing certificate. `WhatsDone.md` §3 is updated to say exactly that rather than
reading as though packaging were finished.

## [1.0.60-alpha] — 2026-09-08

### Fixed — `Licences.SOURCE_URL` names a real repository

It read `https://github.com/<your-account>/Omega-Plugin` for the whole of the program's life, which
was the one thing standing between a build and being handed to anybody: GPLv3 §6 wants a binary to
be accompanied by its Corresponding Source, and a URL that cannot be opened offers none. It now
reads `https://github.com/Sam-GN/Omega-Plugin` — in `ui/Licences.kt`, in README.md, and therefore
in **☰ → Licences**, where the program says it out loud. Closes `known-issues.md` §6,
which is deleted; the `Octave` entry below it renumbers from §7 to §6.

`LicencesTest` kept its guard rather than losing it along with the issue. It used to assert that the
placeholder was *the documented placeholder*; it now asserts the URL carries no angle bracket and
names an account and a repository. The shape is checked rather than the exact repository, so moving
the repository stays one edit instead of two — what cannot come back is a placeholder.

### Fixed — the README claimed Linux support that has never existed

The second sentence of the landing page said "Runs on Windows and Linux." `linux-compat.md` opens by
saying that no part of this program has ever been executed on Linux, and both cannot be true. The
README now says Windows only, and alpha, and describes the seam's Linux shape as the design claim it
is rather than as a test result. It also mentions `portableZip`, which existed and was documented
nowhere a user would look.

The same paragraph still described the chain as "master volume, gain, overdrive, delay, reverb",
which is five modules of a board that now carries eleven, and the screenshot directly beneath it
would have said so. Both it and the matching sentence in CLAUDE.md now read the order out of
`MainWindow.pedals`, and both keep the one clause that is a design rule rather than a default —
time-based effects last, so repeats and tails are never fed back into the clipper.

### Added — the repository furniture that publication needs

Nothing here changes a sample. All of it is what a repository has to carry before it is worth
pointing anyone at.

- **`SPDX-License-Identifier: GPL-3.0-or-later` in all 96 source headers.** Nothing legal changes —
  the English wording above it already said "or later". What changes is that machines can now read
  it: GitHub's detector, dependency scanners and compliance tooling look for the SPDX line and
  otherwise label the project plain GPL-3.0, dropping the "or later" half of what was granted.
- **A screenshot in README.md**, at `docs/img/screenshot.png`, which is the first thing a repository
  visitor asks a GUI program for and the last thing this one had. It is a real running stream rather
  than a mock-up — WASAPI exclusive, 48 kHz, 144 frames, 9.0 ms by the driver's reckoning, 1 % DSP,
  no dropouts — and the caption says so, on the same principle as every other figure here.
- **`.github/workflows/build.yml`** — `gradlew build` on `windows-latest` *and* `ubuntu-latest`. The
  Linux leg is not redundancy: no test needs an audio device and none touches AWT, so it costs
  nothing and is the cheapest standing evidence that nothing below the backend seam has grown a
  Windows assumption. It is a compile, not a port.
- **`.github/workflows/release.yml`** — on a `v*` tag: fat jar, launchers, `portableZip` and
  `LICENSE`, as a draft release. It runs on Windows because a jlink image is per-platform, and a
  bundle built anywhere else would carry a runtime the launchers cannot start. It refuses to publish
  a tag whose name disagrees with the version in `build.gradle.kts`.
- **`.github/ISSUE_TEMPLATE/bug_report.yml`** — asks for `--list-backends` output and for
  `realtimeRatio` by name, with the reason `deadlineMisses` is the wrong counter stated in the form
  itself rather than left for the reporter to know.
- **`CONTRIBUTING.md`** — the rules that are not preferences: the LGPL 2.1 election on JNA, never the
  Steinberg SDK, no bundled captures or samples, ZGC as product rather than tuning, and what is
  forbidden inside a callback.
- **`.gitignore`** now keeps the repository to the program and its documentation, and nothing else:
  `.idea/` and `.run/` entire, since Gradle drives the build and IntelliJ rebuilds all of it on
  import — `misc.xml` in particular named the SDK `corretto-11`, which is neither present on a clone
  nor the version the toolchain asks for; `.claude/` and `.mcp.json`, the tooling this is developed
  with, whose `settings.local.json` records absolute paths under the developer's home directory;
  `logs/`, `*.log` and `*.hprof`; three superseded icon drafts, kept on disk because the shipping
  pair grew out of them; and **`*.nam`, `*.wav` and `*.ir`**, which is the one entry that is a rule
  rather than tidiness — a capture, an impulse response and a recording each carry someone's
  copyright, the recorder's destination is settable to anywhere, and this is what stops a
  `git add -A` from ever committing one.

  Checked rather than assumed: `git ls-files --others --exclude-standard` against the initialised
  repository resolves to **133 files, 1.7 MB**, with no absolute path from this machine in any of
  them.

### Changed — small accuracy fixes the first public read would have found

- `known-issues.md` §5 said there was "no record" of the JNA-off-the-classpath degradation test, and
  `WhatsDone.md` said it had been run. Both were half right and together they read as a contradiction:
  it was run, and nobody wrote down against which version. §5 now says that, and asks the next run to
  be dated.
- CLAUDE.md said 303 tests; there are 304.
- `settings.gradle.kts` had no newline at the end of it.

---

## [1.0.59-alpha] — 2026-08-17

### Fixed — Record never starts the metronome, in any circumstance

1.0.58 stopped the count-in leaving the metronome running for the whole take. That was the wrong fix
to the right complaint: it still meant **pressing Record produced a bar of clicking from a metronome
that was not playing**, which nobody asked for. Record is not Play.

A count-in now applies **only while the transport is already running**. `armForRecord` returns false
against a stopped transport and Record does what it has always done — opens the file immediately,
silent. The behaviour that existed before the rhythm section shipped is the behaviour a stopped
transport gets back.

`bars` is now honoured in the case that remains, which it was not before: armed while playing, one
bar opens the file on the next bar line and two on the one after — a full bar of lead-in, which is
what the ⋮ sub-option promises. Previously the playing case ignored the count entirely.

### Changed — the transport has two states, not three

`COUNTING_IN` existed for a count-in that had started the transport itself. Nothing does that now, so
counting in is simply playing with a take armed, and the state had nothing left to represent. Gone,
along with `stopAfterCountIn`, `awaitingDownbeat` and `countInPattern` — the last of which existed
only to make a self-started count-in tick a plain click regardless of the selected feel.

The ⋮ menu says the rule outright: *"Only while the metronome is playing. Record never starts it."*

### Testing

The test that pinned the old behaviour is replaced by one that pins its absence: arming a stopped
transport is refused, and four seconds of rendering afterwards are **exactly silent**. The two-bar
option is now tested by difference — armed mid-bar, two bars cues exactly one bar later than one —
which is a claim about the option's meaning rather than about an absolute timestamp.

---

## [1.0.58-alpha] — 2026-08-17

### Fixed — pressing Record started the metronome and left it running

With count-in enabled and the transport stopped, Record started the transport to have something to
count with — and then kept it running for the whole take. Reported as a bug, and it was one:
**pressing Record is not pressing Play.** The click was audible in the monitor for the length of the
performance, and would have been written into the " - drums" copy as well.

A count-in now *borrows* the transport and gives it back. Armed from a standing start it runs for the
count and stops on the downbeat the file opens on; armed while the transport is already playing it
changes nothing, which is right for the mirror reason — it was running before Record was pressed and
nothing here should stop it.

Ordering detail worth keeping: `stop()` clears the record cue, so the cue is now raised *after* the
stop rather than before it. Reversed, the count-in would fire correctly and then silently discard the
handover, and no take would ever start.

### Fixed — a count-in kept a duplicate " - drums" copy

Consequence of the same code path. The twin is deleted when the take closes if the metronome never
played, and the check was being satisfied by the count-in's own clicks — which are not in the file,
since recording begins on the downbeat *after* them. A count-in with the metronome otherwise off
therefore left two byte-identical files. The flag is reset at the handover, which is the moment the
file actually starts.

### Testing

Three more, 305 in total. The one that matters asserts on the audio rather than on the state: after a
one-bar count-in at 120 BPM, everything past 2.6 s is exactly silent. A state check alone would have
passed on an implementation that stopped the transport but left a voice ringing.

---

## [1.0.57-alpha] — 2026-08-17

### Changed — a module's controls now dissolve when the set of them changes

Switching an FX slot's type replaced one set of knobs with a different set, of a different count, in
a single frame. Expanding did the same to the four knobs it kept: they jumped position the instant
the row was rebuilt, and 1.0.56's width animation grew the pedal *around* that jump rather than
hiding it.

`EffectPanel.transitionKnobs` replaces both. Three phases, and the order is the design:

1. **The old knobs fade out** at the current width. Nothing moves.
2. **They are swapped** while nothing is visible. This is the only frame in which controls appear,
   vanish or move, and it happens behind a fully transparent row — which is what makes a change of
   *count* and a change of *arrangement* cost the same as a change of one label.
3. **The pedal resizes as the new ones fade in.** It grows or shrinks while empty, so nothing is ever
   clipped mid-flight and the new controls arrive into a pedal that already fits them.

**This lives in the module system, not in EQ or in `FxSlot`.** Anything whose
`AudioProcessor.parameters` can change under it calls `transitionKnobs()` instead of `rebuildKnobs()`
and inherits the behaviour — the two callers today, and whatever a later module needs it for.

**The effect's own state is not deferred.** An FX slot changes type the instant the menu item is
clicked, because that is a change to what the audio thread is running; delaying it by even a tenth of
a second would put the sound behind the click. Only the picture waits. A frame captured 40 ms into a
Transient → Octave change shows the header already reading OCTAVE while the old ATTACK and SUSTAIN
knobs are still fading.

Width eases in and out; **alpha does not**. An eased fade-in starts almost flat, which held the pedal
visibly empty for an extra beat after the swap — the one moment in the transition with nothing to
look at, and so the one that should be shortest. Linear brings the controls back promptly while the
resize keeps its curve.

70 ms out, 130 ms back. The shorter half is the fade, because what it hides is a single frame.

### Added — `EffectPanel.FadeRow`

The alpha is applied in `paintChildren` on the row rather than per control, so it works for whatever
a module puts in there without that component knowing anything about it. Left non-opaque, so the
pedal's own background is repainted underneath and the knobs fade towards the pedal rather than
towards a fill of the row's own.

### Note on 1.0.56

The clip-reveal that version introduced — knobs built first and uncovered as the pedal grew — is
gone, replaced by the dissolve. `SingleRowLayout` stays and is still load-bearing: the row is still
wider than the panel during the early frames of phase 3, and a wrapping row would still fold it.

---

## [1.0.56-alpha] — 2026-08-17

### Added — expanding a module animates its width

The `<>` button on EQ and on any other module that hides controls used to change the pedal's width in
a single frame. It now eases in and out over 180 ms.

**The order of the two halves is what makes it a reveal rather than a redraw**, and it is opposite
for the two directions:

- **Expanding** builds the knobs *first* and then grows the pedal to uncover them.
- **Collapsing** narrows the pedal *first* and discards the surplus knobs once they are already out
  of sight. Removing them up front would leave a visibly empty pedal shrinking around nothing.

Cubic ease-in-out, because a linear resize looks mechanical — nothing physical starts and stops
instantly. 180 ms is short enough that a second click never waits on the first; much below 120 ms the
eye reads a jump with a smear, and much above 250 ms a control used repeatedly starts to feel slow.

### Added — `SingleRowLayout`, because a half-grown pedal folded its knobs

The knob row was a `FlowLayout`, which was correct as long as a pedal only ever appeared at its final
width — it is always sized to fit its knobs exactly, so the wrap could never fire. Animating makes
the panel genuinely narrower than its contents for a fifth of a second, and a wrapping row answers
that by starting a second line inside a fixed-height pedal, where the knobs are cut in half.

The new layout never wraps and centres what it has, so the overflow is split between both edges and
the parent clips it. Knobs appear from the left and right together as the pedal widens about its own
centre. A frame captured 70 ms into an expand shows exactly that: the middle five of the EQ's nine
knobs visible, the outer four clipped at the edges.

`applyPreferredSize` is suspended while the tween runs. Without that, `rebuildKnobs` — which every
expand goes through — would set the final width itself on the frame the knobs are built, and there
would be nothing left to animate.

### Known limit

When the board is full enough that the wider pedal no longer fits its row, it still **jumps to the
next row part-way through the animation** rather than at the start. The wrap is recomputed each frame
from the current width, so it changes at the moment the pedal crosses the threshold. Making it happen
once, up front, would mean the board wrapping against the pedal's target width for the duration.

---

## [1.0.55-alpha] — 2026-08-17

### Fixed — pressing Start rearranged the top bar

The device combos changed width the moment a stream opened, and since the bar lays out left to
right, everything after them slid sideways.

The cause is a side effect of how the pickers are locked. `setDeviceControlsEnabled` collapses each
model to its single current value rather than disabling the control — a deliberate choice, because
Nimbus paints a disabled combo's value through its own `SynthStyle` and all four pickers go visibly
blank at exactly the moment you most want to read which device is live. What it overlooked is that
**a `JComboBox` measures itself against every item in its model, not against the selected one**.
Collapsing the list therefore resizes the control to whatever that one value happens to need.

`inputCombo` and `outputCombo` never showed it, which is why it survived this long: they are pinned
to 190 px for an unrelated reason — a long device name would otherwise set the width of the whole
window. The other three had nothing holding them still.

Each combo's own preferred size is now captured before the collapse and restored after it, with null
recorded where a combo had none of its own so sizing goes back to the model rather than being frozen
at the value that happened to be showing.

Measured across a start/stop cycle by sampling the window's pixels: the five combos span 145, 190,
190, 95 and 166 px, identical before, during and after. The Start button still changes label, which
moves nothing — the Rec button beside it holds the same position throughout.

One note for the next person measuring this way: an *unlit* status lamp is close enough to the panel
colour to split a button's pixel run in two, so a button can appear to change width when only its
lamp has come on. That artifact is what the Rec button's fixed position was used to rule out.

---

## [1.0.54-alpha] — 2026-08-17

### Fixed — the tempo field was shorter than the buttons beside it

A `JTextField` takes its height from its font and its insets, which is a different number from the
30 px every other control on those bars uses. On a `FlowLayout` row it is perfectly happy to be short,
and a control a few pixels under its neighbours reads as misaligned long before anyone works out
which one is wrong.

`DarkTextField.style` now takes an optional height and pins preferred, minimum and maximum to it. The
tempo field passes `PushButton.HEIGHT`, which is made public for the purpose rather than copied — two
controls sized from two copies of the same number stay level only until one of them is adjusted.

The border becomes symmetric top and bottom. `FieldView` centres a single line vertically inside
whatever room it is given, so the insets only have to keep the text clear of the accent rule and the
centring puts it where the eye expects it in the taller box.

Verified by sampling the pixels rather than by eye: both controls now span y=133..162 in the window,
30 rows, same top and bottom edge. Worth recording that the zoomed screenshot was misleading — the
accent rule immediately under the field's top border makes it look like it starts higher than the
button next to it when the two are in fact flush.

---

## [1.0.53-alpha] — 2026-08-17

### Changed — the rhythm row's ⋮ is now the same control the pedals use

1.0.51 sized it to match TAP, which was the wrong fix to the right complaint. `⋮` is not a label,
and putting it in a bordered button treats it as one: it came out looking like a third labelled
action beside TAP and PLAY, when what it is is a glyph you recognise rather than read.

It is now `EffectPanel.MenuButton` — the identical control on the NAM and IR headers. A bare stroked
mark, no fill and no border, which is a distinction this window already draws and which the new row
had simply not joined.

Reusing the component rather than copying its paint also moves the popup's construction into it, so
the rhythm menu now goes through `DarkMenu.popup()` by the same route the pedals' menus do and
cannot drift out of step with them the way it did when it built its own. `showRhythmMenu()` becomes
`buildRhythmMenu(menu)`, matching `buildNamMenu`.

---

## [1.0.52-alpha] — 2026-08-17

### Fixed — "Two bars instead of one" stayed locked after count-in was switched on

Switching count-in on left its length sub-option greyed until the menu was closed and opened again.
A menu is a single interaction, and a control that unlocks only after leaving and coming back reads
as broken rather than as delayed.

The cause is the one the logging note in this same file already carries a comment about: rows are
built when the popup opens, so every one of them is a snapshot of the state at that moment. Anything
depending on a *sibling* row is therefore wrong from the first click onwards.

`DarkMenu.setRowEnabled` updates a built row in place, and the count-in row calls it. It greys the
label as well as the switch — disabling the switch alone leaves a row that reads as available and
does not respond, which is worse than one that reads as unavailable.

Verified by driving the running application: the same open menu, count-in clicked off and then on,
with the sub-option greying and un-greying between the two frames and no reopening in between.

---

## [1.0.51-alpha] — 2026-08-17

### Fixed — the rhythm row did not look like the rest of the window

Four faults, all of them the new row failing to opt into something the window already had. Verified
by screenshot rather than by reading the code, which is how three of the four were confirmed fixed
and how the first was confirmed to exist.

- **SIG and FEEL were Nimbus silver** beside four gunmetal combos on the bar above. They were simply
  missing from the `DarkComboBoxUI.install(...)` call — the app's combo styling is applied per
  instance, not through a UIManager key, so a new combo gets the platform look until it is named.
- **The ⋮ button was 30 px wide** against Tap's 52, which made three buttons in a row three sizes.
  Now the same minimum as Tap.
- **The ⋮ menu showed grey around its edges.** It was built on a bare `JPopupMenu` rather than
  `DarkMenu.popup()`. The popup paints its own background behind the content panel, so an unstyled
  one shows through everywhere the panel does not reach.
- **The tempo field had a black bar across its top.** That is Nimbus's inner shadow, drawn for a
  light theme where it reads as a bevel; on this one it reads as a defect. `setBackground` cannot
  reach it, because Nimbus paints text fields through a painter in its defaults rather than from the
  component's own colours.

### Added — `DarkTextField`

The bar is replaced rather than removed: a two-pixel accent rule along the top, brighter when the
field has focus. Same idea as the meter's peak line — a thin accent mark is how the rest of this
window says a thing is live.

Done with `Nimbus.Overrides` and `Nimbus.Overrides.InheritDefaults = false`, which is the supported
way to swap a painter for one component. Without the second property the override is merged over the
stock painters and the shadow survives it.

### Added — clicking away from a text field commits it, exactly as Enter does

Swing's default is that a text field keeps focus until something else takes it, and most of this
window is unfocusable custom-painted components. So clicking a knob, the board, or bare panel left a
field still focused, still holding a selection, and still uncommitted — the user had visibly finished
with it and the program had not noticed.

Installed once on the toolkit rather than per field, which is what makes it reach **every** text
field including the two built on demand: the knob's inline editor and the rename dialog's field.
A per-field listener would have to be remembered at each of those sites and at every future one.

It only moves focus and clears the selection; it commits nothing itself. Every text field here
already commits on `focusLost` — that is what Escape has to be distinguishable from — so moving focus
*is* pressing Enter, and firing the action as well would commit twice.

Two clicks are deliberately left alone: one landing on another text component, which is about to take
focus on its own, and one in a different window, so dismissing a combo popup or a menu does not
commit a field the user never touched.

---

## [1.0.50-alpha] — 2026-08-17

### Added — the kit, synthesised

Five voices, six drum patterns, and the click can now sit on top of them. This completes the feature
started in 1.0.49; the transport, the bar row, count-in and the twin recording were already there and
are unchanged.

**Nothing is sampled, and that is a licence decision before it is a technical one.** A drum kit
shipped as audio is an asset with its own copyright, in a program that is GPLv3-or-later with exactly
one third-party runtime dependency — the same reasoning that keeps NAM captures and impulse responses
out of the distribution and leaves only loaders behind. An oscillator has no author.

It is also the right sound for the job. These are analogue-drum-machine voices rather than imitations
of a recorded kit, and against a loud guitar amp a drum machine cuts through where a sampled room mic
turns to mud.

| Voice | How |
| --- | --- |
| Kick | Sine swept 150 → 48 Hz over 25 ms, under a 420 ms amplitude decay |
| Snare | Two detuned tones at 185 and 330 Hz, plus noise banded 700 Hz – 7 kHz and decaying slower |
| Rim | The same ingredients as a snare, over in a fortieth of the time |
| Hat, closed / open | Noise above 7.5 kHz at 24 dB/oct; 45 ms and 280 ms |

The kick's whole character is the sweep. A sine at a fixed 50 Hz is a test tone; the same sine
started at 150 and dropped to 50 is a kick drum, because a falling pitch is what the ear reads as a
beater striking a head under tension. The hats need **two** cascaded high-passes — one leaves enough
low-mid to sound like a burst of static rather than like metal, which is the same arithmetic that
makes a single pole a poor detector filter elsewhere in this program.

### Added — a shared sine table, because the kick sweeps

`Tick` uses the coupled recurrence `y[n] = 2·cos(w)·y[n-1] − y[n-2]`, which is one multiply and one
subtract — and which only works because a metronome tick's pitch never moves. A swept voice cannot
use it: the coefficient *is* the frequency, and recomputing it per sample would put a transcendental
back in the loop the recurrence exists to keep it out of.

So the pitched drum voices read a 4 096-entry table with linear interpolation — two loads, a multiply
and an add, worst-case distortion around −80 dB, which is far below anything audible under a 40 ms
envelope. `Transport.prepare` touches the table deliberately, from a thread allowed to allocate;
first use from inside a callback would be class loading against the deadline.

### Added — six patterns, written as the grids they are

Rock, Half-time and Shuffle in 4/4; Waltz in 3/4; Ballad in 6/8; Slow blues in 12/8. Each lane is one
character per step — `X` accented, `x` normal, `o` soft, `.` silent — so the source reads as the
thing it produces:

```kotlin
groove("Rock", sig, 2, 0f,
    Voice.HAT_CLOSED to "xoxoxox.",
    Voice.HAT_OPEN   to ".......o",
    Voice.SNARE      to "..X...X.",
    Voice.KICK       to "X...X...",
)
```

That matters more than it sounds: a pattern written as nested lists of step indices is one nobody can
check by eye, and these are checked by eye or not at all.

**Shuffle is byte-for-byte Rock with `swing = 0.33`**, which is the point worth seeing in the source
— a shuffle is not a different pattern, it is the same pattern played unevenly. The swing field has
been in `Pattern` since 1.0.49 with every pattern using zero, specifically so this would need no
format change. Swing lengthens the on-beat and shortens the off-beat by the same amount, so the pulse
never moves; a shuffle that dragged the beat would be a tempo change rather than a feel.

**2/4 gets no drum pattern.** A march is a click on 1 and 2 with a snare on the 2, which the click
styles already are, and inventing a polka to fill the row would be padding.

Ballad and Waltz use the rim rather than the snare: a full backbeat at that tempo flattens the part
it is under.

### Added — the click over a kit

`clickOverDrums` was wired in 1.0.49 and inert, because every pattern was a click. It now does
something, and it needs no second scheduler — a click over a kit is a tick on every pulse, and the
pulse is already the moment `stepInBeat` returns to zero. It suppresses itself when the selected feel
is a click, where it would be the same tick fired twice at the same instant. The ⋮ row unlocks by
itself as soon as a drum pattern is selected.

### Fixed — the rhythm bus clipped on its own

Found by the new test rather than by ear, and it is why the voices were built before the numbers were
trusted. A kick, a hat and a layered click landing on the same downbeat are three oscillators that
all start from phase zero, so their peaks really do line up: **the 3/4 Waltz reached 1.71 at full
level** with nothing else in the buffer.

The fix is one bus trim rather than five smaller voice gains. The voice gains set the balance
*between* the instruments; the trim sets where the group as a whole sits, which is how a mixer works
and what stops a change to one drum's loudness becoming a clipping bug in some other pattern that
happens to use it. At 0.40 the worst pattern in the catalogue peaks near 0.7, leaving the rest of the
buffer for the guitar — the rhythm is *added* to it and only then clamped, so a bus reaching full
scale alone would leave nothing for the instrument it is there to accompany.

### Testing

Nine more, 303 in total. Two are worth naming:

- **Every voice is identified by its brightness**, measured as the RMS of the signal's
  sample-to-sample difference over its own RMS. Differencing a sine at frequency *f* scales it by
  about `2π·f/r`, so a 60 Hz kick lands near 0.009 and an 8 kHz hat near 1.1 — two orders of
  magnitude, enough to tell them apart without writing a spectrum analyser. It exists because
  `Transport.voices` is indexed by `Voice` ordinal and nothing in the language enforces that the two
  agree: get it wrong and every pattern still plays perfectly in time with a kick coming out of the
  hi-hat.
- **The clipping guard walks every pattern**, not the one that failed.

`TransportTest`'s "every pattern is click-only" assertion is gone, as flagged when it was written —
it was a guard against a pattern naming a voice that did not exist yet, and it is replaced by one
that checks every named voice is in range and every velocity is inside (0, 1].

`--selftest --chain` now drives Rock with the click layered over it rather than a bare metronome:
the scheduler costs the same either way, and the voices are the part that adds up.

---

## [1.0.49-alpha] — 2026-08-17

### Added — a metronome, on a second bar row

Nothing in the app kept time, which is the one thing a practice tool is for. There is now a
transport: a tempo, a time signature, a feel, a level, and a Play button, on a second row under the
device bar.

**The click is a drum pattern with a click kit, not a separate feature.** Building a metronome and a
drum machine side by side would mean two tempo fields, two phase accumulators and no answer to what
happens when both run at different speeds. One clock underneath, and "Click" is simply one of the
patterns FEEL offers. The kit lands next; the format it will use is already here.

**SIG and FEEL are nested, not siblings.** FEEL's model is rebuilt whenever SIG changes, so the two
can never contradict each other — a waltz is not in the list unless the bar is a 3/4. The nesting is
worth the extra control because the two halves of the catalogue have opposite shapes: every click
style exists in every signature (six styles × five signatures would be thirty flat entries), while
every drum pattern belongs to exactly one.

Five signatures. **12/8 is in** — a proper four-dotted-quarter bar rather than 4/4 with swing pasted
over it — because that is where slow blues actually lives.

**Tempo is the pulse you tap your foot to, not always the quarter note.** In 4/4 they are the same;
in 6/8 they are not, and a musician who says a ballad is "at 70" means 70 dotted-quarter pulses.
Defining tempo as the quarter throughout would have been arithmetically consistent and would have
meant typing 105 to get a feel of 70. So the beat lamps show **two** in 6/8 and four in 12/8, which
is also the fastest way to see that the wrong SIG is selected.

### Added — the transport, and the first thing here that generates audio

`dsp/rhythm/` is deliberately not an `AudioProcessor` and not a member of `EffectChain`. Those model
a pedal: something the guitar passes *through*, that gets dragged around the board, that a preset
stores. A metronome is none of those, and putting it in the chain would send the click through the
overdrive and the reverb. It is mixed into the monitor path after the chain has run.

Three properties are load-bearing:

- **Time is counted in samples off the callback, never from a `Timer`.** At 130 BPM and 44.1 kHz a
  pulse is 20 353.846 samples, so the countdown is a `Double` and the remainder carries. Rounding
  each beat to a whole sample drifts about 0.85 samples a beat — 109 samples by the 130th, audible
  against a loop long before that. `TransportTest` asserts the 130th beat lands within **three**
  samples of where the arithmetic says, which passes the fractional implementation and fails every
  rounding one.
- **The tick oscillator is a coupled recurrence, not `sin()`.** `y[n] = 2·cos(w)·y[n-1] − y[n-2]` is
  one multiply and one subtract per sample against a transcendental of some tens of nanoseconds. The
  callback budget is already at 97.7 % with a NAM capture at 64 frames; a few tenths of a percent is
  not free there. Drift over a 40 ms burst is far below what the envelope does in the same time.
- **`mix` allocates nothing**, asserted as bytes over 20 000 blocks rather than as a timing, for the
  same reason `AudioEngine.render` is.

The warm-up now walks **every pattern in the catalogue**, not the selected one — FEEL is a dropdown
and a dropdown is changed while the stream runs, which is exactly the case an `FxSlot`'s
`alternates` exist for. It also forces `Feels`' initialiser, which builds every step table and is
allocation the callback must never see.

`--selftest --chain` drives the transport at its heaviest setting — 16ths at 240 BPM, silent — for
the reason CLAUDE.md records: that switch once drifted to four pedals and spent several versions
quietly measuring less than half the app. It runs **after** `engine.start`, because the warm-up
stops the transport in its `finally` and a version placed above that line printed that it was
running while measuring nothing.

### Added — count-in, and takes that begin on a bar line

With count-in on, Record does not open a file where you pressed it. If the transport is already
playing it waits for the next downbeat; if it is stopped it starts, plays a whole bar, and opens on
the one after. Either way a take begins on beat 1 however sloppily the button was hit, which is what
makes two takes line up in an editor.

It stays sample-accurate without the audio thread ever touching a filesystem. The UI opens the
`Recorder` up front — that is the part with a disk behind it — and parks it in
`AudioEngine.pendingRecorder`; the callback only flips a boolean, and the swap happens at the top of
the next block. That is up to one block late, 1.45 ms at 64 frames, against the 33 ms a UI poll
would have cost. Pressing Record again during a count-in abandons the take and deletes its files.

### Added — an optional " - drums" copy of a take

Off by default: a take holds the guitar and the click is monitor-only. On, a second file is written
beside the first with ` - drums` appended, carrying the same performance with the rhythm mixed in.

It needs no extra buffer. The dry write already sits immediately before where the rhythm is mixed,
so the two files differ only by which side of one call they are read on:

```
chain.process(output, frames)
recorder?.write(output, frames)         // dry take
rhythm.mix(output, frames)
rhythmRecorder?.write(output, frames)   // the twin
```

Both are written **before** the clamp, so the twin keeps the same 32-bit float headroom the dry take
has always had. Both are also written in the `catch (Throwable)` path, and that matters more than it
looks: the twin exists to line up with the dry take, and a fault that shortened one of them by a
block would put every sample after it permanently out of step with the other.

A twin recorded while the transport never played is byte-for-byte the dry take, so it is deleted
when the take closes rather than left as a confusingly-named duplicate.

### Added — tap tempo, beat lamps, and a ⋮ menu

**Tap** averages over the whole run of taps rather than the last gap — a single interval carries the
whole of one tap's timing error, and four taps roughly halve it. A gap over 2.5 s starts a fresh
count, because that is somebody returning to the button rather than a slow pulse.

**Beat lamps** are one per pulse, driven by a beat counter read at the same 30 Hz as the level meter.
The counter is what distinguishes "still on beat 2" from "beat 2 again a bar later" — at one pulse a
bar the beat index never changes, and a lamp keyed on it would light once and stay lit.

**⋮** holds the things set once rather than changed while playing: click over drum patterns, the
twin recording, and count-in with a one-or-two-bar sub-option. It is rebuilt on every open, which is
how the first row's locked state stays true.

`ToggleSwitch` gained a disabled state to serve that row, and it shows **locked in position** rather
than blank. When the selected feel is already a click you *are* hearing one, so the row reads as on
while refusing to be turned off. Greyed-and-off would say the click is silent while a click is
audibly playing, which gets reported as a bug rather than understood as a lock.

### Added — the rhythm settings live in the session

Tempo, signature, feel, level and the three ⋮ toggles are in `Session`, not `Preset`. A preset is a
*tone* and has to load unchanged on someone else's rig; 132 BPM is not part of a tone, it is where
you left the practice room. Stored as plain strings and numbers so `preset/` stays free of `dsp/`
for the same reason it is already free of `audio/` — a file format that names live classes breaks
the moment one of them is renamed. A feel that no longer exists falls back to the plain click.

Whether the transport was *playing* is deliberately not stored. Restoring a session puts the
controls back; it does not start making a noise before anything has been pressed.

### Layout

The second row is its own `BarLayout` panel rather than more children in the device row. Appending
would have put the tempo field beside BUFFER on a wide window and separated them only once it
narrowed — the layout would decide where "the device" ends and "the metronome" begins, and the
boundary would move as the window resized. Two panels means each row wraps within itself. `group()`
keeps a label and its control as one child, so wrapping can move FEEL and its dropdown together but
can never strand the word.

---

## [1.0.48-alpha] — 2026-08-17

### Fixed — the third-party licence notice named three of the four things in the jar

`org.jetbrains:annotations:13.0` ships inside the fat jar and was named nowhere in
`Licences.THIRD_PARTY` or in README's table. Nothing changes legally — it is Apache 2.0, confirmed by
reading the `<licenses>` block out of the POM inside the jar rather than from memory, so it is
GPLv3-compatible on exactly the same terms as the Kotlin standard library it arrives underneath. The
notice was simply incomplete, and GPLv3 §4 asks for it to be right.

The interesting part is **why it was missed**, because that is what determines the fix. It is
transitive: `kotlin-stdlib:2.1.20` depends on it and `build.gradle.kts` does not mention it. Every
way of auditing this that starts from the `dependencies` block — which is how a person checks —
cannot see it. `gradlew dependencies --configuration runtimeClasspath` can, and that distinction is
now written into both the notice and the README.

### Added — `LicencesTest`, which checks the notice against the jar rather than against the build file

Adding the missing line fixes today's list and does nothing about the next transitive dependency, so
the actual fix is the guard. `tasks.test` now passes the resolved runtime classpath to the tests as
`omega.runtimeClasspath`; the test opens each jar, reads its Maven coordinates from its own
`META-INF/maven/.../pom.properties`, and fails if the artifact is not named in `Licences.full()`.

Two properties of it were deliberate. It **does not hardcode the current list** — a test asserting
"expect Kotlin, annotations, JNA and jna-platform" passes forever and catches nothing, whereas this
one derives its expectations from the classpath and so updates them itself. And it **asserts the
classpath is non-empty first**, because the failure mode of the whole approach is the Gradle wiring
going away and leaving a test that sweeps an empty list and passes — the same
indistinguishable-from-perfect trap as the `--selftest` issue in `docs/known-issues.md` §1.

Verified by deliberately breaking it: with the new entry deleted from `Licences.kt` the build fails
and names `org.jetbrains:annotations`. A guard that has never been seen to fail is not a guard.

Four further assertions ride along: the JNA LGPL 2.1 election and the Steinberg-SDK statement are
both pinned as strings, since they are precisely the kind of thing a later cleanup removes; About is
checked to carry the copyright and the disclaimer that §4 wants on the program itself; and
`SOURCE_URL` is pinned to the exact placeholder that `docs/known-issues.md` §6 quotes, so the two cannot
drift apart while it is still unfixed.

### Changed — `docs/known-issues.md`

`Octave` is no longer listed as a defect. The user report that opened it — notes changing pitch
mid-decay at the low frets — has been traced to **a buzzing string**, not to the module. It moves to
the bottom of the file as §7, retitled "measured, never observed", with the severity claim and the
"reproduces the reported symptom exactly" conclusion removed.

The measurements stay, because they are a property of the code and not of the report: 17 % correct at
the shipped `TRACK 450` against a synthetic piezo note whose second harmonic sits +5.6 dB above the
fundamental. What is now open is whether any real pickup presents that +5.6 dB. The honest residue is
narrower than the old claim and is a settings question rather than a code one — `TRACK 450` is a poor
default for a low note. The three specced fixes remain, under an instruction not to build any of them
without a recording from the actual instrument.

Recorded rather than deleted because the mistake generalises: a synthetic model built to reproduce a
described symptom will reproduce it, and its doing so is then evidence of nothing.

Known issues renumbered accordingly; the annotations item is gone, fixed above.

---

## [1.0.47-alpha] — 2026-08-12

### Added — Octave, a seventh FX slot type

Nothing in the chain changed pitch, which is the one thing a riff built on an octave-down guitar
needs. `Octave` is a **divider**, not a shifter — the Boss OC-2 approach rather than the Whammy one —
and that choice is the whole of its cost profile. A shifter needs a window, an FFT or a pair of
crossfaded delay taps: buffers to size, tens of milliseconds of latency, and per-block work that has
to be fitted inside a 1.45 ms deadline. A divider is a comparator and a flip-flop. It reads one
sample and writes one sample, so it adds **zero latency**, allocates nothing, and derives every
coefficient from the rate handed to `prepare`.

A Schmitt trigger watches a low-passed copy of the input — TRACK sets that filter, whose only job is
to remove the harmonics that would put extra zero crossings inside one cycle — and every rising edge
toggles a flip-flop. One flip per input cycle is one square cycle per two, so the division is exact
by construction rather than estimated. The square is scaled by a peak follower on the *unfiltered*
input, at `1/√2` of the peak, which is where a square and a sine of the same peak carry equal RMS and
therefore where MIX behaves as a blend rather than as a level control. TONE low-passes the result and
a 15 Hz high-pass removes the DC a frozen flip-flop under a decaying envelope would otherwise leave.
The blend is equal-power, because the sub is at a different frequency from the dry signal and a
linear crossfade of two uncorrelated signals dips in the middle of the knob.

MIX defaults to **0**, so a slot just switched to Octave is bit-transparent until the knob is moved —
asserted with `assertContentEquals`, not a tolerance.

Two limits are designed in and documented as characteristics. It is **monophonic**: a chord's
fundamentals interleave their crossings and the flip-flop divides whatever it last saw. A real OC-2
does the same for the same reason. And it **needs a threshold**, because a comparator with nothing to
compare chatters on room noise and on the tail of a decaying note, which is heard as the octave
warbling about a semitone. Below THRESH the flip-flop freezes and the sub fades out; a `Gate` in
front of it leaves THRESH deciding only when the *note* has finished.

`Biquad` gains `lowPass`, which it had never had: the app's only shared section shapes were a
high-pass and three shelves, and a one-pole is not enough to tame a square.

**Measured headlessly**, one DFT bin per frequency over the back half of a 0.5 s buffer, 0.8
amplitude in, fully wet:

| In | Out at f/2 | Out at f | Out at 3f/2 |
| ---: | ---: | ---: | ---: |
| 82.4 Hz | 0.613 | 0.016 | 0.220 |
| 110 Hz | 0.636 | 0.014 | 0.213 |
| 220 Hz | 0.654 | 0.006 | 0.217 |
| 440 Hz | 0.657 | 0.000 | 0.182 |

The third column is the point: a square at f/2 has odd harmonics only, so there is nothing at f for
it to put there, and what is left at the input frequency is 2 % of the sub. The fourth is the proof
it really is a square — a third harmonic at 0.33 of the fundamental, against the 1/3 the series says.
Repeated at 96 kHz the numbers agree to three decimal places (0.6128 against 0.6125 at 82.4 Hz),
which is the standing guard against the hardcoded-44100 bug. With a second and third harmonic added
to the input the sub still dominates its own fundamental by better than twenty to one across
61.7-330 Hz.

Nine tests: bit-transparency at the default, the division at 44.1 and 96 kHz, a harmonic-rich note,
silence in/silence out, shut below the threshold and open 30 dB above it, bounded at every extreme,
and — the one that pins the latency claim — the same input processed in one call and in 256-frame
blocks must come back bit for bit identical, which only holds for a module that looks no further
ahead than the sample it is writing. `Biquad.lowPass` is checked against its own order: unity a
quarter of the way down, 0.707 at the corner, 0.243 an octave above it.

`Octave` is appended to the **end** of `FxSlot.TYPES`, which is the only place it may go — a preset
stores the selection by name in its `fx` list and incidentally as the `Type` parameter's positional
index, and reordering the list makes those two disagree.

---

## [1.0.46-alpha] — 2026-08-12

### Fixed — Rescan says "Scanning…" instead of freezing the window

The request was a status line. Measuring first showed the status line could not have worked on its
own: `refreshBackends()` ran on the event thread, so a `setStatus` before the work would never have
been painted — the window froze, then showed the outcome, which is the symptom being complained
about.

**Measured on this machine, in process, once** (a JDK 21 harness calling `Backends` directly against
the 1.0.45 fat jar, timing each phase with `System.nanoTime`): a rescan takes **~1.4 s**. It breaks
down as `Backends.rescan()` 3 ms, `Backends.preferred()` 718 ms, `devices()` on the chosen backend
696 ms, `capabilities()` under 1 ms. Both of the big numbers are the same thing —
`AsioBackend.probeAll()` opens, `init`s and releases every registered ASIO driver, and does it in
full on every `devices()` call, which the refresh made twice. For comparison on the same run:
WASAPI exclusive 13 ms, WASAPI shared 4 ms, Java Sound 185 ms. Cold startup enumeration was 973 ms.

So the fix is the one `engine.start` already uses. `refreshBackends()` splits into `scan()`, which
touches no Swing and runs on a named `omega-rescan` daemon thread, and `applyScan()`, which is event
thread only and marshalled back through `invokeLater`. `refreshDevices()` takes the device list the
scan already paid for rather than enumerating a second time. The status line reads
`Scanning for audio devices…` for the duration and is then replaced by the outcome — the device
counts and driver name that were already written there, or the reason Start is unavailable.

Rescan and Start are both disabled while a scan is in flight and both come back when it lands: two
quick clicks would otherwise overlap two scans, and a stream started mid-scan would have had the
combo models replaced underneath it. The scan is still blocked outright while a stream runs, which is
what keeps enumeration away from drivers that refuse a second `init`. The duration and what was found
go to the log, since "what the machine did" is the useful kind of line.

The startup path is deliberately **unchanged and still synchronous**: `refreshBackends(prefer = …)`
now means `applyScan(scan(prefer))` on the event thread, so `restoreDevices()` on the next line still
finds the combos populated. Session restore was the thing most easily broken by this change and it
never becomes asynchronous.

---

## [1.0.45-alpha] — 2026-08-12

### Fixed — Rescan now discards what the app had cached about your devices

Reported: a 44.1 kHz-only interface on the input, a 48 kHz output, and the correct warning that the
two share no sample rate. The user did the right thing — changed the output to 44.1 kHz in Windows
Sound settings, came back, pressed **Rescan**, re-picked the output — and got **the same error**.
Only closing and relaunching the app made it notice. The advice the app gives was sound; the app
then refused to look again.

The cause is one missing line rather than a wrong one. `WasapiBackend` caches its capability probe
per device *and* rate, because finding out which buffer sizes an endpoint really accepts means
opening it exclusively and calling `Initialize` for real, four times, which is slow and disruptive to
do on every combo repaint. That cache was correct and stays. What it never had was an expiry: the
one backend instance lives for the process, so the first probe of an endpoint was the last word on
it, and a rate the user had since changed in Windows was invisible until restart.

`AudioBackend` gains `rescan()` with a **default empty body**, so it costs nothing for a backend that
holds nothing. `WasapiBackend` implements it by dropping every probe under its existing lock;
`Backends.rescan()` invalidates **every loaded backend, not just the selected one**, because the
stale probe is otherwise one click away — rescan on WASAPI, switch the driver combo to ASIO, same bug
again. `MainWindow.refreshBackends()` invalidates before re-enumerating, which is what the Rescan
button now means.

Checked, not assumed, for the other two backends. **ASIO already re-probes**: `probeAll()` clears its
map and rebuilds it from the drivers on every `devices()` call, merging nothing and skipping nothing,
so its hook stays a no-op — the one time entries survive is under a running stream, where
enumerating would mean re-opening drivers that refuse a second `init`. **Java Sound holds nothing**:
it reads the mixer list from `AudioSystem` on each call and its capabilities are a pure function of
`AudioConfig`. Its own limitation is below us and unchanged — Java Sound's device enumeration does
not see hot-plugged hardware, which ASIO and WASAPI do.

A rescan **while a stream is running defers** rather than clearing. The probe opens the device
exclusively, so `capabilities()` deliberately returns a generic fallback while streaming; clearing
mid-stream would leave the buffer picker showing sizes belonging to no device on this machine until
Stop. The note is redeemed at close, which is the first moment a truthful answer can be fetched. In
practice the path is unreachable from the UI — Rescan is disabled for the life of a stream — and the
reported fault happens with the stream stopped, which is the path that is now fixed outright.

With logging on, a rescan records what the machine did rather than what the user clicked:

```
INFO  wasapi   WASAPI exclusive: rescan dropped 4 cached device probe(s); next query re-probes.
```

A rescan that drops several probes and still reports the same rate conflict means the device really
has not changed; one that drops nothing means the invalidation never reached that backend. Either
reading is worth more than the silence that was there before.

Four `BackendSeamTest` cases cover the seam: the default body is a genuine no-op, invalidation
reaches every backend in order, a backend that throws on invalidation does not stop the ones after it
(asserted against the registry's own loop, not a copy of it in the test), and invalidating the real
registry twice leaves it enumerable. WASAPI itself needs COM and a device, so it is still verified by
running the app.

---

## [1.0.44-alpha] — 2026-08-12

### Added — first run says where `config.json` is, and which key turns logging on

Logging is off by default and its switch lives in ☰ → Logging, which means the failure it would be
most valuable for — a launch that dies before there is a window — is the one failure it can never be
turned on for. The only way in is to edit the settings file by hand, and until now nothing had ever
told anyone where that file is.

The run that **creates** the file now prints its absolute path and the exact key to set:

```
Settings created: C:\Users\...\AppData\Roaming\Omega-Plugin\config\config.json
  If Omega-Plugin ever fails to start, its Logging switch is out of reach. Set
  "loggingEnabled": true in that file with any text editor and the next launch will
  record what happened in the logs folder beside it.
```

Once, on the run that creates it, and never again. A line printed every launch is a line nobody
reads by the third time, and the one occasion it mattered would scroll past with the rest.

### Fixed — the settings file is now written at first load, not at first change

It was created lazily, by the first setting somebody touched, which defeated the whole idea above:
an installation where nobody had picked a recording folder or flipped a switch had no `config.json`
at all, so a launch that failed left nothing on disk to edit. `Config.load()` now creates and flushes
the file when it finds none, **before** any of the startup that can fail — the notice is printed from
`main()` ahead of the window for the same reason.

The created file contains `"loggingEnabled": false` explicitly rather than being an empty `{}`. The
key somebody is being sent to change has to already be in front of them, spelled the way the program
spells it; nobody can be expected to guess a name out of an empty object.

Not printed by `--list-backends`, `--list-devices`, `--selftest` or `--measure`: none of them
constructs a `Config`, and none of them installs the log, so `loggingEnabled` has no effect on those
paths. Sending someone to flip a switch that does nothing to the command they just ran would be
worse than saying nothing.

Two `ConfigTest` cases cover it — a fresh folder produces a file containing the key, and the notice
fires on the creating run and stays quiet on the next.

---

## [1.0.43-alpha] — 2026-08-12

### Changed — loading a preset now rearranges the board instead of replacing it

A preset that reorders the chain, or that carries a different number of FX slots, used to hard-cut:
one frame the old board, the next frame the new one. The eye's hide/show reflow had been animated
since 1.0.39 and this was the obvious hole beside it — the same board, changed by a different
action, with none of the same continuity. Three separate paths, all now on the drag animation's
easing, so a preset load reads as the board *rearranging* rather than as a different board arriving.

- **A reorder slides.** Every pedal eases from where it was to where the preset puts it.
- **An arriving slot fades up in place** while its neighbours slide apart to make room. Deliberately
  no slide-in: a pedal that has never been on the board has no previous position, so a slide would
  be inventing one, and an invented slide is a claim about the chain that is not true.
- **A departing slot fades out where it stood** while the gap closes over it — the same treatment a
  pedal hidden by the eye already got.

### Three things learned by getting them wrong

**`revalidate()` is not enough to start an animation.** `setOrder` called it and nothing else, and
`revalidate` defers layout to the event queue. The 16 ms frame timer therefore fired first, found
every pedal already sitting on its stale slot, concluded nothing was moving and stopped the animator
before `doLayout` had run once. The slots have to be computed on the spot, which is what `beginDrag`
had been doing all along for the same reason.

**A departing pedal has to leave the chain immediately even though it leaves the screen slowly.**
`MainWindow.deleteSlot` and `syncSlots` call `EffectChain.replaceAll(allEffects())` on the next line,
and `allEffects()` reads the board's order, which reads its panel list. A pedal kept in that list
while it faded would be published straight back into the chain and go on processing audio after the
user deleted it. The component moves to a separate ghost list instead: out of the order, out of the
slots, still a child component, actually removed when its opacity hits zero.

**A ghost still takes mouse events.** During its ~150 ms fade it is a live component, so a press on
it reached `beginDrag`, where its index in the panel list is `-1` — a negative drag index with a
pedal marked as dragged, inconsistent for as long as the button was held. Its drag, edit and resize
callbacks are cleared the moment it becomes a ghost.

### Not fixed, and known

A membership change can shift where the rows wrap, so pedals sometimes glide diagonally from one row
to the next; and the board's preferred height changing mid-animation can flicker the scrollbar for a
few frames. Both are honest consequences of animating a reflowing layout rather than a fixed one.

This is view code only. `MainWindow.load()` stops the stream before it touches the board, so nothing
here runs anywhere near a callback, and neither `dsp/` nor `audio/` changed.

---

## [1.0.39-alpha] — 2026-08-12

### Added — an eye on the board that hides the pedals which are switched off

Top-left of the board, in the same bare-glyph style as the `✕` and the `+`: a lens with a pupil,
struck through while pedals are hidden. Struck means *hidden*, matching every other eye control
anyone has used — showing what pressing it would do instead reads correctly for exactly as long as it
takes to press it once.

**It is a view state and nothing else.** A hidden pedal is still in the chain, still in the preset,
still in the order — it is simply not drawn. And the only pedals that qualify are the ones
`EffectChain` already skips, so hiding one cannot change a sound. That is what makes this the one
board change that is safe while the stream runs, unlike adding, removing or reordering.

The reflow is animated by the easing the drag already used, so a pedal leaving and its neighbours
closing the gap are the same gesture over the same frames rather than two separate ones. A departing
pedal has no slot, so it fades where it stands while the rest slide; `EffectPanel.fade` is applied in
`paint` rather than `paintComponent`, because the knobs and the switch are child components and a
composite set in `paintComponent` gets painted straight over by them.

Three details that are not obvious until it is in front of you:

- **Cables run between what is shown.** The signal still passes through a hidden pedal, so a gap in
  the drawing would be a lie about the chain.
- **A pedal added while hiding is on is exempt from it.** An FX slot starts switched off, which is
  precisely the state being hidden, so pressing `+` would have added one and had it vanish on
  arrival — a button that appears to do nothing. The exemption ends the moment its own switch is
  touched, because what should happen to it is no longer ambiguous.
- **Dragging still works with pedals hidden.** Hit-testing counts the pedals on screen while the list
  being reordered still holds all of them, so a drop position is translated through the shown pedal
  that currently occupies it. A hidden pedal stays anchored to its neighbour rather than being
  stepped over silently.

The board reserves a strip along its top for the eye, and the eye is pinned to the viewport rather
than to the board — a control that scrolls off the top is one you have to go looking for to undo what
it just did.

The lens is 14 × 8 with a 5 px pupil — an almond rather than a slit, which is what "open" has to look
like at sixteen pixels.

Its two inset constants are deliberately unequal, and the reason is worth keeping: the component is
16 × 22 like every other header glyph so they all share a hit area, but the lens drawn inside is
nearly the full width and about half the height. Equal component offsets therefore put the eye hard
against the left edge. The height is also smaller than the drawing code looks — a quadratic Bézier
reaches only **half** the offset of its control point, so `EYE_LID` at 8 draws an apex 4 px above
centre. Both insets were set by measuring at 6× zoom rather than by arithmetic, which was wrong
twice, and both have to be measured again whenever the glyph's height changes.

---

## [1.0.42-alpha] — 2026-08-12

### Added — process CPU in the status bar, beside the DSP figure

`0.3 ms driver   DSP 12%   CPU 0.3%   0 dropouts`. Shown only while the stream runs, which is where
the transport line lives anyway.

**It is not the same measurement as DSP and the tooltip now says so outright.** DSP is the share of
one callback period the chain consumes and is what predicts a dropout; CPU is the share of *all*
cores the whole JVM uses, as Task Manager counts it. On a twelve-core machine a chain sitting at half
its deadline is about four per cent of the CPU, so the two legitimately differ by an order of
magnitude — and two numbers labelled like percentages that disagree that badly will be read as one of
them being broken unless the difference is spelled out.

**One decimal place, and that is not cosmetic.** At whole percent a healthy realtime audio process
reads `0%`, because it uses a fraction of one core out of twelve. A readout that says zero while the
application is working looks like a measurement that failed. Caught by running it rather than by
thinking about it — the first version shipped integer percent and read `CPU 0%` immediately.

### It costs nothing, which was the requirement

`getProcessCpuTime` deltas rather than `getProcessCpuLoad`, so the averaging window belongs to this
code and can be tested against a fake clock. The counter is read **once a second** however often the
display asks — the status line refreshes at about 3 Hz off a 30 Hz timer, and between windows the
last figure is returned unchanged. One `GetProcessTimes` per second, on the event thread, nowhere
near the audio thread.

Two failure modes are handled rather than assumed:

- **`com.sun.management.OperatingSystemMXBean` is HotSpot's extension, not a standard interface.** The
  cast is guarded and resolved once; a JVM without it loses the readout and nothing else.
- **The first sample has nothing to subtract from.** It reports *unknown* and the field is omitted,
  because a CPU figure of `0.0%` before any window has elapsed looks like an answer when it is not.

### Verified against something that is not itself

The application reported `CPU 0.3%` while an independent `Get-Process` measurement of the same PID
over a four-second window gave **0.36 %** of 12 cores. Nine unit tests cover the arithmetic against
an injected clock and counter: one saturated core of four reads as a quarter of the machine, an idle
process reads zero rather than unknown, a jumping counter cannot produce an impossible percentage,
and thirty repaints inside one second do not advance the window.

---

## [1.0.41-alpha] — 2026-08-12

### Fixed — the eye's lane was held at the top only, so the bottom row sat on the border

Reserving a strip for the eye is the top of the board's problem, so that is where the reservation
went — and the board centres its rows in whatever height it is given. The result was the entire
reservation above the pedals and none below: a clear margin along the top edge and the bottom row
pressed against the border.

The lane is now claimed at both ends and the rows centre in what is left. Measured by scanning pixel
rows rather than by eye, which had it wrong: **23 px above the first row and 23 px below the last**,
against 23 and 11 before.

---

## [1.0.40-alpha] — 2026-08-12

### Added — the eye's setting is remembered

`Config.hideDisabledModules`, beside the logging switch rather than in the session, and the
distinction is the point: this is a preference about *looking* at a board, not part of any sound. A
preset that carried it would impose one person's screen habits on everyone it was shared with, and
would change what you see every time you loaded one.

**It defaults to showing everything**, and that default matters more than the round trip. A board
that opened with pedals missing, on an installation whose owner had forgotten the setting existed,
reads as pedals that have been lost.

Restored before the change listener is attached, so putting the saved value back does not count as a
change and write itself out again.

---

## [1.0.38-alpha] — 2026-08-12

### Fixed — the warm-up cost grew with the buffer size, for no benefit

The pass ran `WARMUP_BLOCKS × bufferFrames` samples, so its cost was **linear in the device's block
size**. Measured on the Sonicake with a NAM capture loaded: 236 ms at 64 frames and **7286 ms at
2048** — 32 times the buffer, 31 times the wait, for identical benefit. Java Sound's 7056-frame line
extrapolates to roughly 25 seconds, which would read as a hang rather than as a start.

The mistake was tying the amount of work to the block size at all. Warm-up exists to get code
compiled, and JIT compiles methods and loop bodies; neither knows how many frames a call carried. The
pass now runs at a **fixed 64-frame block whatever the device asked for** — 64 because that is the
buffer the deadline work was done at, so the small-buffer case, which is the one that actually needs
warming, is unchanged — and finishes with four blocks at the real size, to first-touch the buffers
`prepare` has just allocated.

| Buffer | Before | After |
| ---: | ---: | ---: |
| 64 | 256 ms | 259 ms |
| 2048 | 7551 ms | 328 ms |

### Fixed — the full warm-up was repeated on every start

Class loading and JIT compilation are per class and last as long as the process, so every start
after the first was repeating work already done. `warmedClasses` records what has been through a
complete pass — statically, because that is the scope the property really has — and later starts run
a short pass instead, enough to first-touch new buffers and re-enter anything the collector
deoptimised.

**Second start at 64 frames: 26 ms, against 271 ms for the first.**

The set is only updated after a pass that *completed*. A warm-up that threw has proved nothing about
the classes it never reached.

### Changed — Start no longer freezes the window, and says what it is doing

`engine.start` ran on the event thread, so for the whole open the window could not repaint: no
progress was possible even in principle, and Windows was entitled to dim it. It now runs on a worker
thread, with everything after it marshalled back through `invokeLater`, and the status area shows
the stage:

```
Opening Sonicake USB Audio Device…
Warming up… 62 %
```

`AudioEngine.onStartProgress` reports a fraction where there is something real to count and a
**negative** one where there is not. That distinction is the honest part: the warm-up is a loop with
a known trip count, but a driver open is a handful of calls of unpredictable length and a percentage
across it would be invented.

A `starting` flag guards the window between "not stopped" and "not yet running" — without it a
second press would open a second stream on the same device, and under exclusive mode a device left
held can need a reboot.

### Worth recording — 64 frames with a NAM capture is on the edge

Eight seconds at 64 frames, capture loaded: realtime 1.0000, 0 xruns, 0 deadline misses — and a
**worst callback of 1417 µs against a 1451 µs period, 97.7 % of it**. It held, and it has almost
nothing left. The ASIO table's 0.15 ms at this buffer is from `--chain`, which has no capture in it.
**With NAM loaded the honest recommendation is 128 frames.**

---

## [1.0.37-alpha] — 2026-08-12

### Added — the pause behind the Start button is now measured, not guessed

`AudioEngine.lastStartTiming` breaks the open into its phases, logs one line, and `--selftest` prints
it. The pause is the one delay a user sees and cannot explain — `start` runs on the caller's thread,
which is the event thread, so the window is frozen for exactly this long.

It was added to answer "should we trim the warm-up", and the answer turned out to be no. Measured on
the Sonicake over ASIO at 64 frames, from the running application with a NAM capture loaded:

```
Start took 256 ms total: open 11, prepare 2, warm-up 236, reserve warm-up 6, start 1
```

**The reserve warm-up added in 1.0.35 is 6 ms of 256 — under 3 %.** The suspicion that FX slots had
made the pause noticeably worse was wrong, and would have cost a pointless tuning change to act on.

**92 % of it is the warm-up, and almost all of that is NAM.** The headless `--chain`, which cannot
load a capture, warms in 14 ms against this chain's 236. Corroborated by steady state: 52 % load with
the capture in, against roughly 10 % without.

Two things follow that were not visible before:

- The pause is **almost entirely a loop with a known iteration count**, so a genuine 0–100 % readout
  is computable rather than invented. What stops it being shown is only that `start` blocks the event
  thread, so nothing repaints.
- The **warm-up is re-run on every start**, and its purpose — class loading and JIT — is one-time per
  process. Every start after the first is paying 236 ms for work already done.

### Worth recording — 64 frames is much tighter with a NAM loaded than the table says

Same run: **worst callback 1251 µs against a 1451 µs period, 86 % of it.** Realtime held at 0.9997
with 0 xruns and 0 deadline misses, so it passed — but the ASIO table in `CLAUDE.md` reports 0.15 ms
at this buffer, and that figure is from `--chain`, which has no amp capture in it. **Those numbers
overstate the headroom for anyone running NAM.** 128 frames is the honest recommendation with a
capture loaded; 64 is for a chain without one.

---

## [1.0.36-alpha] — 2026-08-12

### Changed — the two new header controls are marks, not buttons

Both shipped as `HeaderButton`s, which was wrong and looked it. A `HeaderButton` is a *labelled*
action whose text carries information — LOAD, or the name of the cabinet currently in use — so
putting `+` inside that same rounded border gave a one-character mark the visual weight of a file
name, and two of them on one header made the pedal read as a toolbar.

They now match the `✕` that was already there: a bare stroked glyph, no fill and no border, dim until
the pointer is on it. `EffectPanel.HeaderIcon` is the shared base — hover behaviour, hit area and
stroke in one place — with `ClearButton` and the new `IconButton` on top of it. The hover colour is
the accent for both new marks and stays red for `✕`, which is the rule: red is for the one that
throws something away.

**The EQ's expander is now a pair of chevrons** rather than `+` and `−`: pointing apart to expand,
together to collapse. Horizontal because that is the direction the pedal actually grows — a module
with more knobs is wider, never taller — and reversed rather than swapped for a different symbol,
so the glyph itself says that pressing it again undoes what it just did.

Side effect worth having: an icon is 16 px against roughly 30 for a text button, so pedals carrying
these are narrower and the board wraps later.

---

## [1.0.35-alpha] — 2026-08-12

### Added — an EQ, and FX modules the player decides the contents of

Two gaps closed at once, both of them the same gap seen from different sides: there was no way to
shape frequency, and no way to add a module the fixed board did not already have.

**EQ.** A high-pass, a low shelf, two sweepable bells and a high shelf, built on a new `Biquad`
using the standard Audio EQ Cookbook derivations. Four knobs collapsed — LOW CUT, MID F, MID, HIGH —
and a `+` on the header that reveals the other five. Before this the only filter anywhere in the
signal path was `Overdrive`'s single-pole TONE, which meant the only way to touch the top end was to
run a distortion you did not want.

Every band exists at all times and a band at unity gain is skipped by a comparison. Expanding
constructs nothing, and it must not: the button can be pressed while the stream is running.

**FX slots.** A new kind of module that is not one effect but a *slot* — it holds an instance of
every type it offers, runs one, and switches between them from a dropdown on its header. `FX 1` is
permanent and sits after the cabinet; its `+` adds another beside it, and every slot after the first
carries an `✕` that removes it, asking first if there is anything in it to lose.

Six types to start: **Chorus**, **Tremolo**, **Transient**, **De-Ess**, and **Delay** and **Reverb**
as separate instances from the fixed ones, so a board can carry two delays at different points.

Three of the four new effects are recombinations of code that was already here — `Compressor`'s
envelope detector, `Delay`'s interpolating read head, the EQ's biquads. That is why these four and
not four others.

### Design notes worth keeping

**Slots are numbered, never named.** Naming a slot after what it holds falls over the moment someone
picks the same type twice. So the label is computed from board position and renumbers when the board
changes, and a preset records slots as an ordered `fx` list rather than a name-keyed map — position
is the identity. A preset written before this release has no `fx` key at all and correctly reads as
a board with no slots.

**Every type is built up front, and the warm-up now visits all of them.** Selecting a type happens
while the stream runs, so constructing an effect on that path would allocate and class-load against
a 1.45 ms deadline. `AudioProcessor.alternates` is how the warm-up reaches the unselected types;
they are deduplicated by class, because JIT compilation is per class, so a second slot holding the
same six types has already been paid for by the first.

**Changing type fades through silence** — about 12 ms each way — rather than swapping between
blocks, which would be a step discontinuity and therefore a click. Tested by configuring two types
to pass audio untouched and asserting no single-sample step exceeds 0.01.

**A slot remembers every type's knobs, not just the selected one's**, so dialling in a chorus,
switching to a tremolo to hear it, and switching back returns the chorus you had.

**Adding and removing modules needs the stream stopped**, the same rule the drag handle already
followed. `EffectChain.replaceAll` publishes a membership change atomically, but an arriving effect
must have been through `prepare` and the warm-up first.

### Fixed during development — the de-esser could not de-ess

The first version split the band with a second-order high-pass and took the remainder as
`input - band`. Measured at 1.6 times the corner, that remainder still held **0.9** of the input,
because a steep filter shifts phase hard around its corner and the subtraction does not cancel —
so ducking the band by 12 dB moved the total by under two. The test caught it before the module ever
ran.

The audio path now splits with a one-pole, whose remainder sums back to the input exactly, in phase
as well as magnitude. The *detector* keeps the second-order filter, where steepness is wanted and
reconstruction is owed nothing.

### Fixed during development — a pedal narrower than its own header

`EffectPanel` sized itself from the knob row alone, which had always been true and stopped being
true the moment a module could have a long header. An FX slot set to Transient has two knobs — 188
pixels — and a header carrying a title, a `✕`, a `+`, "TRANSIENT ▾" and a bypass switch, which does
not fit in 188. The title ran underneath the dropdown and the `✕` was pushed off the end of the
pedal, so the one control that removes the module could not be clicked.

The width is now the larger of the two rows, recomputed on anything that can change either — a knob
rebuild, a header button being added, and the title changing, since a slot renumbering from `FX 9`
to `FX 10` is wider than it was. Caught by running the application, not by a test; Swing layout
still is not unit-tested.

### Tests

245, up from 211. New coverage asserts against numbers worked out independently rather than against
what the code returns: a −12 dB bell reduces its own centre frequency to a linear 0.251; a 2 Hz
tremolo dips to silence exactly twice in one second; a 300 Hz low cut attenuates 50 Hz identically
at 44.1 and 96 kHz, which is the regression guard for the hardcoded-44100 bug; the transient shaper
lowers a note's onset while leaving its body within 10 %, which is the property a compressor cannot
have; and every module claiming to be transparent is asserted **bit**-transparent rather than close.

### Measured on the Sonicake, ASIO, 44.1 kHz

60 s each, `--selftest --chain --stress`, against the widened `--chain` — every module that runs
without a file, the EQ with all five bands active, and an FX slot holding a reverb.

| Buffer | Round trip | Worst callback | Period | Misses | xruns | Realtime | Verdict |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 8 | 5.17 ms | 0.34 ms | 0.18 ms | 2 | 0 | 1.0000 | **fails** — overran its period twice |
| 16 | 5.35 ms | 0.19 ms | 0.36 ms | 0 | 0 | 1.0001 | clean, but over half the period spent |
| 32 | 5.71 ms | 0.25 ms | 0.73 ms | 0 | 0 | 1.0000 | clean |
| 64 | 6.44 ms | 0.15 ms | 1.45 ms | 0 | 0 | 1.0000 | clean, with margin |

**16 frames now passes where it was previously marginal**, on a chain carrying more DSP than the one
that produced the old figure — so the added cost of the EQ and a slot is smaller than the run-to-run
variation between them. One 60 s run each; treat the difference as "not worse", not as an
improvement. 32 to 64 remains the recommendation: at 16 the worst callback is 53 % of the period
against 10 % at 64, and that margin is what absorbs a bad moment.

At 8 frames the failure mode differs from the one recorded previously — deadline misses rather than
starvation, with realtime holding at 1.0000. Same verdict either way.

### Not verified

The input was silent for these runs (no `--tone`, nothing played), so the `Compressor`'s
over-threshold branch — the one place a logarithm is taken per sample — was not exercised. Every
other cost in the chain is unconditional, so the figures are close to worst case but not exactly it.

---

## [1.0.34-alpha] — 2026-08-11

### Fixed — the note under the Logging switch was a snapshot

Moving the switch left the line beneath it reading the old state until the menu was closed and
reopened. So the switch said one thing and the sentence under it said the opposite — on the one row
whose entire job is to answer "is it recording right now" at a glance.

The cause is that the hamburger menu is **rebuilt on every open** — a deliberate choice, and the
right one, since the preset list and half the enabled states change while it is closed. But it means
anything read at build time is a snapshot, and a control that changes the state it is describing
while the menu is still up has to update its own row. The switch now sets the note's text in the
same handler, and the two strings come from one function so they cannot drift apart.

**The row is also sized for the longer of the two messages before the popup is shown.** A
`JPopupMenu` takes its size when it opens and does not grow while it is up, so a label laid out for
the shorter sentence would have clipped the other to an ellipsis the moment the switch moved —
trading a stale row for a truncated one.

### Verified

Drove the pointer at the built application: opened the menu, clicked the switch, captured the row,
clicked it again, captured again — all without reopening the menu. The note tracks the switch in
both directions and neither string is clipped. `config.json` read back `true` after the two clicks,
so the state landed where it should.

This also closes the gap left open in 1.0.33: the click path had been rendered but never run, and it
has now been exercised end to end.

---

## [1.0.33-alpha] — 2026-08-11

### Added — a troubleshooting log, switched from the menu

**☰ → Logging** is a switch, beside **Open Logs Folder**. On, it writes
`logs/omega.txt` in the settings folder — `%APPDATA%\Omega-Plugin\logs\` on Windows, alongside
`config/` and the presets. Off by default, and the setting is remembered across launches.

This exists for one situation: something is wrong on a machine nobody here can touch, and the only
evidence is what the user is willing to send. Every decision below follows from that.

**A switch, not a menu item.** The question asked at this row is "is it recording my problem right
now", which an item answers only by being clicked. `DarkMenu.switchRow` already existed for the NAM
rate control, so it looks like a second instance of something rather than a second idea. It is
deliberately not routed through `markEdited()`: this belongs to the installation, and flagging the
patch dirty would offer to save a diagnostic into a preset.

**Remembered across launches, in both directions.** The faults worth a log are the intermittent
ones, so "turn it on and use it for a few days" has to be possible; a switch that reset each launch
would collect nothing but first-minute logs.

**Off means nothing on disk** — not a file nobody writes to. The writer thread is not running and
the folder is not created. A diagnostic that costs something while idle is one people switch off and
then cannot switch on when it matters.

### The threading rule this had to respect

`Log` is in `util/`, under everything, and **is never called from the audio thread**. It allocates a
string, takes a queue slot, and has a disk behind it — every clause of the threading contract
applies to it with force. The calls added are on the EDT, on stream monitor threads, and on the
opening thread. Nothing was added to `AudioEngine.render` or to any backend callback, and the
allocation assertion in `AudioEngineTest` still passes unchanged.

A bounded queue and one `MIN_PRIORITY` daemon writer, so the caller never waits for a disk: a
stalled filesystem cannot freeze the EDT mid-repaint. A full queue drops lines and counts them, and
the count is written out — a log with a silent hole is worse than one that admits to it. This
narrows the damage if someone breaks the audio-thread rule later; it does not make it safe.

Flushed whenever the queue drains, because the interesting log is the one that ends in a crash.

### What is logged, and why each earns its place

Seventeen call sites, chosen so a submitted log answers the questions that actually get asked. Kept
deliberately few: a log nobody reads to the end is not evidence, so nothing was added that a reader
would have to scroll past to reach something that matters.

- **A banner**: version, OS, CPU count, JVM, and the collector — with a `WARN` when it is not
  generational ZGC. Three facts no user can be expected to report accurately, and nearly every
  "it crackles on my PC" turns on one of them.
- **Every backend and its availability reason.** The single most useful line in the file. "Why is
  there 200 ms of delay on this PC" is nearly always answered here.
- **Stream open, as a pair**: what was asked for, then what the driver granted — buffer, rate, and
  the reported round trip broken into input, output and adapter frames — with a `WARN` when they
  disagree. A driver quietly granting something else is a real failure mode here, and it stops being
  visible the moment those numbers are mirrored into the combos.
- **A health snapshot every 60 s while running, and one on stop**, with `realtimeRatio` first. It is
  rate-limited *and* gated on a counter having moved, so a clean hour costs no lines. It answers
  "it was fine and then it started crackling half an hour in", which a start line and a stop line
  cannot place in time.
- **Failures with their stack traces**: stream open, NAM and IR loads, preset saves, recording
  starts, and a session or a referenced capture that would not come back.
- **An uncaught-exception handler** installed before the window is built. Swing's default prints to
  a console that a double-clicked `OmegaPlugin.bat` does not have, so without this the one failure a
  log most needs to contain is the one with nothing left to record it.

`realtimeRatio` is printed to four places and put first on purpose. It catches what the other
counters miss — a collection stalls the callback thread *between* callbacks, so no body is slow,
`deadlineMisses` stays at zero, and the driver simply gets nothing. A log showing misses of 0 beside
a ratio of 0.97 is that failure, and reading it the other way round sends people to tune a buffer
size, which cannot help.

### Rotation

2 MB, keeping one previous file as `omega.prev.txt`. A log people are asked to send has to be
attachable, and keeping one generation means the run *before* the one that went wrong is still
there — often the one that shows the setting being changed.

### Verified

Ran the built application against a **sandboxed `APPDATA`** rather than the real settings folder, so
none of this touched existing presets or config.

With `loggingEnabled` pre-set to true and no menu interaction, the folder and file were created and
carried the banner and all four backends — the "still on next launch" half, proven without a click.
The process was then killed with `Stop-Process -Force`: the content was already on disk, which is
the flush-on-drain guarantee holding under the case it exists for. A second launch **appended**
rather than truncating, and — unplanned, since that run went out without the ZGC flags — produced
`WARN NOT running generational ZGC`, so the collector check fires for real and not just in a test.

With the setting absent, a full launch and shutdown created **no `logs` folder at all**.

The menu was opened by driving the pointer at the built application and screenshotting: the switch
row, its note and *Open Logs Folder* render inside the dark popup with no Nimbus grey behind them —
the failure mode `DarkMenu.content()` exists to prevent, and the only one a unit test cannot see.
Pixels were sampled to confirm the note is `TEXT_DIM` and not tinted.

211 tests pass, including 7 new ones for `Log` and 1 for the persisted setting.

Not verified: no audio was streamed, so the health-snapshot and stream-open lines have been read in
code and exercised by neither a real driver nor a real dropout. The switch was rendered but not
clicked — the click path is one method, and the persistence either side of it is under test, but
the click itself has not been run.

---

## [1.0.32-alpha] — 2026-08-11

### Added — a portable bundle that carries its own JVM

`gradlew.bat portableZip` produces
`build/distributions/OmegaPlugin-<version>-windows-x64-portable.zip`: the fat jar, two launchers, the
licences, and a jlinked Java 21 runtime. 36.9 MB zipped, 47.1 MB of runtime unpacked. Unzip and
double-click; nothing is installed and nothing is required to be present.

Both existing artifacts assume a Java 21 on the target machine's `PATH`, which is a large assumption
to hand someone who wants to plug a guitar in — and usually a wrong one, since the common install is
8, 11 or 17 and generational ZGC needs 21. The development machine here is a case in point: its
`java` is Corretto 11, and only Gradle's foojay-provisioned toolchain is 21.

**Modules.** `jdeps --print-module-deps` on the fat jar gives `java.base`, `java.desktop`,
`java.logging`, `java.management`. Two more are added by hand because jdeps reads bytecode references
and can see neither: **`jdk.unsupported`**, which JNA needs for its reflective `sun.misc.Unsafe`
access — omit it and the program starts, opens its window, and then loads no native backend at all,
which presents as "this PC only has Java Sound" rather than as a missing module — and
**`jdk.management`** for `getCurrentThreadAllocatedBytes`, which is how the allocation assertion is
measured. `jdk.localedata` and the crypto modules are deliberately out; the UI is English and nothing
opens a socket.

**Two launchers, because the program has two faces.** `OmegaPlugin.bat` is `javaw.exe` behind
`start`, so double-clicking leaves no console window behind the GUI. `omega.bat` is `java.exe` and
forwards its arguments, for `--list-backends`, `--selftest` and `--measure` — those print, and
printing into a window that closes on exit is the same as not printing. Both hardcode
`runtime\bin\`, which is the point: a stale Java 8 on `PATH` must not be able to win and fail
confusingly.

`build` does **not** depend on `portableZip`. jlink costs tens of seconds and this is a release
artifact, not something wanted on every compile.

### Fixed — diagnostics output garbled on a UTF-8 console

The backend listing separates a device from its latency with an en-dash. JDK 19+ takes
`stdout.encoding` from the console when attached to one and from the native encoding when redirected
— cp1252 here — so a lone `0x97` reached a `chcp 65001` console and rendered as `�`. Confirmed by
reading the raw bytes off a redirected stream rather than trusting what a shell displayed, since
PowerShell 5.1 does its own transcoding in the middle and would have hidden which end was wrong.

The portable `omega.bat` sets `chcp 65001` **and** `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`.
They are a pair: fixing only one swaps one mismatch for another, and setting both means redirecting
to a file and reading on screen agree. Scoped to that launcher rather than to `gcArgs`, because this
is a console-presentation problem and `gcArgs` is the realtime contract.

### Licensing — two open points this bundle makes sharper

Neither is new, but shipping something designed to be handed to people raises the stakes on both.

1. **`Licences.SOURCE_URL` is still a placeholder.** GPLv3 §6 wants a binary accompanied by its
   Corresponding Source, and a URL that does not resolve offers none. This bundle is the most
   handed-over artifact yet, so it is also the one most exposed by that.
2. **The bundled runtime is Eclipse Temurin 21, under GPLv2 with the Classpath Exception.** Choosing
   it over a proprietary JDK is what keeps this distributable: the Classpath Exception is why
   linking against it does not reach the application. jlink preserves Temurin's own notices in
   `runtime\legal\`, and `READ-ME-FIRST.txt` names it. Redistributing an OpenJDK binary carries its
   own source obligation under *its* licence, which is discharged by pointing at Adoptium's
   published sources — **not yet written down anywhere in the bundle.** Fix alongside `SOURCE_URL`.

### Verified

Built the zip, unpacked it to a clean directory, then emptied `JAVA_HOME` and stripped every
`jdk|jre|java|corretto` entry from `PATH` — confirmed `java` was no longer resolvable — and ran
`omega.bat --list-backends`. All four backends enumerated (ASIO, WASAPI exclusive, WASAPI shared,
Java Sound), which is the real test of `jdk.unsupported`, since JNA loading is what would have failed
silently. Startup reported **"Collector: generational ZGC. Good."**, so the launcher's flags reached
the JVM; that check is the reason a bundle running on the wrong collector cannot pass unnoticed. Exit
code 0, en-dashes rendering correctly.

The GUI was launched from the same stripped environment and ran twelve seconds with an "Omega-Plugin"
window and an empty stderr, which is what exercises `java.desktop`, Nimbus and ImageIO — none of
which `--list-backends` touches, so the headless check alone would not have caught a module missing
from that side.

Not verified: no audio was streamed from the bundle, and it has not been run on a second machine —
which is the only test that matters for the claim it makes, and the one that cannot be run from here.

---

## [1.0.31-alpha] — 2026-08-11

### Added — a Licences item, and the licence inside the artifacts

Ahead of publishing on GitHub. The *choice* of licence was already sound — GPLv3-or-later, every
`.kt` headered, JNA elected under LGPL 2.1 and the election written down, no Steinberg SDK. What was
missing was everything about **handing the program to someone else**.

**☰ → Licences** now shows the copyright, the licence, the warranty disclaimer, where the source is,
and every third-party component *with the reason it is permitted*. That last part matters: a bare
list of names does not show that anyone looked at the licences, and one of them is a dual licence
that had to be elected, so the reasons go in the dialog where a later cleanup cannot lose them.

It is its own menu entry rather than a paragraph inside About, because §4 asks for the notice to be
conspicuous and something you have to go looking for inside another dialog is not. About keeps the
short form — copyright, licence name, disclaimer — and points at the rest.

All of it comes from `ui/Licences.kt`, one object. These lines have to agree with `LICENSE`, with the
README's table and with each other, and the way that stops being true is by living in three files
that get edited separately.

### Fixed — the jar shipped without its own licence

GPLv3 §4 requires recipients be given a copy of the licence **along with the Program**, and a fat jar
handed over on its own *is* the whole program. `LICENSE` sat in the repository root, which the jar and
`distZip` never picked up — so the one artifact most likely to be attached to a release was the one
copy with no licence in it.

`LICENSE` and `README.md` now go into both. The licence lands at the **jar root, deliberately not
`META-INF/LICENSE`** — that path is already occupied by JNA's own notice, which arrives with its
`zipTree` and has to stay intact. Under `DuplicatesStrategy.EXCLUDE` one of the two would silently
have won, and which one is not something to leave to file ordering.

### Fixed — no warranty disclaimer on the running program

About named the licence but not the disclaimer §4 asks to be published conspicuously on each copy.
Both dialogs carry it now.

### Still needed before anything is published

`Licences.SOURCE_URL` is a **placeholder**. Under GPLv3 §6 a binary must be accompanied by its
Corresponding Source, and a URL that does not resolve offers none. It is deliberately the only
such string in the program, and the README's new *Source and redistribution* section names the same
file, so there is exactly one thing to change once the repository exists.

### Verified

Unpacked the built artifacts: the fat jar carries `LICENSE` (the GPLv3 text) at its root **and**
JNA's `META-INF/LICENSE`, `META-INF/LGPL2.1` and `META-INF/AL2.0` intact — neither displaced the
other. `distZip` carries `LICENSE` and `README.md` beside `bin/`. Both dialogs opened in the built
application and render in full; `MessageDialog` truncated at 8 lines with "… and N more", which is
right for a batch of failures and wrong for a licence, so the cap became a parameter. 203 tests pass.

---

## [1.0.30-alpha] — 2026-08-11

### Changed — recordings default to `$HOME` on Linux, not `~/Desktop`

Windows is unchanged: takes still land in `Desktop/Omega-Plugin`, still following the OneDrive
redirection that `user.home` knows nothing about.

Everywhere else the default is now `$HOME/Omega-Plugin`. A desktop folder is a convention of a
desktop environment rather than of the system — a headless box or a bare window manager has none, and
a localised install calls it `Escritorio` or `Skrivbord`. Since the recorder creates the folder it is
given, the old code would have invented an English `~/Desktop` that nothing else on the machine uses,
purely to satisfy an assumption that is only true on Windows.

Only the default moves. A destination chosen from the menu is unaffected and still persists.

### Verified

Not verified on Linux, because **nothing in this project has ever been run on Linux** — see open item
2 in `docs/WhatsDone.md`. Stating that plainly rather than implying otherwise: this is a change whose whole
effect is on a platform that has never executed a line of this program.

The Windows branch is the same logic it was, reached by a plain `os.name` test that returns before it,
and 203 tests pass. That is what protects the behaviour that *is* exercised — it is not a
substitute for running the new branch, which remains untested.

### Added — a ✕ to unload a capture or a cabinet

NAM and IR each gain a small ✕ at the far left of their header, immediately left of LOAD. It removes
the file the module has loaded and nothing else: **every knob stays exactly where it was**, so mix,
level and the rest are still dialled in for whatever goes in next.

**It is hidden until there is something to clear**, rather than shown greyed. A permanently disabled
✕ on a module that has never loaded anything is a control that spends most of its life explaining
that it does nothing; hidden, its presence *is* the statement that a file is loaded.

Left of LOAD on purpose. The discard is the smaller and rarer action, so it should be the one you
have to aim at — inboard of LOAD it would sit under the pointer on the way to the button you actually
wanted.

A **missing** file counts as something to clear, and this is the case that made the button worth
having: after a preset or session names a file that has since moved, the module holds a reference that
colours LOAD red and puts a sentence on the status line every time it loads. "I do not have that file
any more, stop asking" had no way to be said until now.

There is no confirmation dialog. The undo is the LOAD button sitting next to it, and a dialog in front
of a one-click action that is one click to reverse is friction pretending to be safety.

### Verified

No new tests — this is Swing, which this project does not unit-test. In the built application, from a
session naming a capture that exists and a cabinet that does not:

- Both modules showed the ✕; NAM's LOAD read `GOAT` normally and IR's read `4x12` in red.
- Clicking NAM's ✕ returned the button to `LOAD`, hid the ✕, greyed the `⋮` menu, left SIZE, LEVEL and
  MIX untouched, and reported *"Removed the amp from NAM. Its settings were kept."*
- Clicking IR's ✕ did the same for the missing cabinet — the red went with it, which is the point.

---

## [1.0.28-alpha] — 2026-08-10

### Added — it opens where you left it

Closing the application no longer throws away what was on screen. The next launch comes back with the
same sound, the same pedal order, the same switches, the same captures loaded, and the same driver,
devices, rate and buffer.

**The sound is remembered whether or not it was ever saved.** That is the point of the feature rather
than an edge case: a preset that was loaded and then modified comes back modified, with the `•` still
in the title bar; a sound that came from nowhere in particular comes back too. It is stored under a
`session` key in `config/config.json` — a `Session` is a `Preset` plus the audio setup, which is the
one thing a preset deliberately refuses to carry, because a *sound* has to load on a machine that has
never seen the interface it was made on and a *session* is the opposite half of that split.

Restoring is not routed through the preset-load path, and that is deliberate. Loading is the path for
*choosing* a sound, so it asks before discarding edits, clears the dirty flag and announces itself; a
session is not being chosen — it is what was already there — and must do none of those things.

### What happens when something is missing

- **The preset was deleted between runs.** The sound is restored in full; only the *name* is dropped.
  Keeping it would mean "Save" quietly recreating a preset that was deliberately deleted, and the
  knobs are what actually matter.
- **The device is gone.** The first device on that driver is selected instead, and the status line
  names what it was looking for and what it settled on. Note that "first enumerated" is not
  necessarily Windows' own default endpoint under WASAPI; querying the true default needs
  `GetDefaultAudioEndpoint` and is not done here.
- **The driver is gone** — an ASIO driver uninstalled, say. Falls back to the usual pick, and shows
  the driver's own reason for being unavailable. The saved device ids belong to a driver that is not
  in use, so device restore is skipped entirely rather than matched against the wrong list.
- **The rate or buffer is no longer offered.** Dropped rather than forced, which leaves the driver's
  own recommendation in place.
- **A NAM or IR file has moved.** Exactly as a preset already handles it: the module's knobs are kept,
  its LOAD button goes red, and the status line says which file to go and find.
- **The session file is corrupt.** It reads as no session and the application opens on defaults. This
  is read on the way to showing a window, so it must cost the session and never the launch.

### What it deliberately does not do

**It does not start the stream, and it does not arm REC.** The selection is restored with Start
unpressed. An application that seizes a WASAPI-exclusive device the moment it launches is hostile, and
a device left held can need a reboot to clear.

The master's mute state *is* restored, honestly rather than helpfully — quit muted and you come back
muted. The knob reads `MUTED` in red, so it is visible rather than mysterious.

NAM's "match capture rate" switch is not carried, because it is not stored in a preset either: it
belongs to the device pairing rather than to the patch, and it defaults on.

### When it writes

A **10-second coalescing timer** on anything a module does — a knob dragged or typed into, a bypass
switch, a pedal dragged to a new position, a capture or cabinet loaded, a device chosen. A knob sweep
is hundreds of changes and one write.

Anything discrete writes immediately and does not wait: loading or saving a preset, deleting the
loaded one, starting or stopping the stream, and quitting. Starting matters because the driver has the
last word on rate and buffer, and what it *granted* is what should come back.

Save-on-close alone would have been simpler and is the wrong trade: a session that survives only a
clean quit is precisely the one you lose on the day it crashes.

### Fixed — a byte-order mark no longer costs the whole file

Found while testing the above, by writing a settings file with PowerShell's `Set-Content -Encoding
utf8`, which prepends a UTF-8 BOM. JSON's grammar has no room for one, so `Json.parse` failed at
offset 0 and the *entire* file — session and recording destination together — was discarded. The
character is invisible in every editor that produces it, Notepad included, so the symptom is settings
that silently stop existing.

`Json.parse` now skips a leading BOM. Only a leading one, and only one: anywhere else it is genuinely
malformed, and saying so is more use than quietly accepting a file nothing else will read. Applies to
presets as much as to settings, since both go through the same reader.

### Verified

Ten new tests, 203 in total. Every session field survives the real writer and the real parser; a frame
count comes back as `64` and not `64.0`, which would have silently matched nothing in the combo and
handed back the driver's default on every launch; junk of every shape reads as no session rather than
throwing; wrong types degrade field by field instead of losing the sound; a leading BOM does not cost
the file; and the whole thing round-trips through the settings file without evicting the recording
destination.

Then in the built application, in two runs against a hand-written session file:

- Opened on **WASAPI shared** rather than the default ASIO, with the saved input and output, 48 kHz,
  the reordered board (Reverb → Delay → NAM → IR → Overdrive → Gain → Compressor → Gate), Gate off,
  Drive 22.0, Delay 420 ms, Master 65 %, and `Crunch •` in the title bar. Start unpressed.
- The saved buffer of 480 frames is not one this pairing offers, and was **dropped rather than
  forced** — the driver's own 1056 was kept, and 1056 is what the session then wrote back.
- Device ids were deliberately made wrong; both matched on **name** instead, and the write-back on
  close replaced them with the real WASAPI endpoint GUIDs.
- Relaunched with a NAM path pointing at a file that does not exist: the capture's knobs were kept,
  the LOAD button came back red reading `GOAT`, and the status line named the file and the path it
  was expected at.

---

## [1.0.27-alpha] — 2026-08-10

### Fixed — settings were being read as a broken preset

Saving a recording destination wrote `config.json` **into the presets folder**, and the preset store
lists every `*.json` there. So the settings file was read as a sound, failed to parse, and was
reported to the user as a preset that would not load. Introduced in 1.0.25 and entirely my own doing.

Two changes, because one of them alone would only make it unlikely:

- **Settings now live in a `config` subfolder**, `Omega-Plugin/config/config.json`. Subfolders are
  already skipped by the store, which only lists regular files, so a settings file can no longer be
  in the namespace at all.
- **`PresetStore` ignores `config.json` by name** as well. A copy left beside the presets by an
  earlier build would otherwise keep producing the same error for as long as it existed.

No migration: an existing file in the old place is simply not read, and the destination is chosen
again. It is one menu item, and moving files around on someone's disk to save a single setting is a
worse trade than asking for it once.

### Verified

Two new tests, 193 in total: settings land inside a `config` folder and leave nothing preset-shaped
beside the presets, and a stray `config.json` in the presets folder yields the real preset with **no
failures reported** — a direct regression test for what went wrong.

Then in the built application: chose a destination, confirmed `config/config.json` holds it and the
presets folder contains only the two presets.

---

## [1.0.26-alpha] — 2026-08-10

### Changed — the recording destination chooser is on the theme

It was the one borrowed component in the application, and it looked it: a platform window frame
around a Nimbus-painted chooser, silver buttons and all.

**The frame is gone** — the chooser now lives in an undecorated dialog of our own with its own
heading, like every other dialog here.

**The buttons and the drop-downs are ours.** The lever is the **UI delegate**, not colours: Nimbus's
painters mostly ignore `background` and `foreground`, which is the whole reason everything else in
this package is hand-painted. Replacing the delegate with a `Basic*` subclass takes a component out
of Nimbus's hands entirely — the same trick `DarkComboBoxUI` and `DarkMenuItemUI` already play. So
`DarkChrome` walks the chooser and gives every `JComboBox` the existing `DarkComboBoxUI` and every
`JButton` a new `DarkButtonUI`.

`PushButton`'s painting is now a shared function that both it and `DarkButtonUI` call. Two components
and two mechanisms — one is a bare `JComponent` this application builds, the other a delegate for
buttons Swing builds and wires to actions — but **one appearance**, which is the point.

Also: the "Files of Type" row was a blank bar, because directories mode has no filters to offer. It
now says "Folders". The chooser cannot be told to drop the row, but it can be told what to put in it.

### Still not perfect

The toolbar icons at the top of the chooser are the look-and-feel's, and are left alone — a
label-centred painter would erase an icon-only button. A native folder picker through COM would
avoid all of this, and `FileDialog` cannot be used for it: its API has only `LOAD` and `SAVE`, and
the one directory escape hatch is honoured on macOS alone.

### Verified

By opening it in the built application: no window frame, our combo on "Look In", our buttons on "Use
folder" and "Cancel", a named type row — then choosing a folder and confirming both the status line
and the `recordingFolder` written to `config.json`.

---

## [1.0.25-alpha] — 2026-08-10

### Added — recording

A **Rec** button beside Start, with a red lamp while rolling, writing the processed output to a WAV
you can open in Audacity or anything else.

**32-bit float, mono, at the device's rate.** Float because the whole chain is float and Audacity's
own internal format is 32-bit float, so it is a lossless handoff with no dithering — and because
float has headroom above 0 dBFS. The tap is deliberately **before the engine's output clamp**, so a
take driven into the clip keeps its peaks and can be pulled back down later, where capturing after
the clamp would record the damage. Mono because the engine is mono end to end and the second channel
would be a copy. The JDK writes this format natively, so it costs no new dependency and no licence
question.

**The callback does one thing: copy into a ring.** No allocation, no lock, no syscall, nothing that
can block — a disk that pauses for half a second is routine, and a callback that waited for one
would take the stream down. An ordinary-priority thread drains the ring to the file, and the ring
holds about five seconds. If it ever overruns, whole blocks are dropped and **counted**, and the
count is reported when the take ends: a silent gap nobody knows about is the worst possible outcome.

Details that are not obvious until they bite:

- **The header is patched as it goes.** A WAV stores its length in the header, so a take abandoned
  by a crash would claim to be empty. Rewriting the three size fields every five seconds costs two
  seeks and twelve bytes, and turns "lost the take" into "lost its last few seconds".
- **A `fmt ` of 18 bytes and a `fact` chunk**, which is what libsndfile and Audacity write for float
  WAV. Sixteen and no `fact` is common and usually works, but "usually" is the wrong target for a
  file whose entire purpose is to open somewhere else.
- **Silence is recorded on the fault path too.** If the chain throws, the block still reaches the
  file — one that was simply missing would shorten the recording against the performance and make
  everything after it early.
- Rec is disabled unless the stream is running, stopping the stream or closing the window finishes
  the file properly, and the transport shows elapsed time while rolling.

### Added — `config.json`, and a place for settings to live

Recordings go to **`Desktop/Omega-Plugin`** by default — not the settings folder, because presets
belong in hidden per-user config but a recording is a document you go looking for. On Windows the
Desktop is resolved through `FileSystemView`, which follows a OneDrive redirection that `user.home`
does not; on this machine the default correctly resolves to `…\OneDrive\Desktop\Omega-Plugin`.

**Select Recording Destination…** and **Open Recordings Folder** are in the menu, and the choice is
remembered in a new `config.json` beside the presets. Two rules make that file safe to grow:

- **Keys it does not recognise are kept.** The whole map is read and written back, so a setting added
  by a later version survives an earlier one loading and saving the file.
- **A missing or corrupt file is not an error.** Settings are conveniences; refusing to start over
  one would not be.

Takes are named `2026-08-10 18-22-35 Crunch.wav` — timestamped so they sort, preset-named because
"which of these used the GOAT capture" is a question you will ask.

### Verified

Thirteen new tests, 191 in total, plus the built application end to end.

The header is asserted field by field against sizes derived from the WAV specification rather than
read back from the writer, samples round-trip exactly **including values above full scale**, a second
decoder agrees it is a valid file, a file copied mid-recording still describes what it holds, and an
overrun drops whole blocks and counts them. `AudioEngineTest` pins the tap itself: the same block
leaves the engine clamped to 1.0 and reaches the file at 1.5.

End to end, a 7.25 s take was recorded from the running engine and read back independently as
`PCM_FLOAT, 44100 Hz, mono, 32-bit, 319 744 frames`.

### Known

The destination chooser is Swing's `JFileChooser`, which Nimbus paints in its own colours and which
therefore does not match the rest of the window. `FileDialog`, used everywhere else here, cannot
select folders on Windows. A native folder picker through the JNA already on the classpath would fix
it and has not been written.

---

## [1.0.24-alpha] — 2026-08-10

### Fixed — a dropped pedal kept the layout of the size it was lifted at

Drag a pedal to a new position and it landed correctly sized but with its contents wrong: the knob
pushed off centre, the bypass switch sitting ten pixels nearer the right edge than every other
pedal's.

Setting a component's bounds **invalidates** it but does not schedule a layout — only `revalidate`
does, and that runs later, on the event queue. During an animation "later" never arrives before the
next frame overwrites the bounds again, so the pedal's contents stayed laid out for whatever size it
had when it was last actually validated. For a dragged pedal that is the **lifted** size — scaled by
`LIFT_SCALE` and padded by `LIFT_PAD` for its shadow, about ten pixels wider than it ends up. The
descent animation then eased the box back to its slot without ever laying the contents out again.

`ChainBoard.step` now validates each pedal whose bounds it changes, which does the layout *now*, at
the size the pedal actually is. It is a no-op when only x and y moved — which is every pedal except
the one being dragged, since only that one changes size.

**Pre-existing, not new.** It dates from the drag animation itself: it needs a pedal to change size
mid-drag, which is what the lift does, and nothing since has touched that path.

### Verified

By dragging a pedal across the board in the built application, before and after. Before: the moved
pedal's switch sat 23 px from its right edge against 42 px on its neighbours, and the knob was
visibly right of centre. After: knob, label and readout all on the box's centre line, switch at the
same inset as every other pedal.

---

## [1.0.23-alpha] — 2026-08-10

### Added — type a value straight into any knob

Click the readout under a knob and it becomes an editable field, in place, with the current value
selected. Enter accepts, Escape abandons. A knob is the right control for *finding* a value by ear
and the wrong one for *setting* a value you already know — "350 ms" is three keystrokes and an
unbounded number of drags.

- **Only the readout is clickable.** The pointer turns into a text caret over it, and a press there
  opens the editor instead of starting a drag: dragging from the number would move the knob under a
  pointer that was aiming at a value.
- **The unit is stripped for editing** and tolerated on the way back in, so both `6.5` and `6.5 dB`
  work. A decimal comma is accepted too.

### Out of range is refused, not clamped

As asked, and it is the right rule: clamping looks helpful and is not. Typing 5000 into a knob that
stops at 1000 would silently leave 1000, which is indistinguishable from the value having been
accepted — and the one thing worse than a rejected edit is one you think worked.

Refused input **flashes the readout red for 700 ms** before restoring the old value. Simply putting
the old number back would be indistinguishable from a click that did nothing at all. Escape does
*not* flash: abandoning an edit deliberately is not a rejection.

### Verified

By driving the built application through every path, since Swing is not unit-tested here:

| Typed | Result |
| --- | --- |
| `6.5` | accepted — readout reads `6.5 dB`, arc moves |
| `999` (range is ±24) | red flash, reverts to `6.5 dB`, back to normal after the flash |
| `banana` | red flash, value untouched |
| `2` then Escape | abandoned silently, value untouched |

### Known, and pre-existing

Knobs on a **bypassed** pedal cannot be edited, because they are disabled — the same reason they
cannot be dragged. That behaviour is older than this change and was not altered here, but it does
mean a pedal has to be switched on before its values can be typed.

---

## [1.0.22-alpha] — 2026-08-10

### Fixed — the label on a lamp button was off centre

1.0.21 centred the label in the space *left of* the lamp, which is not the same as centring it in the
button. The result sat visibly left of centre, and next to `Rescan` — whose label is centred in the
ordinary way — the pair did not line up.

The label is now centred in the whole button. The lamp still gets its own reserved width, because
`resize` adds it to the button's size, but **reserving room is not the same as pushing the label
aside to make it**, and it turns out no reserving was needed at the drawing stage at all: a button is
always at least `text + PADDING + LAMP_ZONE` wide, so a centred label clears the lamp by at least
16 px however long it gets. The lamp did not have to shrink.

That also corrects the note in 1.0.21 about the label shifting between `Start` and `Stop`: it still
shifts by a pixel or two as the word changes width, and the lamp still never moves, but the label is
now symmetric about the button's own centre rather than about a smaller area beside the lamp.

### Verified

Both states in the built application, `Start` and `Stop`, against `Rescan` alongside for comparison.

---

## [1.0.21-alpha] — 2026-08-10

### Changed — Start carries a lamp instead of a colour

1.0.19 made `Start` a filled accent button on the grounds that it is the bar's primary action. That
was the wrong reading: it is not just an action, it is the one control whose **state** you want to
check at a glance. A colour cannot say that — it looks identical whether audio is running or not.

`Start` now uses the same neutral style as `Rescan`, with a **status lamp** on its right: dark when
stopped, green when running. The label still changes, but a label has to be read, and a lamp is
seen — glancing up to check whether the stream is live no longer means parsing a word.

Details that matter more than they sound:

- **The lamp keeps its own column** on the right, so the label may shift a pixel or two between
  `Start` and `Stop` while the lamp never moves. A status light that wanders as the state it reports
  changes is worse than no light.
- **Unlit is drawn, not omitted** — darker than the button face, with a rim, so it reads as a lamp
  that is off rather than as empty space.
- **Lit gets a halo and a highlight.** Without them it is a green dot; with them it reads as
  something emitting light.
- Both buttons are now the same width, so the pair reads as one row of controls.

### Verified

By driving the built application through both states: `Start` with the lamp dark, then `Stop` with
it lit green and `Rescan` correctly greyed while the stream runs, then back again.

---

## [1.0.20-alpha] — 2026-08-10

### Changed — About is on the theme now

It was the last thing in the application still drawn by the platform: a raw
`JOptionPane.showMessageDialog`, which brought a **native Windows title bar** — around a program that
draws its own — and a look-and-feel **OK button** that Nimbus paints silver and will not let anyone
recolour.

Now routed through `MessageDialog`, the application's own modal, which was already undecorated and
already painted to match. It gains the shared `PushButton` from 1.0.19 for free. No new dialog was
written: the one that reports preset failures does this job.

### Fixed

Blank lines in a `MessageDialog` collapsed to nothing. About uses them to separate version from
copyright from Java version, and an empty `JLabel` lays out at zero height, so the grouping silently
disappeared and the text ran together. An empty line is now a real vertical gap.

### Verified

By opening it in the built application: no platform frame, the panel and border matching the window,
grouped text, a themed OK, and OK actually dismissing it — which matters more than usual for a modal
with no title bar to close.

---

## [1.0.19-alpha] — 2026-08-10

### Changed — Rescan and Start are on the theme now

They were the last two stock `JButton`s in the application. Under Nimbus that means silver, which
cannot be recoloured per component — so they read as a dialog from a different program dropped into
the middle of this one, sitting next to controls that are all hand-painted.

There was already a house button style, but it was `PaintedButton`, private to `ConfirmDialog.kt`,
with fixed text and no disabled state — so the device bar could not have used it even if anyone had
thought to. It is now **`PushButton`**, shared, and the dialogs use it too: one button in this
application rather than one per place that needed one.

Two shapes in one family:

- **Neutral** — the same fill, border and hover as a pedal's `LOAD` button, so `Rescan` in the top
  bar and `LOAD` on a pedal are visibly the same kind of thing at two sizes.
- **Filled** — a solid accent call to action. `Start`, `Save`, and the red confirm on a destructive
  dialog. `Start` earns it: the whole device bar exists to lead there, and it is now the one filled
  element in the row, next to the master knob's accent arc and the enabled pedals' accent switches.

What `PushButton` adds over what it replaces:

- **A disabled state that is painted.** `Start` greys out when two devices share no sample rate, and
  `Rescan` while the stream is running. Previously the only feedback was that clicking did nothing.
- **Changeable text**, because `Start` becomes `Stop` without becoming a different button.
- **A pressed state**, and the action fires on *release inside the button* rather than on click — so
  pressing and dragging off cancels, which is what every other button on the machine does.
- Height matched to the device bar's combo boxes so the row sits on one line.

### Verified

By driving the built application: neutral and filled at rest, hover brightening with an accent
border, `Start` becoming `Stop` on click with the stream actually opening, and `Rescan` painting
disabled while it runs.

---

## [1.0.18-alpha] — 2026-08-10

### Added — presets remember their IR cabinet too

The same treatment 1.0.17 gave the NAM capture, now for the impulse response — and generalised so
the third module to load a file needs no new machinery.

### Changed — one map instead of a field per module

1.0.17 stored the capture under its own `nam` key. Two file-backed modules would have meant four
parallel fields, and a third would have meant six, so it is now a **map keyed by effect name**:

```json
"files": {
  "NAM": { "name": "GOAT.nam",    "path": "…/GOAT.nam" },
  "IR":  { "name": "4x12.wav",    "path": "…/4x12.wav" }
}
```

Keyed by effect name because that is already how `enabled` and `values` work in this schema — a
module that gains a file later needs no new field, and one that goes away is simply not looked up.
Written only when there is something to write, so a preset whose modules load nothing reads exactly
as it always did.

**Presets written by 1.0.17 still load.** The old `nam` key is read rather than migrated, and picks
up the current shape the next time that preset is saved.

The restore path is now one routine shared by both modules, so they cannot drift apart; only the
loader, the labels and the wording differ. A missing cabinet does not cost you the amp — both are
attempted whatever the other does, and if both are gone, both buttons go red and both are named.

### Verified

Three new tests, 178 in total: both files survive the round trip; a preset written by 1.0.17 under
the old key still yields its capture; a preset whose modules load nothing still writes no key.

And end to end against copies of a real capture and a real WAV: load both, save a preset, confirm
both paths in the written JSON, rename **both** files away, reload — and check that both buttons go
red carrying their own file's name, and that the status line holds both explanations in full.

---

## [1.0.17-alpha] — 2026-08-10

### Added — presets remember their NAM capture

Saving a preset now records which `.nam` was loaded, and loading one puts it back. Closes the
longest-standing gap in the preset system: a preset restored the amp's knobs but not the amp, so a
sound built around a capture did not survive being saved.

Stored **inside the preset**, under a `nam` key holding the display name and the full path, rather
than in a registry beside it. Per preset, so two presets naming the same capture can point at
different files — and because it travels in the file, an exported preset carries its amp with it.

The key is written **only when there is one**, so a preset with no amp reads byte-for-byte as it
always did, and every preset saved before this loads unchanged with no capture recorded.

### Added — a preset whose capture has moved says so

Files move. When the recorded path no longer resolves:

- **Every knob the preset restored stays exactly where it is.** Only the file is lost, so pointing
  at the moved capture is all that remains to do — not setting the amp up again. Pinned by a test.
- **The NAM pedal's load button turns red and keeps the capture's name**, e.g. a red `GOAT`. Those
  are two different questions — *something is wrong* and *which file do I go and find* — and
  blanking the label back to `LOAD` would answer the first while losing the second.
- **The status line explains it in full**, naming the preset, the capture and the path it is not at.
  Clicking it copies the whole thing, which is how the path gets pasted somewhere useful.
- **The file chooser opens where the capture used to live**, which is usually one folder from where
  it went.
- The model itself is dropped rather than left as whatever was loaded before. A preset that says
  `GOAT`, shows GOAT's settings and plays something else is worse than one that plainly has no amp.

Restoring happens while the stream is stopped, so a capture is never loaded into a running callback
and its warm-up costs nothing anyone hears.

### Verified

Four new tests, 177 in total: the capture survives the JSON round trip; two presets can name the
same capture at different paths; a preset with no amp writes no `nam` key at all; a preset saved
before this existed still loads. Plus one on the module: clearing a model leaves the knobs alone.

And end to end by driving the built application against a copy of a real capture — load it, save a
preset, confirm the path in the written JSON, rename the file away, reload the preset, and check
both the red button and the message. Then reselect the moved file and watch the alert clear.

---

## [1.0.16-alpha] — 2026-08-10

### Added — a NAM capture can now run at its own rate

Every capture tested was made at 48 kHz; the Sonicake is 44.1-only. A NAM model has no clock, so it
assumes each sample it is handed is one period of the rate it was trained at — feed it 44.1 kHz and
it is wrong about how fast time passes by 8 %, and every frequency-dependent feature of the amp
lands about 8 % low. It sounds darker than it is.

**`dsp/Resampler.kt`** converts around the NAM module so the model sees the rate it expects.

- **Synchronous, not asynchronous.** Both rates come off one clock, so 44.1 kHz to 48 kHz is exactly
  147:160 and stays exact. That makes this a fixed table of coefficients rather than a control loop:
  no drift to track, no fractional phase, bit-repeatable. **It is not, and must not grow into, a
  converter between two devices** — two crystals disagree by tens of parts per million and the ratio
  has to be measured and steered. See `SampleRates`, whose never-resample rule still stands for the
  device boundary.
- Polyphase: `taps` multiply-adds per output frame and nothing else — **1.5 M MAC/s against NAM's
  519 M**, so the cost is not the arithmetic.
- Kaiser-windowed prototype, 64 taps per phase: passband flat to ~18.6 kHz with images 80 dB down.
  Fewer taps would be cheaper and duller, and dulling the top is the exact complaint being fixed.
- The cost that *is* real is delay: **65 frames, 1.47 ms**, measured with an impulse, not claimed.

Wrapped around the NAM module alone rather than the chain, because only NAM cares about absolute
rate — IR already resamples its impulse at load — and because when the rates agree the converter is
not built at all, so the low-latency path is untouched.

### Added — a `⋮` menu on the NAM pedal

Between LOAD and the bypass switch. Pedals are knobs and a switch, which is right for what you reach
for while playing and wrong for what you set once; the overflow menu costs no space until opened.
Dead until a capture is loaded, since it has nothing to say before that.

It holds **Match capture rate**, on by default — the 8 % shift is a defect and the fix should not
have to be found. It is switchable because 1.47 ms on a 5.71 ms floor is a real trade, and because
the converter has artifacts of its own; whether it is worth it is a judgement about the room, the
buffer and the player. Both the mismatch and the exact cost are stated in the menu, next to the
switch that decides it. Where the rates already agree the row says so rather than offering a switch
that would do nothing.

Deliberately **not** stored in presets, and toggling it does not mark one dirty: a preset does not
record which capture was loaded either, so this belongs to the device pairing rather than the patch.

`DarkMenu` gained `content`, `switchRow`, `disabledRow` and `note` to build rows that are not
menu items.

### Fixed

- **The converter's latency read `0.00 ms` at load.** It was only built in `prepare`, which does not
  run until the stream opens — so the figure quoted while choosing a capture was for a converter
  that did not exist yet. It is now built speculatively at a default block size and resized when
  `prepare` says otherwise. Found by running the app and reading its own status line.
- **The popup rendered on Nimbus grey.** A `JMenuItem` paints its own full-width fill, so a dark
  popup's background is never seen; a plain label does not, and the grey showed through everywhere
  the text did not reach. Hand-built rows now go inside one opaque container.

### Verified

Eight new tests, 172 in total, each against a number derived independently of the code:

- output frame counts match the exact 147:160 ratio in both directions, over 64 000 frames
- a constant passes through at unity gain — the truncated sinc tails are normalised out, or
  switching the converter in would step the level
- the declared latency is the real one, measured with an impulse. **A tone cannot answer this**: a
  1 kHz sine at 44.1 kHz repeats every 44.1 frames, so cross-correlation cannot tell a 21-frame
  delay from a 65-frame one. The first version of this test asserted 21 and was wrong.
- a 300 Hz tone round-trips at unity gain with under 5 % residual
- the reservoir delivers exactly the requested block over 2 000 blocks, never starving or overrunning

And by driving the built application: loading a 48 kHz capture on a 44.1 kHz device, opening the
menu, toggling the switch, and reading the status line change.

---

## [1.0.15-alpha] — 2026-08-10

### Fixed — loading a NAM model mid-stream broke the audio

Reported as *"sometimes I hear crackling"* and *"if I changed the NAM mid run there is massive
latency that is resolved if I stop and restart"*. Both are the same fault.

A network that has never executed runs **interpreted**. Measured with `-Xint` against the same model
JIT-compiled:

| | Speed | CPU needed |
| --- | ---: | ---: |
| Compiled, steady state | 2.5x realtime | 40 % of a core |
| **Interpreted, freshly loaded** | **0.1x realtime** | **1493 % — fifteen cores** |

So for the first stretch after LOAD the callback needed a 25-fold speed-up it did not have, and
missed every deadline. That is the crackling. The lasting latency follows from it on a two-clock
backend: WASAPI joins capture and render with a ring, the stall lets the ring settle at a deeper
fill, and nothing drains it back — so the added latency survives the stall and only a restart clears
it. Under ASIO, which is sample-locked with no ring, the same fault produces the crackle without the
lasting latency.

This is exactly what `AudioEngine.warmUp` exists to prevent, and it could not help: it runs before
`stream.start()`, and until a model is loaded `Nam.process` returns before ever reaching the network,
so none of that code is on the path to be warmed.

**`Nam.load` now warms the network before publishing it**, on the loading thread, the same trade the
engine already makes at stream start. Every submodel is warmed, so moving SIZE later does not hit
the same cliff. Bounded by time as well as block count so a large model cannot lock the caller up,
and reset afterwards so the model still starts from silence.

| | Before | After |
| --- | ---: | ---: |
| First 64-frame block after LOAD | ~13 ms | **470 us** |
| Against a period of | 1 333 us | 1 333 us |
| Verdict | 10x over budget | realtime from the first block |

Cost: **~290 ms** behind the LOAD button, measured on both a clean and a high-gain capture.

### Added

- `--nambench <file>` now reports the warm-up cost and times the first block after it against the
  callback period, so the regression this fixes cannot come back unnoticed.
- A test that the warm-up leaves no residue: a model that delays by four samples must still emit
  exactly zero for the first four. 164 tests pass.

---

## [1.0.14-alpha] — 2026-08-10

### Added

- **Click the status line to copy it.** Every failure in the app lands in the status bar along the
  bottom — a driver's refusal, a preset that would not parse, a `.nam` that was not what it claimed.
  The useful thing to do with those is paste them somewhere: a search, a bug report, a message to
  someone who knows what the message means. Retyping a driver error by eye is the exact point at
  which the detail that mattered gets dropped.

  - Only the drawn text is clickable, not the empty width of the label, so a click on what looks
    like background does nothing. The pointer turns into a hand over the part that will respond.
  - Copying is acknowledged — the line shows "Copied to clipboard." for a moment and then puts the
    message back. A status line that silently does something is indistinguishable from one that is
    broken.
  - The message is held separately from what the label is displaying, so clicking twice copies the
    message rather than the word "Copied", and a new message takes over from the acknowledgement
    instead of being overwritten by it a moment later.
  - The clipboard belongs to the OS and another application can hold it locked. That is not an
    error worth an exception, but the click still did nothing, so it says so.

### Verified

By running the app and driving it: a click on the text put `2 input(s), 2 output(s) on ASIO.` on the
clipboard exactly; a click on the blank part of the same label left the clipboard untouched; the
acknowledgement appeared and the original message returned after it.

---

## [1.0.13-alpha] — 2026-08-10

### Fixed — NAM would not load any modern capture

Loading a `.nam` gave *"is a "SlimmableContainer" model — this build runs WaveNet models"*. The reader
was written against file format **0.5.x**; every model tested was **0.7.0**, written by trainer
0.13 and later, and the difference is structural rather than cosmetic:

| | 0.5.x | 0.7.0 |
| --- | --- | --- |
| Kernel size | one per layer array | `kernel_sizes`, one per layer |
| Head re-channel | a 1x1 | a `head` object with a kernel of its own — **16 taps** in practice |
| Activation | a bare string | an object, so `LeakyReLU`'s slope survives the round trip |
| Width after activation | same as the residual | a separate `bottleneck` |
| 1x1s | always present | `layer1x1` / `head1x1`, either can be off |
| Gating | `"gated": true` | `gating_mode` plus a named secondary activation |
| Whole file | one model | optionally a `SlimmableContainer` of several |

All of it is now read, and 0.5.x still is — both normalise onto one internal config so nothing below
the parser knows which version it came from. Shapes that would be *misread* rather than unsupported
— grouped convolutions, active FiLM, per-channel PReLU — are refused by name, because a wrong
unpacking does not fail: it produces a network that runs perfectly happily and sounds like nothing
in particular.

### Added

- **Slimmable models.** A 0.7.0 file can carry the same amp captured at several widths. On the
  models tested that is **3 channels (1 731 ops/sample) and 8 channels (11 776)** — 7.6x realtime
  against 2.7x, or 13 % of a core against 37 %. A **SIZE** knob picks between them using the
  reference's own rule: the first submodel whose `max_value` the setting falls below, and the
  largest when none does, so 1.0 is always the full capture. The incoming model is cleared before it
  is published, so a change starts from silence rather than from stale state.
- **Output levelling.** Captures are not level-matched to anything, and `metadata.loudness` is what
  NAM records so a host can fix that. Across the fourteen models tested it ranges from **-14.7 dB to
  -43.3 dB** — nearly thirty decibels, more than the Level trim's entire range — so a quiet capture
  played raw is very nearly silent. Each is now brought to -18 dB, the reference plugin's target.
  It applies to the wet path only; Mix stays a mix rather than becoming a second volume control.
- **`--nambench <file|directory>`** loads real captures instead of synthetic ones and reports cost,
  loudness, and — for slimmable files — the correlation between the smallest submodel and the
  full-size one.

### Verified

The weight layout is the whole game: a `.nam` is one flat array, nothing in it marks where a layer
begins, and reading it wrongly yields a model that runs and sounds *plausible*. Three independent
checks agree:

1. **Predicted counts are exact.** Derived from the config alone, the 3-channel submodel needs
   1 871 weights and the 8-channel one 12 146 — which is precisely what the files carry. Both
   numbers are now pinned in `WaveNetTest`.
2. **The submodels agree with each other.** They are the same amplifier at different widths, stored
   as two independent weight blobs. Fed the same signal they correlate at **0.9933–0.9998** across
   all fourteen models. A misread layout cannot produce that.
3. **Hand-worked networks.** One channel, one layer, weights chosen so the whole network reduces to
   `scale * activation(x)` — including a case that pins the head re-channel's 16-tap convolution,
   which is invisible on 0.5.x files and load-bearing on every 0.7.0 one.

163 tests pass.

### Also fixed

`WaveNet.process` iterated a `List` of layer arrays **per sample**, and each layer array iterated a
`List` of layers inside that — roughly a hundred thousand iterator allocations a second at 48 kHz.
This is the same mistake that made `Reverb` audible before it was fixed, and it was sitting in the
most expensive module in the chain. Both are arrays with indexed loops now.

---

## [1.0.12-alpha] — 2026-08-10

### Investigated — WASAPI shared cannot go faster on this machine

The obvious next move after 1.0.11 was `IAudioClient3`, whose whole purpose is to let a shared client
name a period instead of accepting the engine's default. It was implemented in full — activate
`IID_IAudioClient3`, `GetSharedModeEnginePeriod`, `InitializeSharedAudioStream`, fall back to the
ordinary path when the driver has no opinion — and then measured, and it **changed nothing**. Every
buffer size on every endpoint came back identical to 1.0.11.

The reason is in the periods themselves, in frames (`-Domega.wasapi.debug=1`):

| Endpoint | default | fundamental | min | max |
| --- | ---: | ---: | ---: | ---: |
| Line / Speakers (Pocket Master) | 441 | 441 | 441 | 441 |
| Microphone (SteelSeries Arctis 1) | 480 | 480 | 480 | 480 |
| Speakers (SteelSeries Arctis 1) | 441 | 441 | 441 | 441 |
| Speakers (ESS SABRE HiFi) | 480 | 1 | 336 | 480 |

`minimum == default` on everything that matters — there is no headroom to claim. Only the ESS SABRE
reports room below its default, and `InitializeSharedAudioStream` at its 336-frame minimum still
handed back the same 1056-frame buffer the ordinary path gets. **The API being present says nothing
about the driver being written for it.** Shared mode's 65.99 ms stands.

### Changed

- The `IAudioClient3` **initialisation** path was reverted rather than shipped. It is correct and it
  falls back cleanly, but it is an unverifiable optimisation: there is no hardware here on which it
  can be shown to help, and it muddled a period with a buffer size on the way through. Untested
  complexity in the stream-open path is worse than no complexity.
- The **query** is kept, in `AudioClient.sharedModeEnginePeriod`, along with the vtable slot numbers
  that were the expensive part to get right (**18 and 20**, not 15 and 17 — `IAudioClient2` inserts
  three methods in between, and getting it wrong does not crash, it returns a nonsensical HRESULT).
  It runs only under `-Domega.wasapi.debug`, and it is how the table above was taken: it is the
  answer to "can shared mode go faster on *this* machine", which is a question worth being able to
  ask on hardware other than mine.

### Verified

`--list-backends` after the revert reports the same 970 / 1056-frame buffers as 1.0.11 on all
seventeen shared endpoints. No behaviour changed.

---

## [1.0.11-alpha] — 2026-08-10

### Added

- **WASAPI shared** as a fourth driver choice. The same API and the same stream code as WASAPI
  exclusive, through the Windows mixer instead of around it — so **other applications keep using the
  device while Omega is running**, which exclusive mode makes impossible.

### Measured

Sonicake in and out, 20 s, on this machine:

| Backend | Buffer | Round trip | Realtime | Xruns |
| --- | ---: | ---: | ---: | ---: |
| WASAPI exclusive | 264 frames | 17.96 ms | 0.9999 | 0 |
| **WASAPI shared** | **970 frames** | **65.99 ms** | **0.9994** | **0** |
| Java Sound | 7056 frames | ~150–200 ms | — | — |

Coexistence proved rather than assumed: two clients opened on the same endpoint at once, both
streaming cleanly in shared mode, while in exclusive mode the second was refused.

### Notes

- Three things follow from the mode and are not tunable: the rate is whatever Windows has the
  endpoint set to, the period belongs to the engine, and the engine rounds buffer sizes up to it.
  The floor here is 970 frames — 22 ms — on every endpoint tried.
- **In shared mode, "change it in Windows Sound settings" is finally correct advice.** In exclusive
  mode it was not, because exclusive negotiates with the hardware directly and ignores that setting.
  A real consequence: the Sonicake is configured at 44.1 kHz and the Arctis at 48 kHz, so in shared
  mode they have no common rate and the pairing is refused with exactly that instruction — while in
  exclusive mode the same pair runs happily at 44.1 kHz.
- Two bugs found by measuring rather than by reading, both from assuming exclusive mode's contract:
  - **Render starved to 73 % of realtime with 66 xruns a second.** The engine wakes a shared client
    every *period*, which is smaller than the buffer, so the whole buffer is never free. Writing
    `bufferSize - GetCurrentPadding()` is the contract in this mode — the same call that was tried
    and rejected for exclusive mode, where it is wrong.
  - **Capture took one packet per wake.** Shared mode can have several waiting, and the remainder
    piled up until the buffer overran. It now drains with `GetNextPacketSize` until empty.

---

## [1.0.10-alpha] — 2026-08-10

### Added

- **NAM.** Neural Amp Modeler captures, loaded from a `.nam` file through a **LOAD** button on the
  pedal. WaveNet inference reimplemented in Kotlin — the reference is C++ and there is no binding
  for it here. Sits after the overdrive and before the cabinet, which is the order an amp capture
  expects. With nothing loaded it passes audio through.
- **`--nambench`**, a headless measurement of what a capture costs on this machine. Neural inference
  is the one module expensive enough to decide the buffer size, and it varies far too much between
  machines and models to guess at.

### Measured

On this machine, under generational ZGC:

| model | ops/sample | realtime × | CPU at 44.1 kHz |
| --- | ---: | ---: | ---: |
| standard (16/8 channels) | 13,320 | **1.6×** | **61 %** |
| lite (8/4 channels) | 3,064 | 4.1× | 25 % |
| feather (8/4 channels) | 2,732 | 4.5× | 22 % |

**A standard capture is not comfortable here.** 1.6× realtime means the audio thread spends
three-fifths of every callback inside the network, with the rest of the chain, the UI and the
collector all wanting the same core — that is a dropout waiting for a buffer size to be lowered.
Lite and feather captures have room. This is a property of scalar JVM arithmetic: the reference C++
uses SIMD and does the same work several times over.

### Notes

- **Correctness against real captures is unverified.** The arithmetic is tested — a hand-worked
  network reproduces `2·relu(x)` exactly, and a dilated tap delays an impulse by precisely its
  dilation — and the architecture bookkeeping is checked by an independent derivation of the weight
  count. But no actual `.nam` file has been loaded, because there is none on this machine. **Supply
  one and it can be validated properly.**
- Weight order is the whole risk. A `.nam` is one flat array with nothing marking where each layer
  begins; read the shapes differently from how they were written and the model still runs, just as
  something other than the amp it was captured from. The loader therefore refuses a file whose
  weights do not come out exactly even, and refuses a head that does not feed the next layer.
- Only WaveNet is handled. Other architectures are refused by name rather than mis-read.
- `Json` moved to `org.example.util` so the DSP layer can parse `.nam` files without depending on
  the preset package.
- 140 tests, up from 128.

---

## [1.0.9-alpha] — 2026-08-10

### Added

- **Compressor.** Feed-forward peak compressor — threshold, ratio, attack, release, makeup. A peak
  detector rather than RMS, because on guitar the thing worth catching is the pick attack and RMS
  averages exactly that away. The gain law is evaluated in the log domain only when the signal is
  actually over the threshold, so the common case costs no logarithm at all.
- **Gate.** Threshold, attack, hold, release. The hold is what makes it usable: without it the gate
  slams shut in the dip between two cycles of a decaying note and chatters. It starts closed, since
  a gate that opens on the first block passes precisely the noise it exists to remove.
- **IR.** Impulse response convolution — a speaker cabinet. Loads a WAV through a **LOAD** button on
  the pedal itself, resamples it to the device rate, and normalises it to unit energy so swapping
  cabinets is a tone change rather than a volume change. With nothing loaded it passes audio
  through, so switching it on before choosing a cabinet is silent rather than surprising.
- Default chain order is now **gate → compressor → gain → overdrive → IR → delay → reverb**. Gate
  first, because gating *after* a distortion means gating the noise the distortion just amplified.
  The cabinet before the time effects, so delay and reverb are a room around the speaker rather than
  heard through it.

### Changed

- **A preset that does not mention a module now switches it off and returns it to defaults.** This is
  what keeps old presets working as modules are added: a preset saved before the compressor existed
  says nothing about it, and the only safe reading of that silence is "there was no compressor in
  this sound". Leaving it as it was would mean the same preset sounded different depending on what
  you had been playing with beforehand.
  - The exception is the master, whose switch mutes rather than bypasses. An unmentioned master
    defaults to *on*, because a preset that forgot to mention it must not load as a silent app.
- Pedals can carry a small header button. The header rather than a row of its own, because every
  row on the board is laid out against a fixed pedal height and a taller pedal would break the
  alignment for all of them.
- The board wraps against a 1500 px width before the window has been laid out, so seven pedals open
  a two-row window rather than one wider than most screens.

### Notes

- IR uses direct convolution, capped at 2048 taps — 46 ms at 44.1 kHz. Past that an impulse is room
  tail, and the reverb already does rooms. Direct rather than partitioned FFT: the measured headroom
  absorbs 2048 multiply-adds per sample comfortably, and it avoids both an FFT implementation and
  the latency a partitioned scheme adds.
- The impulse and its history are built completely and published as one object through a volatile
  field, so loading a cabinet mid-stream can never leave the audio thread reading new taps against
  an old history.
- **A preset does not yet remember which impulse was loaded.** Loading a preset with IR switched on
  but no cabinet chosen passes audio through untouched.
- 128 tests, up from 111.

---

## [1.0.8-alpha] — 2026-08-10

### Added

- **Import Preset…** opens the platform's own file browser and takes one or many files at once.
  `FileDialog`, not `JFileChooser`, so it is the real OS dialog rather than a Swing imitation — and
  it needs none of the theming the Swing one would.
- **Export Preset** opens the folder the presets live in, ready to copy out of. Run on a background
  thread, because handing off to the desktop environment can block for seconds and on the event
  thread that would freeze the window, meter included.

### Changed

- **One JSON file per preset**, replacing the single collection. A sound can now be copied, sent or
  dropped in on its own, which is what makes import and export worth having — and a file that will
  not parse costs that one preset rather than all of them.
- An existing `presets.json` is **split into individual files on first run**, and kept as
  `presets.json.migrated` rather than deleted.
- Preset names are free text and file names are not, so anything a filesystem would reject becomes
  an underscore. Two names can therefore clean to the same file, so collisions are numbered.
- Importing a name already in use **suffixes it** rather than replacing or skipping — an import
  should never quietly overwrite a sound you had, nor silently drop the one arriving.

### Fixed

- **A preset file that could not be loaded said nothing at all.** Failures are now counted in the
  status bar, listed in the menu, and shown in full on request: the file, whether it was malformed
  JSON or simply not a preset, and for malformed JSON the character offset. Previously every failure
  read as "that preset is not there".
- Import reports per-file failures by name instead of a single count.

### Notes

- `pendingFailures` is declared above the `init` block, and the placement is load-bearing: Kotlin
  runs property initialisers in declaration order, so a `= emptyList()` below `init` ran *after* the
  startup read and wiped what it had found. The menu entry was simply never there.
- 111 tests, up from 106.

---

## [1.0.7-alpha] — 2026-08-10

### Added

- **Presets.** Save a sound and load it back — pedal order, every knob, every switch, and the master
  level and mute. Deliberately *not* the audio setup: a preset describes a sound, so it has to load
  on a machine that does not have the interface it was made on.
- **Hamburger menu gains Save Preset and Load Preset.** `Save Preset ▸ Save / Save As…`, with Save
  enabled only when a preset is loaded *and* something has changed — so it answers "is there
  anything worth saving" rather than merely "is a preset open". `Load Preset ▸ <name> ▸ Load /
  Delete`, with Delete in red.
- **The loaded preset appears in the title** — `Omega-Plugin — Crunch` — with a `•` once it differs
  from what was saved. Set on the frame as well as the label, so the taskbar agrees.
- **Loading warns before discarding unsaved edits**, naming both the preset being left and the one
  being loaded. Tracked even with no preset loaded, since those edits are equally lost.
- **Deleting asks first**, with the destructive button red and Cancel neutral.
- **Loading while audio is running** stops the stream, rewires the chain and reopens the same
  devices. Pedal order cannot change under a live stream — delay lines and reverb tails carried into
  a new position are audible — so the alternative was refusing to load at all.

### Notes

- Presets live in `%APPDATA%\Omega-Plugin\presets.json`, or `$XDG_CONFIG_HOME` / `~/.config/
  omega-plugin/` on Linux. Not beside the jar and emphatically not inside it: the jar is a build
  output that has been replaced seven times today, and anything stored in it dies with each version.
- Writes go to a temporary file and are moved into place, so a crash or a full disk mid-save leaves
  the previous presets intact rather than a truncated file.
- **JSON is hand-rolled** rather than adding a second runtime dependency for a schema that is a name,
  a list of strings and two flat maps. Tested for round-tripping, escaping, malformed input, and
  locale independence — `String.format` on a machine with a comma decimal separator would write
  `6,5` and then fail to read its own file back.
- Presets reference effects **by name, not by index**, so one saved before a new effect existed still
  loads: what it does not mention keeps its place, what it mentions that no longer exists is ignored.
- Menus are real `JMenu`/`JMenuItem` on a custom UI. Submenu hover delays, keyboard traversal and the
  rules for when a nested popup closes are hard to get right and Swing already has them — so Swing
  does the mechanics and only the painting is taken over, the same split as the combo boxes.
- Dialogs are painted rather than `JOptionPane`, because a destructive choice has to look
  destructive and look-and-feel buttons cannot be recoloured per dialog.
- 106 tests, up from 93.

---

## [1.0.6-alpha] — 2026-08-10

### Fixed

- **A disabled combo showed a patch of the enabled background inside it** — most visible on RATE
  when the device offers only one sample rate and the control locks. `BasicComboBoxUI` hands the
  renderer a background and lets it fill, using `ComboBox.disabledBackground` from `UIManager` while
  disabled, so an opaque **rectangle** was painted over the rounded fill beneath. It went unnoticed
  while enabled only because the two colours happened to match. The value is now drawn directly with
  a transparent renderer, so the rounded fill is all there is.
- **A disabled combo's value is legible again.** Nimbus's disabled foreground sits close enough to
  the fill that a locked RATE read as an empty box rather than as a fixed one; it is now drawn in
  the theme's own disabled colour.

---

## [1.0.5-alpha] — 2026-08-10

### Added

- **Dragging a pedal now lifts it.** It detaches from the row, scales up 3 %, carries its own drop
  shadow and follows the cursor, clamped to the board so it cannot be dragged out over the device
  bar. It rides a little above the cursor so the gap stays visible beneath it.
- **The drop target is drawn as a placeholder** — filled body in the dark accent with a stronger
  accent border. Under live reflow "where it came from" and "where it will land" are the same
  rectangle, so both markers land on that one shape.
- **The other pedals slide aside** rather than jumping, and the dropped pedal eases back down into
  its slot. Everything approaches its target by a fixed fraction each frame, which needs no start
  time or duration and behaves correctly when the target moves mid-flight — which during a drag it
  does constantly.

### Fixed

- Re-ordering silently stopped working the moment the pedal started following the cursor. Hit
  testing read the pedals' **live** bounds, and the dragged pedal's live bounds sit under the cursor
  by construction, so it always matched its own slot first and the chain never moved. It now tests
  against the settled slots.
- The lifted pedal's shadow was drawn by the board, which paints behind every child, so any pedal
  the lifted one passed over erased it. The pedal now reserves a margin and draws its own.
- Cables are routed from slots rather than live bounds, so they show the chain as it will be instead
  of whipping around after a floating pedal.

---

## [1.0.4-alpha] — 2026-08-10

### Added

- **The layout is responsive.** The window's shape chooses it; there is no mode to switch. Wide, the
  pedals are one row as before. Narrow it and they wrap into a grid, narrower still into a single
  column.
- **Wrapped rows alternate direction, so the chain snakes** — row one left to right, row two right
  to left. Every wrap then becomes a short vertical hop at the end where the signal already is,
  instead of a return cable dragged back across the whole board. Arrowheads rotate to match, so a
  right-to-left row reads as one. It is also how anyone wires a real two-row pedal board.
- **The device bar wraps too**, onto as many lines as it needs, with each label and its control kept
  together so a wrap can never separate them. Without this the window could not be made narrow
  enough for the board to reflow at all.
- **The board scrolls** when the chain needs more height than the window has, on a slim dark
  scrollbar. It fills the viewport and centres itself whenever it does fit, so the scrollbar appears
  only when it is genuinely needed.

### Changed

- Minimum window size is now 380 x 320, down from 880 wide. Both the bar and the board wrap, so
  there is no longer a width at which the window merely clips.
- Dragging a pedal to re-order now hit-tests in two dimensions: row by y, then position within that
  row read in *that row's* direction.
- The device combos take a fixed 190 px closed. Left alone a combo sizes itself to its longest item,
  and "Headset Microphone (3- Arctis Pro Wireless Chat)" would have set the width of the window —
  the drop-down already shows the full name since 1.0.1.

### Notes

- `ChainBoard` positions its children itself rather than delegating to a `LayoutManager`, because
  cable routing and drag hit-testing both need to know which row a pedal landed in and which way
  that row runs.
- `BarLayout` exists because `FlowLayout` wraps but reports a single line's height, so a wrapped bar
  inside a `BorderLayout` is silently cut off. It also hands surplus width to the device combos
  while the bar is one line, which is what `GridBagLayout` used to do.

---

## [1.0.3-alpha] — 2026-08-10

### Changed

- **Pedal height is now fixed, as the width already was.** The maximum height was `Int.MAX_VALUE`,
  so a taller window stretched the pedals while their contents stayed where they were. A pedal is a
  fixed object; it now sits in the extra space rather than absorbing it, centred, with the cables
  following. Verified by growing the window 220 px: pedals held at 156 px.

---

## [1.0.2-alpha] — 2026-08-10

### Changed

- **All five device-bar combo boxes are now gunmetal by default.** Nimbus drew them with a light
  silver gradient that only turned dark once the control took focus, so the bar was a mix of two
  looks and the light state made the white value text hard to read. The dark state is now permanent
  and the accent is purely the focus indicator.
- Combo drop-downs get a matching dark list with an accent highlight on the selected row, and a
  chevron drawn in place of Nimbus's silver arrow button.

### Fixed

- A focused combo filled solid orange with dark text on it. `BasicComboBoxUI` substitutes the popup
  list's *selection* colours whenever the control has focus; the value is now always drawn with the
  control's own colours, since the border already indicates focus.

### Notes

- Implemented as a `BasicComboBoxUI` subclass rather than by overriding Nimbus's painter keys.
  Nimbus paints through `SynthStyle` and applies its colours *after* a renderer returns, which is
  why an earlier attempt to recolour it from `UIManager` had no effect. Existing cell renderers are
  untouched — only the control's own painting changed.

---

## [1.0.1-alpha] — 2026-08-09

### Changed

- The **input and output device drop-downs now open wider than the control**, overhanging it where
  the device names need the room. Device names are long and often differ only at the end — "Headset
  Microphone (3- Arctis Pro Wireless Chat)" against "Headset Earphone (3- Arctis Pro Wireless
  Chat)" — so a popup clipped to the control's width was truncating exactly the part that tells them
  apart. Width is measured from the widest row the renderer produces and capped at 720 px.
- **More space between a knob's name and the knob itself.** The label baseline and the arc were
  effectively touching: the gap measured 3 px but the arc's 5 px stroke straddles its bounding box,
  so it rendered from half a pixel below the text. Knobs are 6 px taller, and the pedals with them.

### Fixed

- Nothing.

---

## [1.0.0-alpha] — 2026-08-09

First tagged build. Alpha because it has been played through on exactly one interface, on one
operating system, and several things listed under *Known issues* are load-bearing.

### Added — audio engine and backends

- `AudioBackend` seam: duplex, callback-driven, mono float. All device concerns — channel selection,
  sample-format conversion, interleaving — stop at this line.
- **ASIO backend** driven through a hand-declared `IASIO` COM vtable. No Steinberg SDK.
- **WASAPI exclusive backend**, event-driven, as fallback where no ASIO driver exists.
- **Java Sound backend** as the always-available bottom tier, so the app still runs where natives
  cannot load.
- Automatic fallback down that ladder, with the reason for each unavailable backend surfaced rather
  than the option silently disappearing.
- Sample-rate negotiation across **both** endpoints, offering only rates they share. Omega does not
  resample between devices — it would cost more latency than the backend saves.
- Buffer size and sample rate are both driver-enumerated; neither accepts a free-form value.
- Per-stream health counters: realtime ratio, deadline misses, device xruns, DSP load.

### Added — measured latency

Sonicake Pocket Master over ASIO, full pedal chain enabled, 44.1 kHz:

- **32 frames — 5.71 ms** round trip, realtime ratio 1.0000, 0 xruns
- **64 frames — 6.44 ms** round trip, realtime ratio 1.0000, 0 xruns
- **128 frames — 9.91 ms** round trip, realtime ratio 0.9999, 0 xruns
- Java Sound path, same interface, for comparison: **~150–200 ms**

Figures are the driver's own `ASIOGetLatencies` report: they include driver safety offsets and
exclude converters. **No cable-to-cable measurement has been taken.**

### Added — effects

- Master volume, gain, overdrive, delay, reverb. All process mono float in `[-1, 1]`, in place.
- Every rate-dependent coefficient and buffer derives from `prepare(sampleRate, maxBlockFrames)`, so
  a knob means the same thing at any device rate.
- Master volume's switch is a **mute**, not a bypass, and ramps over ~11 ms so it never clicks.
- Overdrive normalises by `1/tanh(drive)` so drive adds harmonics without adding level.
- Delay uses a fractional read head with a smoothed read distance — tape-style pitch bend on time
  changes rather than clicks.
- Reverb is a mono Freeverb with mutually-prime comb tunings, rescaled for the device rate.

### Added — user interface

- Custom undecorated window: app icon, title, hamburger menu, and minimise / maximise / close, all
  hand-painted. Window dragging, double-click maximise and edge-drag resize are re-implemented,
  since an undecorated frame gets none of them.
- Pedal board with cables linking the effects in signal order, carrying directional arrowheads.
- **Drag-and-drop pedal reordering**, which rewires the DSP chain. Locked while streaming.
- Master volume knob in the top bar; click mutes and unmutes, returning to the level it was at.
- Backend, input, output, sample rate and buffer pickers. All lock while streaming.
- Status bar reporting measured latency and dropouts, red when non-zero.
- Application icons at eight sizes, 16–512 px, with a simplified variant for 32 px and below.

### Added — diagnostics

- `--list-backends` — every backend, why the unavailable ones are unavailable, and each device's
  rates and legal buffer sizes.
- `--list-devices` — headless enumeration.
- `--selftest` — the real engine between two devices, reporting realtime ratio, deadline misses and
  xruns per second.
- `--measure` — true round trip through a physical loopback lead. Tested against synthetic delay
  lines; **not yet run against real hardware**.
- Startup warning when the JVM is not running generational ZGC.

### Added — project

- Licensed **GPL v3 or later**. Verbatim `LICENSE`, notice in `README.md`, header on every source
  file, third-party licence table.
- JNA 5.19.1, elected under **LGPL 2.1** (of the two licences it is offered under; both are
  GPLv3-compatible). It is
  the only runtime dependency.
- 93 unit tests, none requiring an audio device.
- Shared IntelliJ run configurations in `.run/`.
- `CLAUDE.md`, `docs/WhatsDone.md` and `docs/low-latency-backend.md`.

### Changed

- **Toolchain moved from Java 11 to Java 21, with generational ZGC.** Not a preference: at 64 frames
  under allocation pressure, G1 gave a realtime ratio of 0.9716 against ZGC's 1.0000 — 2.8 % of the
  audio lost — while `deadlineMisses` read 0 under *both*. Collections stall the callback thread
  *between* callbacks, so body-time instrumentation calls G1 healthy while audio is dropping.
- `EffectChain` order is now mutable and array-backed, published whole behind one volatile write.
- `dsp/` no longer imports from `audio/`, restoring the `ui -> dsp <- audio` layering.

### Fixed

Found during development, before any release.

- Java Sound playback was "crackly and robotic": the JDK's Windows backend polls with
  `lock.wait(waitTime)`, and Windows' 15.6 ms scheduler tick made every wait cost ~15.6 ms. The loop
  moved 5 897 frames/s instead of 44 100. Fixed by priming, buffer sizing and drift regulation —
  **505 glitches in 28 s became 1 in 60 s**.
- The JVM stayed alive after the window closed, orphaning a process that held the capture device.
- `Reverb.process` allocated two iterators **per sample** — about 96 000 objects a second — by
  iterating a `List` inside the per-sample loop.
- `dsp/` hardcoded 44.1 kHz in four places. At 96 kHz the 1000 ms delay knob silently topped out
  near 505 ms, and the overdrive tone control landed an octave out.
- Sample rate was negotiated from the **input only**, so a 48 kHz-only output produced an error
  advising a Windows setting that device does not have.
- WASAPI `Initialize` returning `S_OK` did not prove the format was honoured — one device accepts
  144 frames and reports 192.
- A single buffer size was forced on both WASAPI endpoints, which are independent clients.
- Frame counts round-tripped through 100 ns units truncate (264 → 263), breaking a working pairing.
- Caption buttons sat 8.5 px too high: `minimumSize` was unset, so `BoxLayout` read a stale
  `Component.size()`.
- Maximise covered the taskbar — `MAXIMIZED_BOTH` ignores screen insets on an undecorated frame.

### Known issues

- **No cable-to-cable latency measurement.** Every figure is the driver's own claim.
- **Linux has never been run.** Not once. The seam is OS-agnostic and a Linux backend touches
  nothing in `dsp/` or `ui/`, but none of it is verified.
- **Packaging.** The jar requires Java 21+ and cannot be given VM flags through a manifest, so
  `java -jar` runs without the collector the app needs. Launch with
  `java -XX:+UseZGC -XX:+ZGenerational -jar …`, or use `gradlew run`.
- **Nothing persists.** Chain order, buffer, rate, device choice and knob positions reset on restart.
- **AORUS HDMI cannot run WASAPI exclusive** — its driver fires events 33 % faster than it plays.
  Refused with a named reason; two workarounds were tried and reverted because they damaged
  well-behaved devices.
- **Two different physical devices at the smallest WASAPI period drop out** — independent crystals,
  no cushion. Use a larger buffer, one device both ways, or ASIO.
- The Sonicake (44.1 kHz only) cannot pair with the AORUS (48 kHz only). Sample-rate conversion is
  under consideration for monitoring paths only.
- Hamburger menu contents are placeholders.
- Aero Snap, the system window menu and the OS drop shadow are gone — the cost of drawing our own
  window chrome.

### Verified by ear

A guitar has been played through the Sonicake on both ASIO and WASAPI. Everything else in this
release is verified by unit test and by counter.

[1.0.0-alpha]: https://example.invalid/omega-plugin/releases/1.0.0-alpha
