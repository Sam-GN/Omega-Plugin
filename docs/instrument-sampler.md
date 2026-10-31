# Instrument Sampler — playing other sounds through the guitar

**Status: proposal. Nothing here is built.** Written 2026-10-30 against 1.5.10-beta as a plan to
review later, not as a commitment. Companion to `theme-optional.md`, `profile-optional.md` and
`pickup-optional.md`.

The idea, in two halves that share one engine:

1. **A sampler.** Capture one note from the guitar — or load a file — and play it back at any
   pitch, from a virtual keyboard on screen or the computer's keys. The captured note becomes an
   instrument: a bowed pad, a struck bell, a bass, whatever the source was.
2. **The guitar as the controller.** Track the pitch and the loudness of what is being played and
   drive that instrument with it, so the guitarist plays the sampled sound with their hands on the
   strings. This is the half that makes it a guitar synth rather than a toy, and the hard half.

Both are generators, like the metronome and the loop box, and like them belong beside the chain
rather than in it. Nothing sampled ships: the instrument is empty until the user captures or loads
their own audio, the same rule that keeps NAM captures, IRs, drum kits and lanes out of the
distribution.

---

## 1. What the user does

- Switch the instrument on from the strip — a fourth icon, a keyboard glyph, beside the eye, the
  loop and the fork. A board appears in the stack like the loop board does.
- **CAPTURE** a note: play one, hold it, let it ring. The board trims the silence off the front,
  finds the note's pitch and calls that the root. Or **LOAD** a WAV of anything.
- Play it from the **piano** on the board, two octaves with octave shift, or from the computer
  keyboard mapped the way trackers do it (the Z row and the Q row). Each key plays the sample
  shifted to that note.
- Shape it: **ATTACK, DECAY, SUSTAIN, RELEASE**, a **LEVEL**, and **ORDER** — AFTER under the
  guitar, or THRU into the start of the chain so the pedals run over it, as a lane can.
- Set **SOURCE** to GUITAR and the piano becomes a display: the note being tracked lights up, and
  the instrument sounds at the pitch and loudness of what is being played. BOTH leaves the keys live
  too.

---

## 2. The sample

### 2.1 Capturing

The capture point is a decision. The **dry input** gives the cleanest note for pitch detection and
the most flexible instrument, since the pedals can be applied afterwards by choosing THRU. The
**finished sound** after the master is what the user hears and may be what they want to play with.
Offer the dry input as the default and the finished sound as the alternative; the loop box already
taps the second point and the engine already copies the first.

A capture is a few seconds, not a minute: a cap of ten seconds at the stream's rate is under two
megabytes and covers any note that is going to be looped for sustain. The buffer is allocated in
`prepare`, as a lane's is. Capture ends on silence, on a key, or at the cap.

### 2.2 Analysis, all of it off the audio thread

Once the capture ends, on a worker thread:

- **Trim** the leading silence to the onset, with a few milliseconds kept so the attack is not cut.
- **Find the root.** A pitch estimate over the steady part of the note — YIN or the McLeod pitch
  method, both standard, both a page of code — gives a frequency; the nearest MIDI note is the
  root, and the remaining cents are stored so the instrument plays in tune. The user can override
  the root from the board when the detector is fooled, which octaves of a plucked string do manage.
- **Find loop points for sustain.** A plucked note dies; a held key should not. Search the
  sustaining part of the note for a region a few periods long whose ends match — a zero crossing
  with the same slope and a small difference over the following samples — and loop it with a short
  crossfade. A sample without a usable region plays once and the release does the rest, and the
  board says so.

A loaded file goes through the same analysis. It is decoded by `LaneFiles`' path, mixed to mono,
cut to the cap. It is **not** resampled to the stream's rate: see 3.1.

### 2.3 Root detection is why the sample is stored with its rate

A sample is stored at the rate it was captured or decoded at, with its root, its cents, its loop
points and its envelope defaults. Everything else is computed at play time.

---

## 3. The playback engine

### 3.1 Varispeed

A voice plays the sample at a read speed:

```
step = 2^((note − root) / 12) × (sampleRate_of_sample / sampleRate_of_stream)
```

advancing a fractional read position by `step` every output frame and interpolating between
samples — four-point cubic (Hermite) is the standard choice and is cheap enough. The second factor
is why a sample is never resampled offline: a rate change changes `step`, and nothing else. That is
simpler than the lanes, which must be resampled because they are read at exactly one sample per
frame.

The trade is the one every sampler makes: a single sample shifted far from its root changes
character, not just pitch — the chipmunk effect above, the slowed-tape effect below. Within about
five semitones either way a plucked string is convincing; beyond that it is an effect in itself,
which some users will want. **Multi-sampling** — a sample per octave, or per few semitones, each
with its own root, the engine picking the nearest — is the remedy, and is phase 2 rather than
phase 1.

### 3.2 Voices, fixed and preallocated

A fixed set of voices — eight is conventional — each with a read position, a note, a gain, an
envelope stage and a pointer to its sample. All allocated in `prepare`. A new note takes a free
voice or steals the oldest releasing one. No voice is ever created at play time; that is the whole
of the realtime contract applied here.

The envelope is a per-sample ADSR on each voice; release begins at note-off and the voice frees
itself at the end of it. Loop points are honoured while the key is held and after note-off until
the release ends.

### 3.3 Where it touches the signal

The same two points the loop box uses. The engine's block becomes:

```
input → sampler.track(input)            (feed the pitch tracker, § 5; a copy, never a write)
      → sampler.mixThrough(buffer)      (THRU voices, ahead of both boards)
      → chain → second board → post (master)
      → looper.finish(buffer)
      → sampler.finish(buffer)          (AFTER voices)
      → recorder → rhythm → clamp
```

AFTER lands after the looper's own after-lanes, so a lane recorded while the instrument plays
contains it — the instrument is part of the performance, which is what a user recording a loop of
it expects. THRU is in the input, so it is in the recording either way, as a through-lane is.

### 3.4 Note events cross a thread

A key on the board, a computer key or a tracked note is a note-on or note-off that must reach the
audio thread without a lock. A preallocated single-producer ring of small event records, written by
the event thread and drained at the top of `begin`, does it; the loop box's `seek` is the one-slot
version of the same thing. Tracked notes come from a second producer (§ 5), so either two rings or
one ring fed through the tracker thread.

### 3.5 Cost

A voice is one interpolation and one envelope step per frame: eight voices at 96 kHz is well under
a tenth of what the amp voice costs at twice the rate. The sampler itself is not the realtime
concern. The tracker is.

---

## 4. The virtual keyboard

A piano drawn on the board: two octaves, white and black keys, the usual geometry, clickable, with
the pressed and the tracked notes lit. Octave up and down buttons; a readout of the note under the
pointer. Width follows the board like the waveform does.

Computer keys: `Z S X D C V G B H N J M` for the lower octave from C, `Q 2 W 3 E R 5 T 6 Y 7 U`
for the upper, `,` and `.` or `-` and `=` for octave shift. The loop box's key dispatcher already
sits on the focus manager and skips text fields; this adds a second consumer that is active only
while the instrument is on and SOURCE includes KEYS, and never while a KEY binding is being
captured. Reserved keys stay reserved.

**MIDI is the obvious extension and costs no dependency.** `javax.sound.midi` is in the JDK, so a
MIDI keyboard or a MIDI footswitch could feed the same event ring. It is not in phase 1 because
nothing else in the app has a MIDI port yet and a device picker for it is its own small piece of
work.

---

## 5. The guitar as the controller

### 5.1 What has to be measured

Two things, continuously, from the dry input: **the pitch** and **the loudness**. Pitch picks the
note; loudness is the envelope — the instrument swells and dies with the string rather than with a
fixed ADSR, which is what makes it feel played rather than triggered.

- **Pitch**, monophonic. YIN or MPM over a window of about 1024 samples at 44.1 kHz, which is two
  periods of the low E at 82 Hz and the floor for a confident estimate. Updated every 256 frames,
  not every block: the tracker's answer is smoothed and a note only changes when the new estimate
  has held for two updates and is more than a quarter tone from the current note, so vibrato bends
  the pitch of the voice (it is passed through as fine pitch) and does not retrigger it.
- **Onset**, for retriggering the same note: a jump in the loudness envelope against its recent
  level. A new pick is a new note-on, even at the same pitch.
- **Loudness**: a short-attack, medium-release envelope follower, mapped to the voice's gain.
  Note-off when it falls below a threshold for long enough; the sampler's own release then takes
  over.

### 5.2 Latency, and why it is a physics figure not a bug

A pitch estimate needs the note to have existed for about two periods before it can be made: 24 ms
at the low E, 6 ms at the high E's twelfth fret. Add the 256-frame update interval (5.8 ms) and the
block. **The tracked instrument speaks 10–30 ms after the pick**, and no implementation moves that
floor much; commercial guitar synths live with the same one and the bass strings are always the
slow ones. The dry guitar at the normal path latency can be mixed under it, which is what most
players do.

### 5.3 Where the tracker runs

Not on the audio thread. YIN's difference function at a 1024 window and a 600-lag search is
hundreds of thousands of multiply-adds per update; at 64 frames that is a sizeable fraction of a
1.45 ms period on top of a chain that already spends most of it with a capture loaded. So:

- The audio thread copies the input into a preallocated ring and does nothing else.
- A **tracker thread** of ours, high priority, wakes on each 256 frames arriving, runs the
  estimate over its own window, and publishes the result — pitch in Hz, confidence, loudness — as
  volatile scalars, plus note-on and note-off events into the sampler's ring.
- The audio thread reads the scalars for fine pitch and gain, and drains the events at `begin`.

That is the same shape as the stream monitor threads: the audio thread writes volatiles, another
thread allowed to think reads them. The hop costs about a millisecond and keeps the deadline
untouched. The warm-up still has to reach the tracker's methods, on the tracker thread's behalf,
so its first real window is not interpreted.

### 5.4 What it cannot do, said now

- **Chords.** One pickup, one signal, one pitch. Polyphonic tracking of a mixed signal exists in
  research and in a few products and is not something this app should promise. Play lines.
- **Harmonics, muted notes, heavy distortion in the capture path.** The tracker wants the dry
  signal; a clean pickup setting tracks far better than a hot one, and the board should say so.
- **Fast runs on the bass strings.** The physics floor above. Trills on the low E will smear.
- **Pitch bends past the note threshold** retrigger, by design; a BEND RANGE knob that widens the
  threshold is a later refinement.

---

## 6. Where it lives

- **`dsp/sampler/`**: `Sampler` (voices, the two mix calls, the event ring), `Sample` (buffer,
  rate, root, cents, loop points), `Envelope`, `PitchTracker` (the analysis, usable offline for
  the root and online from the tracker thread), `Analysis` (trim, root, loop search). No import
  from `audio/`, like the rest of `dsp/`. Not an `AudioProcessor`, for the reason `Transport` and
  `Looper` are not: it generates, and it touches the signal in two places.
- **`AudioEngine`**: the four calls above, `prepare` and `warmUp` extended, the allocation test
  extended to run with voices sounding and the tracker ring being fed.
- **`ui/`**: `InstrumentBoard`, a `Stackable` below the loop board; the piano as a custom-painted
  component; the strip gains its fourth icon. The board follows the loop board's conventions — the
  Cube buttons, the compact knob, pop-out if it earns one.
- **Session**: whether the instrument is on, SOURCE, ORDER, the envelope and level, and the
  **path** of a loaded sample — never the audio. A captured note that the user wants back next
  time is saved with SAVE as a WAV, as a lane is, and the session keeps its path, the way it keeps
  a NAM capture's.
- **`--selftest --chain`** carries it: a sample of synthesised noise, two voices sounding, the
  tracker fed. Otherwise it drifts out of the measurement, as the chain once did.

---

## 7. Phases

### Phase 1 — the sampler and the keyboard

Capture and load, trim and root detection offline, varispeed with cubic interpolation, eight voices,
ADSR, LEVEL, ORDER, the piano and the computer keys. No loop points: a note plays once and the
release shapes the tail. This is a complete and useful instrument by itself.

### Phase 2 — sustain and range

Loop-point search and crossfaded looping; multi-sample zones; root override and fine tune on the
board.

### Phase 3 — the guitar as the controller

The input ring, the tracker thread, pitch and loudness tracking, onset retrigger, SOURCE with
GUITAR and BOTH. Measured, not assumed: tracking latency per string and the false-trigger rate on a
recorded test phrase, before it is called done.

### Phase 4 — MIDI in

A MIDI input picker, notes and sustain into the event ring, and the same port offered to the loop
box's KEY bindings.

---

## 8. Risks

- **Expectation.** The word "synth" promises chords and instant response; § 5.4 is the list of
  things to say in the status line before the user discovers them. Phase 1 carries none of that
  risk, which is a reason to ship it first and alone.
- **The tracker thread on Windows.** It needs the same treatment the callback thread gets —
  MMCSS, or at least a high priority — or a busy desktop will starve it and the instrument will
  stutter while the audio stays clean. `Rt` already knows how.
- **Keyboard focus.** A second consumer on the key dispatcher, alongside the loop box's bindings
  and the text fields, is where a typed tempo plays a note. The rule has to be one owner at a time
  and the text fields always win.

---

## 9. Open questions

1. Capture point default — dry input, as proposed, or the finished sound?
2. One sample or zones in phase 1? Zones are not hard, but the board gets busy.
3. Does the dry guitar stay audible under the tracked instrument, and is that a knob (a dry/wet
   on the board) or just the board's LEVEL against the master?
4. Should a captured sample be saved automatically into the settings folder so it survives a
   restart without the user pressing SAVE? It is the user's own audio either way.
5. Computer-key layout: tracker style as proposed, or a single row?
6. Is phase 3 worth building at all before a MIDI keyboard has proven the sampler? Phase 1 plus
   phase 4 is a sampler played from a keyboard; phase 3 is the guitar synth. They are different
   products to different players.
