# Omega-Plugin user manual

This manual explains every control in Omega-Plugin, in the order you meet them. It assumes you play
guitar and own a computer, and nothing else. Words that sound technical are explained the first time
they come up, and there is a glossary at the end.

If you only want to make a sound right now, read [First sound in five steps](#first-sound-in-five-steps)
and come back to the rest later.

![The board mid-stream, with a second FX slot added, above the device row and the rhythm section](img/screenshot.png)

## Contents

1. [What Omega-Plugin is](#what-omega-plugin-is)
2. [What you need](#what-you-need)
3. [Installing and updating](#installing-and-updating)
4. [First sound in five steps](#first-sound-in-five-steps)
5. [The window, top to bottom](#the-window-top-to-bottom)
6. [The device row](#the-device-row)
7. [Latency, in plain words](#latency-in-plain-words)
8. [Working the pedals](#working-the-pedals)
9. [Every pedal, one by one](#every-pedal-one-by-one)
10. [FX slots](#fx-slots)
11. [Split mode: two boards at once](#split-mode-two-boards-at-once)
12. [The loop box](#the-loop-box)
13. [The rhythm section](#the-rhythm-section)
14. [Recording](#recording)
15. [Presets: saving and loading sounds](#presets-saving-and-loading-sounds)
16. [The menu](#the-menu)
17. [What comes back when you reopen the program](#what-comes-back-when-you-reopen-the-program)
18. [The status line and the health readout](#the-status-line-and-the-health-readout)
19. [Keyboard and footswitch](#keyboard-and-footswitch)
20. [Where your files live](#where-your-files-live)
21. [When something is wrong](#when-something-is-wrong)
22. [Running the diagnostics](#running-the-diagnostics)
23. [Glossary](#glossary)

---

## What Omega-Plugin is

Omega-Plugin turns a computer into a guitar rig. Your guitar goes into an audio interface, the
interface goes into the computer, the program runs the sound through a row of effect pedals, an
amplifier and a speaker cabinet, and the result comes out of the interface again into your headphones
or speakers. All of that happens live, with a delay small enough that it feels like playing through
a real amp.

Despite the name, it is not a plugin for another program. It is a program of its own. "Plugin" here
means a guitar effect, in the sense of plugging a pedal into the chain.

It runs on Windows and on Linux. It is free software under the GNU General Public License, and the
source code is at <https://github.com/Sam-GN/Omega-Plugin>.

Everything in the program is made by the program itself: the drums are synthesised, the amplifiers
and cabinets are built from filters and clipping stages. Nothing recorded from a real amp, speaker or
drum kit ships with it. If you want the sound of a real amplifier, you load a capture of one
yourself. [The NAM pedal](#nam-a-captured-amplifier) and [the IR pedal](#ir-the-speaker-cabinet)
explain how.

## What you need

- **A guitar** with a pickup. Electric, or an acoustic with a piezo pickup.
- **An audio interface.** This is a small box with a guitar socket on the front and a USB lead to
  the computer. A guitar plugged straight into a computer's microphone socket sounds thin and noisy
  and arrives late. Any interface made for recording will do. The program was developed against a
  Sonicake Pocket Master.
- **A computer** running 64-bit Windows 10 or 11, or 64-bit Linux. The program needs nothing
  installed beforehand: its own copy of Java is inside the download.
- **Headphones or speakers**, plugged into the interface rather than the computer. The interface's
  output is where the processed guitar comes out.
- **For the lowest delay on Windows**, the driver your interface's maker provides. This is called an
  ASIO driver, and it usually comes from a download page on the maker's website. The program works
  without it, with slightly more delay.

Optional, if you want them later:

- **Amp captures**, files ending in `.nam`. They are made with the Neural Amp Modeler project and
  there are thousands shared free online. One file is one amplifier at one setting.
- **Impulse responses**, files ending in `.wav`, each a recording of one speaker cabinet. Also widely
  shared.

## Installing and updating

Download from the Releases page: <https://github.com/Sam-GN/Omega-Plugin/releases>. Take the newest
version at the top.

**Windows.** Download the file ending in `-Windows.zip`. Right-click it and choose Extract All, into
any folder you can write to, for instance a folder on the Desktop. Open the folder and double-click
`OmegaPlugin.exe`. Keep the folder together: the `.exe`, the `app` folder and the `runtime` folder are
one unit, and moving the `.exe` on its own breaks it. Windows may show a warning the first time
because the program is not signed. Choose "More info" and then "Run anyway".

**Linux.** Download the file ending in `-Linux.AppImage`. Make it executable and run it:

```
chmod +x Omega-Plugin-*-Linux.AppImage
./Omega-Plugin-*-Linux.AppImage
```

One file works on every distribution. For the lowest delay, install your distribution's PipeWire
JACK package, usually called `pipewire-jack` (on Fedora, `pipewire-jack-audio-connection-kit`).
Without it the program still runs, through ALSA. If nothing at all happens when you run the file,
your system may lack FUSE 2; running it with `--appimage-extract-and-run` after the file name works
without.

**Updating.** The program does not check for new versions. Look at the Releases page now and then.
On Windows, extract the new zip and delete the old folder. On Linux, download the new AppImage, make
it executable, and delete the old file. Nothing you made is lost: your settings and presets live in
a folder of their own, outside the program, and so do your recordings. See
[Where your files live](#where-your-files-live).

## First sound in five steps

1. **Plug in.** Guitar into the interface, interface into the computer, headphones into the
   interface. Turn the interface's own volume down to start with.
2. **Start the program** and look at the row of pickers near the top, under the title bar. Choose
   **DRIVER**: on Windows pick ASIO if it is offered, otherwise WASAPI exclusive. On Linux pick
   JACK / PipeWire if offered, otherwise ALSA. Then pick your interface under **INPUT** and
   **OUTPUT**.
3. **Press Start.** The button lights green and the status line at the bottom says "Running".
4. **Play.** You should hear your guitar through the Gain pedal, the Amp and a cabinet, which are
   on by default. Turn the interface's volume up slowly. If you hear nothing, check that the
   interface's input gain is up and its direct-monitor switch, if it has one, is off.
5. **Make it yours.** Click a pedal's switch to turn it on or off. Drag a knob up or down to change
   it. When you like the sound, open the ☰ menu at the top left, choose Save Preset, then Save As…,
   and give it a name.

Everything below explains what each control does and why.

## The window, top to bottom

The window has no ordinary frame. Its own title bar is at the top, and the program draws every
control itself so it looks the same on Windows and Linux.

| Part | Where | What it is for |
| --- | --- | --- |
| Title bar | The very top | The ☰ menu, the program's name, the name of the preset you have loaded, and minimise, maximise and close. Drag it to move the window. Double-click it to maximise. Drag any window edge to resize. |
| Device row | Under the title bar | Which interface, which driver, which sample rate and buffer size, the master volume, Rescan, Start and Rec. [The device row](#the-device-row). |
| Rhythm row | Under the device row | The metronome and drum machine: tempo, beat lamps, time signature, feel, level, Play. [The rhythm section](#the-rhythm-section). |
| Tool strip | A thin band above the boards | Three icons: the eye, the loop, the fork. [Working the pedals](#working-the-pedals), [The loop box](#the-loop-box), [Split mode](#split-mode-two-boards-at-once). |
| The board | The big area in the middle | Your pedals, wired in order with cables. Scrolls when there is more than fits. |
| Status line | The bottom | Messages from the program on the left, the health readout and the output meter on the right. [The status line](#the-status-line-and-the-health-readout). |

A word about **the signal path**, because everything else refers to it. Your guitar enters at the
left of the board and travels along the cables from pedal to pedal. After the last pedal it passes
through the board's LEVEL, then the MASTER volume in the device row, and then out to your interface.
What is upstream affects what is downstream: a reverb placed before the overdrive gets distorted,
one placed after it does not.

## The device row

From left to right.

### DRIVER

How the program talks to your interface. The list only shows what works on your computer, best
first, and if the one you choose cannot be used the program drops to the next and says why in the
status line.

| Driver | System | What it means for you |
| --- | --- | --- |
| **ASIO** | Windows | The lowest delay by a wide margin. Needs the driver from your interface's maker. The interface is both input and output, so choosing it under INPUT fills in OUTPUT too. |
| **WASAPI exclusive** | Windows | No extra driver needed. The program takes sole control of the device while running, so other programs cannot play sound through it. The next best after ASIO. |
| **JACK / PipeWire** | Linux | The Linux equivalent of ASIO. Needs the `pipewire-jack` package, or a running jackd. |
| **ALSA** | Linux | Always available. The entry called `Default` goes through the sound server and lets other programs keep playing. The entries marked `(direct)` go straight to the card and are faster, but only when nothing else is using it. |
| **WASAPI shared** | Windows | Lets other programs keep using the device, at the cost of roughly 66 ms of delay. For checking things, not for playing. |
| **Java Sound** | Both | The fallback that always exists. Delay of 160 to 200 ms. If the program says you are on Java Sound, something above it failed, and [When something is wrong](#when-something-is-wrong) explains what to do. |

Changing the driver makes the program look at every device again, which can take most of a second.
The status line says "Scanning for audio devices…" and Start waits until it is done. That is normal.

### INPUT and OUTPUT

Which device the guitar comes in from, and which device the sound goes out to. Normally both are your
interface. Under ASIO they are the same box and the second picker follows the first.

You can mix devices under WASAPI and ALSA, for instance a USB microphone in and the computer's
speakers out. Two different physical devices each run on their own clock, and at the smallest
buffer sizes they drift apart and drop out. If that happens, use a larger BUFFER, or the same device
both ways.

### RATE

The sample rate: how many times a second the sound is measured. The choices are what your output
device can do, in hertz. 44 100 and 48 000 are the usual ones. A higher rate makes each buffer
shorter in time, which lowers the delay, but costs the computer more work.

A rate marked **C** is one your input device cannot do itself. The input then runs at its own rate
and is converted on the way in, which adds about 0.7 ms. The status line tells you which device is
being converted and what it costs. The output is never converted. This is what lets a 44.1 kHz-only
interface feed 48 kHz-only speakers.

### BUFFER

The buffer size, in frames, with the time it amounts to beside it in milliseconds. This is **the
latency control**. The program processes sound in small blocks; the buffer is the size of one
block. Smaller means less delay but less time to do the work, and when the computer cannot keep up
you hear crackles and dropouts.

The list only contains sizes your driver will actually accept at the chosen rate.

Where to set it:

| Situation | Buffer |
| --- | --- |
| Playing, normal chain | 64 frames |
| You want more safety margin | 128 frames |
| A NAM amp capture is loaded | 128 frames |
| Split mode with a NAM capture on each board | 256 frames or more |
| It crackles at any of the above | Go up one step and try again |

RATE and BUFFER are locked while the stream is running. Stop, change, Start.

### MASTER

The final volume, after everything on the board. Drag the knob up or down. **Click the knob to
mute**, and click again to unmute at the level you had. The mute is a short fade rather than a cut,
so it never clicks. The readout beside it shows the percentage, or MUTED in red.

The master is the last thing in the signal path. Turning it down makes everything quieter without
changing how hard the pedals are driven. Default 80 %.

### Rescan

Looks for devices again. Press it after plugging in an interface the program did not see at
startup. Under Java Sound, Rescan cannot find hot-plugged devices and you have to restart the
program.

### Start

Starts and stops the audio. Lit green while running. Starting takes a moment the first time in a
session, longer with a NAM capture loaded, because the program runs the whole chain once silently to
get every pedal ready before any sound goes through it. The status line shows the stages.

While running you can turn pedals on and off, move every knob, change any dropdown, switch split
and the loop box on and off, and use the metronome. You cannot drag pedals into a new order, add or
remove FX slots, or change the device row. Those need the stream stopped, and the status line says so
if you try.

### Rec

Records what you hear to a WAV file. Lit red while recording. [Recording](#recording) has the
details.

## Latency, in plain words

Latency is the delay between hitting a string and hearing the note. Some comes from the interface's
own electronics, some from the driver, and some from the buffer. A real amp a few metres away has a
few milliseconds of delay too, because sound travels about a metre every three milliseconds, so a
round trip under about 10 ms feels like standing next to an amp and most players stop noticing it.

What the program measured on the interface it was built against, with every pedal on and the
computer under memory pressure:

| Driver | Buffer | Round trip |
| --- | ---: | ---: |
| ASIO | 64 frames | 6.4 ms |
| ASIO | 128 frames | 9.9 ms |
| WASAPI exclusive | 264 frames | 18.0 ms |
| ALSA direct (Linux, one laptop's built-in card) | 256 frames | 16.0 ms |
| Java Sound | | 160 to 200 ms |

These are what the driver reports about itself, which is a good estimate rather than a measurement
with a cable. The health readout at the bottom right shows the same figure for whatever you are
running right now. If your guitar feels late, the first thing to check is which DRIVER you are on,
and the second is BUFFER.

## Working the pedals

Every pedal on the board is the same kind of object: a title at the top, a switch at its right end, a
row of knobs underneath, and sometimes a dropdown or a button or two in the title row.

### The switch

Click the switch at the right end of a pedal's title to turn it on or off. A pedal that is off is
drawn grey and the sound passes straight through it as if it were not there. On a fresh install the
Gain, Amp and IR pedals are on and everything else is off, so what you hear first is a clean amp
through a cabinet.

### Knobs

- **Drag up or down** to turn a knob. Up is clockwise.
- **Hold Shift while dragging** for fine adjustment, a sixth of the speed.
- **Roll the mouse wheel** over a knob to nudge it a step at a time. If the wheel is already
  scrolling the board, it keeps scrolling the board until you move the pointer; the first notch of a
  gesture decides what it belongs to.
- **Double-click** a knob to put it back to its default.
- **Click the value under a knob** to type a number. Enter accepts, Esc cancels. A number the knob
  cannot do, outside its range or not a number, is refused and the readout flashes rather than being
  quietly changed to something else.

Every change you make is heard immediately while the stream runs.

### Dropdowns

Some pedals have a dropdown in the title, marked with a small ▾: the Overdrive chooses a kind of
drive, the Amp an amplifier family, the IR a cabinet, and an FX slot what effect it is. Changing the
dropdown changes the sound at once, with a short fade so there is no click, and the knobs under the
pedal change to the ones the new choice has. Each choice remembers its own knob positions, so
flipping to another and back loses nothing.

### The + button

The EQ shows four knobs and the Amp shows four. Click the `+` in the title to reveal the rest, and
the same button, now a `−`, to hide them again. Nothing is lost by hiding: a hidden knob keeps working
at whatever you set it to. On an FX slot the `+` means something different: it adds another slot
after this one.

### Moving pedals into a new order

Stop the stream, then press on the body of a pedal and drag it along the board. The other pedals
slide out of the way and the cables redraw. The order you make is part of the sound and is saved in
the preset. While the stream is running the pedals cannot be moved, and the pointer says so.

The default order is the conventional one: Gate, Compressor, EQ, Gain, Overdrive, Amp, NAM, IR,
FX 1, Delay, Reverb. Dynamics and tone shaping first, distortion in the middle, the amplifier and
cabinet where an amp and a cabinet go, and the time effects last so echoes and reverb tails are not
fed back into the distortion. You are free to break any of that.

### The cables

The lines between pedals show the order the sound travels. When the window is narrow the board wraps
into rows, and the rows snake, left to right then right to left, the way a real two-row pedal board
is wired. The arrows show the direction.

### The eye

The eye icon on the tool strip hides every pedal that is switched off, so the board shows only what
is making sound. Click it again to show them all. Hidden pedals are still there and still in the
preset; this is only about what you look at. A line through the eye means pedals are hidden.

## Every pedal, one by one

Knob ranges are given so you know what the numbers mean. Defaults are in bold where it helps.

### Gate

Silences the signal between notes, so hum and hiss do not come through while you are not playing.
Place it first, before the distortion makes the noise louder.

| Knob | Range | What it does |
| --- | --- | --- |
| Thresh | -80 to 0 dB, **-45** | How quiet the signal has to be before the gate closes. Raise it if noise still gets through; lower it if the ends of your notes are being cut off. |
| Attack | 0.1 to 50 ms, **2** | How fast the gate opens when you play. |
| Hold | 0 to 500 ms, **60** | How long the gate stays open after the signal drops below the threshold. This is what stops it fluttering open and shut on a dying note. |
| Release | 5 to 1000 ms, **150** | How gently it closes. |

### Compressor

Evens out your volume: loud notes are turned down, so the quiet ones can be turned up with Makeup.
Adds sustain and makes picking more consistent. It reacts to the peaks of your picking rather than
the average, because on guitar the pick attack is the thing worth catching.

| Knob | Range | What it does |
| --- | --- | --- |
| Thresh | -48 to 0 dB, **-18** | The level above which the compressor starts turning things down. |
| Ratio | 1 to 20, **4** | How hard. 2 is gentle, 4 is a classic guitar setting, 10 and up is squashing. |
| Attack | 0.5 to 100 ms, **10** | How quickly it reacts. Slow lets the pick click through. |
| Release | 10 to 1000 ms, **120** | How quickly it lets go afterwards. |
| Makeup | 0 to 24 dB, **0** | Volume added back after the compression. |

### EQ

A five-band equaliser, the tone controls of the rig. It shows four knobs, and `+` reveals all nine.

The four you see first are the ones that fix a real instrument: Low Cut for the thump a piezo pickup
puts under everything, Mid F and Mid to find and remove the honk that undersaddle pickups have
somewhere between 800 Hz and 3 kHz, and High to take the glass off the top.

| Knob | Range | What it does |
| --- | --- | --- |
| Low Cut | 20 to 500 Hz, **20** | Everything below this frequency is removed. At 20 it does nothing. |
| Mid F | 200 to 5000 Hz, **800** | Where the first mid band sits. |
| Mid | -18 to +18 dB, **0** | How much that band is cut or boosted. |
| High | -18 to +18 dB, **0** | The top end, above High F. |
| *Hidden until expanded* | | |
| Mid Q | 0.3 to 6, **1** | How narrow the first mid band is. Higher is narrower, for a surgical cut. |
| Mid2 F | 300 to 8000 Hz, **2800** | Where the second mid band sits. |
| Mid2 | -18 to +18 dB, **0** | Its cut or boost. |
| Low | -18 to +18 dB, **0** | The bottom end. |
| High F | 1500 to 12000 Hz, **4000** | Where the High control starts. |

At its defaults the EQ changes nothing at all, so you can leave it on and bring in bands as needed.

### Gain

A clean volume control, -24 to +24 dB, default 0. Use it to push the pedals after it harder, or to
match two presets' loudness.

### Overdrive

The distortion pedal. The dropdown chooses which kind of drive it is, and each kind has its own knobs.
These are voicings in the style of a family of pedal, not models of one particular unit: they have
the shape of clipping and the filtering that family has.

| Type | Knobs | Character |
| --- | --- | --- |
| **Smooth** | Drive, Tone, Level | The program's original overdrive. Smooth, round clipping. The default, and the one old presets use. |
| **Screamer** | Drive, Tone, Level | In the style of the green mid-boosting overdrive every pedalboard has. Pushes the mids forward, tightens the bass. |
| **Rat** | Distortion, Filter, Volume | In the style of the rodent distortion. Harder clipping, and Filter turns the top end *down* as you raise it, as on the real one. |
| **Fuzz** | Fuzz, Volume | In the style of a germanium fuzz. Thick, square, singing. |
| **Tube** | Gain, Bass, Treble, Level | A valve-style preamp stage with its own bass and treble. |

Drive, Distortion, Fuzz and Gain all mean how hard the signal is pushed into the clipping. Level
and Volume set how loud it comes out. Tone is a frequency, 200 to 8000 Hz: higher is brighter.

### Amp

An amplifier, voiced as one of five families. Like the drives, these are voicings rather than models:
they have the stages a real amp has, a cascade of preamp stages, a tone stack, a power amp that sags
under a hard chord, with the gain structure and tone curve of the family. For the sound of one
specific real amplifier, load a capture into the NAM pedal instead. The Amp runs its maths at twice
the sample rate so that its distortion does not fizz.

The pedal shows Gain, Bass, Mid and Treble. The `+` reveals Presence and Master.

| Family | In the style of | Character |
| --- | --- | --- |
| **American Clean** | A blackface combo | Lots of clean headroom, scooped mids, bright. |
| **Tweed** | A fifties tweed combo | Breaks up early and warmly. Its tone stack does little, as on the real one. |
| **British Crunch** | A plexi stack | Mid-forward, with the crunch coming from the power amp as much as the preamp. |
| **British Chime** | A class-A chime amp | Jangly top end. Has a **Cut** control instead of Presence: turning it up takes treble away, as on the real one. |
| **Modern High Gain** | A modern four-stage high-gain head | Tight, scooped, lots of distortion. |

| Knob | Range | What it does |
| --- | --- | --- |
| Gain | 0 to 10, **5** | How hard the preamp is driven. |
| Bass, Mid, Treble | 0 to 10, **5** | The tone stack. Five is where the family's own character sits. |
| Presence or Cut | 0 to 10, **5** | Top end after the power amp. Cut works backwards. |
| Master | 0 to 10, **6** | Output level. Pushing it harder adds power-amp sag. |

### NAM: a captured amplifier

NAM stands for Neural Amp Modeler, a free project that captures the sound of a real amplifier into a
small file ending in `.nam`. Thousands of captures are shared online. Load one here and you have that
amplifier. A capture usually does not include a speaker, so leave the IR pedal on after it.

- **LOAD** in the title opens a file picker for a `.nam` file. The button then shows the capture's
  name. Loading works while the stream runs.
- **✕** beside LOAD unloads the capture and keeps every knob.
- **⋮** opens a small menu. It has one thing in it, **Match capture rate**, which only matters when
  the capture was made at a different sample rate from the one you are running at. On, the program
  converts the capture's rate to yours at a cost of a millisecond or two. Off, the capture plays
  about 8 % slow and sounds slightly darker. It is on by default. The menu tells you both rates and
  the exact cost in frames and milliseconds.

| Knob | Range | What it does |
| --- | --- | --- |
| Size | 0 to 1, **1** | Some captures carry the same amp at several sizes. Lower uses a smaller, cheaper one. On a file with one model it does nothing. |
| Level | -24 to +24 dB, **0** | Output volume. Every capture is normalised to the same loudness when loaded, so this starts at a sensible place. |
| Mix | 0 to 1, **1** | Blend between your dry signal and the capture. |

**A capture is the most expensive thing in the program**, about a hundred times the rest of the chain
put together. With one loaded, use a BUFFER of 128 frames. The status line warns you when the
combination you have chosen is known to be tight.

With nothing loaded the pedal passes sound straight through, so switching it on before choosing a
file is silent rather than surprising. The ⋮ menu is greyed until a file is loaded.

### IR: the speaker cabinet

An amplifier without a speaker cabinet sounds like a wasp. This pedal is the cabinet. The dropdown
offers a loaded file or one of four built-in cabinets.

- **Loaded IR**, the first choice and the best one. An impulse response is a short recording of a
  real cabinet with a real microphone, as a `.wav` file. Press **LOAD** and pick one. Only the first
  46 ms of the file are used, which is the cabinet; the rest is room, and the Reverb does rooms. The
  **✕** unloads it. Knobs: **Mix** (0 to 1, default 1) and **Level** (-24 to +24 dB, default 0).
- **1x12 Open**: a combo. Lighter bottom, bright.
- **2x12 Open**: a bigger combo. More low-mids.
- **4x12 Closed**: a stack. Tight, thick bottom, dark.
- **1x10 Small**: a practice amp. Thin below 150 Hz, boxy mids.

The built-in cabinets have two knobs: **Mic**, from OFF-AXIS (darker, less bite) to ON-AXIS (bright,
full presence), which is the one adjustment people make on a real cabinet, and **Level**. They are
there so you have a cabinet on your first run; a loaded impulse response has detail they do not.

LOAD and ✕ only appear while the dropdown is on Loaded IR. A file you loaded stays loaded while you
try the built-in cabinets, and is there when you switch back.

### Delay

An echo. The sound is repeated after a delay, each repeat quieter than the last.

| Knob | Range | What it does |
| --- | --- | --- |
| Time | 20 to 1000 ms, **350** | The gap between repeats. Short times, under 80 ms, thicken; long times are distinct echoes. |
| Feedback | 0 to 0.95, **0.35** | How much of each repeat goes round again. Higher means more repeats. |
| Mix | 0 to 1, **0.3** | How loud the echoes are against your dry signal. |

### Reverb

The sound of a room, from a small space to a hall.

| Knob | Range | What it does |
| --- | --- | --- |
| Room | 0 to 1, **0.6** | How big the room is and how long the tail lasts. |
| Damping | 0 to 1, **0.5** | How quickly the high frequencies die away. Higher is a softer, more carpeted room. |
| Mix | 0 to 1, **0.25** | How much reverb against your dry signal. |

### LEVEL

Each board ends in a LEVEL knob, in the board's header. It is only shown in split mode, where it sets
how much of each board you hear. Default 71 %, which is -6 dB, so two boards playing the same guitar
add up to about the loudness of one. Click it to mute that board.

### MASTER

Described under [the device row](#master). It is the last thing in the signal path and belongs to
the whole rig, not to a board. It is saved in a preset.

## FX slots

An FX slot is an empty space on the board that you decide the contents of. The dropdown in its title
picks what it is. The board starts with one, FX 1, and you can add more.

- **+** in a slot's title adds another slot immediately after it. The new slot is numbered by its
  position: FX 1, FX 2 and so on. Dragging a slot past another renumbers both.
- **✕** removes a slot. FX 1 is permanent and has no ✕. If the slot has been changed from its defaults
  the program asks first; a slot you added a moment ago and never touched goes without a question.
  Removing FX 2 renumbers FX 3 down into its place.
- Adding and removing need the stream stopped. Changing what a slot *is* does not.

What a slot can hold:

| Type | Knobs | What it does |
| --- | --- | --- |
| **Chorus** | Rate, Depth, Mix | Two slightly detuned copies of your signal swept against the original. Shimmer and width. Rate 0.05 to 5 Hz, default 0.6. |
| **Tremolo** | Rate, Depth, Shape | The volume pulses up and down. Shape morphs the pulse from smooth (0, an amp's tremolo) to chopped (1, the helicopter kind). Rate 0.1 to 12 Hz, default 4. |
| **Transient** | Attack, Sustain | Makes the pick attack louder or quieter, and the body of the note louder or quieter, independently and whatever volume you play at. Attack at a few dB down takes the click off a piezo pickup without touching the note. Both -12 to +12 dB. |
| **De-Ess** | Freq, Thresh, Range | Turns down one narrow band of high frequencies, only when it gets loud. Made for finger squeak and string scrape on an acoustic, which live in a band around 5 kHz and only happen now and then. Freq 2 to 12 kHz, Thresh -60 to 0 dB, Range 0 to 24 dB. |
| **Delay** | Time, Feedback, Mix | The same delay as the fixed pedal, so a board can carry two delays at different points in the chain. |
| **Reverb** | Room, Damping, Mix | The same reverb as the fixed pedal. |
| **Octave** | Track, Tone, Thresh, Mix | A note an octave below what you play, in the style of the classic analogue octave pedal: a hollow, slightly synthetic sub. **Play single notes**, low on the neck; chords make it warble, as they do on the real pedal. Track (100 to 2000 Hz, default 600) is how the pedal finds your note: lower it if a low note will not track, raise it if high notes drop out. Tone rounds off the sub. Thresh is where the pedal stops listening as a note dies away. Mix starts at 0, so a slot switched to Octave is silent until you turn it up. |

Transient and Octave do nothing at their defaults, so you hear them only once you move a knob. The
others are heard as soon as you choose them: Chorus and Tremolo start at half depth, Delay and
Reverb with a modest Mix, and De-Ess acts whenever the band it watches gets loud.

## Split mode: two boards at once

The fork icon on the tool strip splits the rig into two boards. Both are fed the same guitar and
both are heard together, so one sound can be a clean amp and a driven one at once, or a dry signal
under a heavily effected one.

- Press the fork. A second board, BOARD 2, fades in below the first, and each board's header gains a
  name and a LEVEL knob. The second board has every pedal the first has, with its own settings.
- The two boards are summed, then go through the MASTER together.
- LEVEL on each board sets its share. The default of 71 % each is chosen so that two boards add up to
  what one was.
- Split can be switched on and off while the stream runs. It fades in and out rather than switching.
- Press the fork again to go back to one board. The second board keeps its settings until you quit,
  so switching back on brings it back as it was.
- A preset saved in split mode stores both boards. One saved with a single board is unchanged from
  before split existed.
- **Two NAM captures, one on each board, need a BUFFER of 256 frames or more.** One capture uses
  nearly all of a 64-frame buffer's time on its own. The status line says so when you switch split
  on, load a capture, or start the stream.

The eye hides disabled pedals on both boards at once.

## The loop box

The loop icon on the tool strip opens the loop box above the boards. It is a looper: you record a
phrase, it plays round and round, and you record more on top of it, up to four lanes of up to a
minute each.

### What you see

One row per lane, and one horizontal scrollbar under all of them that moves through the minute.
Each row has, left to right:

| Button | What it does |
| --- | --- |
| **✕** | Clears a lane that has something in it. Removes a lane that is empty. Lane 1 is never removed. |
| **+** | Adds another lane below, up to four. Only lit on the last lane. |
| **SAVE** | Writes this lane to a WAV file you choose. |
| **LOAD** | Loads a sound file into this lane: its first minute, mixed to mono, converted to the stream's rate. Any file the program's Java runtime can read, and WAV always works. |
| **REC** | Records this lane. Reads STOP while recording. |
| **ORDER** | AFTER or THRU. See below. |
| **KEY** | Binds a key or footswitch to this lane's ON. See [Keyboard and footswitch](#keyboard-and-footswitch). |
| **ON** | Whether the lane is heard. Lit when it is. |
| **Level knob** | This lane's volume. Drag, Shift for fine, double-click to reset. |
| **Waveform** | The lane's audio, with the playhead moving over it. Click anywhere to play from there. |

Above the rows: a **PLAY / PAUSE** button and a **pop-out** icon.

### How recording works

1. Start the stream. The loop box needs audio running.
2. Press REC on lane 1 and play. The lane records what you hear: the finished sound, after the whole
   chain and the master. Press REC again, or Esc, Space or Enter, to stop. **The length of that first
   recording is the length of the loop.** Every lane after it is that long.
3. Press REC on lane 2. It waits for the top of the loop, starts recording there exactly, and stops
   by itself when it comes round. You hear lane 1 while you play lane 2, but lane 1 is not recorded
   into lane 2; a new lane never contains the ones already playing.
4. Repeat for lanes 3 and 4.

One lane records at a time. Pressing REC while the box is paused starts it playing. When every lane is
empty again the loop length is free, and the next recording sets a new one.

### ORDER: where a lane plays back

- **AFTER** (the default): the lane is added after the master, under your live guitar. It sounds
  exactly as it was recorded.
- **THRU**: the lane is fed into the start of the chain, ahead of the pedals, so your pedals run over
  it again. Record a riff with the overdrive, then switch the lane to THRU and put it through the
  delay. A THRU lane that is playing is in the sound, and so it is in anything you record meanwhile,
  as it would be on a real rig.

### Play, pause and the playhead

PLAY and PAUSE start and stop the loop where it is: pausing holds the position, so play resumes
rather than restarts. Paused lanes fade to silence rather than cutting. The box opens paused and
reads PLAY until you start something. Click a waveform to jump the playhead to that point; this is
refused while a lane is recording.

### What survives what

- Lanes keep their audio when you hide the loop box, pop it out, stop the stream, or change the
  sample rate (they are converted to the new rate). Closing the box stops playback with a fade and
  keeps the audio. Only quitting the program loses them, so **use SAVE** for anything you want to
  keep.
- The program remembers whether the box was open, the key bindings, and where the pop-out window
  was. It does not store the audio.

### The pop-out window

The pop-out icon moves the whole loop box into a window of its own, which can sit on a second screen.
Closing that window puts the box back above the boards; it does not switch the loop off. Keys bound to
lanes work in either window.

## The rhythm section

The second row from the top is a metronome and a drum machine, generated by the program rather than
sampled. It is mixed into what you hear after the pedals, so the click never goes through your
overdrive.

| Control | What it does |
| --- | --- |
| **TEMPO** | Type a number from 30 to 300 beats per minute and press Enter or click away. Decimals are allowed. |
| **Tap** | Tap it in time and the tempo is set from your taps, averaged over the last few. Stop tapping for a couple of seconds and the next tap starts fresh. |
| **Beat lamps** | One lamp per beat in the bar, lit in turn while the rhythm plays. |
| **SIG** | The time signature: 2/4, 3/4, 4/4, 6/8 or 12/8. In 6/8 and 12/8 the tempo counts the dotted-quarter pulse, two or four to the bar, which is how musicians count those bars, and the lamp count follows. |
| **FEEL** | What is played. The list changes with the signature; see below. |
| **LEVEL** | How loud the rhythm is. |
| **Play / Stop** | Starts and stops it. Only available while the stream runs, because the click comes out of the same output as your guitar. |
| **⋮** | A menu of things you set once. See below. |

### Feels

In every signature the list starts with click styles, then drum grooves:

- **Click**: a tick on every beat, a higher one on beat 1.
- **Click + 8ths**, **Click + triplets**, **Click + 16ths** in 2/4, 3/4 and 4/4. In 6/8 the one
  subdivision is **Click + 6**, the six eighths of the bar, and in 12/8 it is **Click + 12**.
  Subdivisions play quieter than the beats.
- **Click on 2 & 4** in 4/4 only, the backbeat drill.
- **Click on 1 only**: one tick a bar, for practising holding time yourself.
- The grooves:

| Signature | Grooves |
| --- | --- |
| 2/4 | March, Polka, Train |
| 3/4 | Waltz, Jazz waltz, Rock waltz |
| 4/4 | Rock, Half-time, Shuffle |
| 6/8 | Ballad, Jig, Driving |
| 12/8 | Slow blues, Driving, Doo-wop |

When you change SIG, a feel with the same name in the new signature is kept, and otherwise the plain
click is chosen.

### The ⋮ menu

- **Click over drum patterns**: play the click on top of a groove, so you still have the tick. Locked
  on when the feel you have chosen is already a click.
- **Also record a " - drums" copy**: when you record a take, also write a second file with the
  rhythm mixed in, named like the first with " - drums" on the end. Off, the recording is the guitar
  only and the click is for your ears alone. The copy is only kept if the rhythm actually played
  during the take.
- **Count-in before recording**, and **Two bars instead of one**: when you press Rec while the
  metronome is playing, wait for the next bar line, count one or two bars, and start the file on the
  downbeat. The count-in is not in the file. Pressing Rec never starts the metronome by itself, so
  with it stopped Rec records immediately as usual. Pressing Rec again during the count-in cancels.

Tempo, signature, feel, level and these switches are remembered between sessions but are not part of
a preset, because a tempo is not part of a sound.

## Recording

**Rec** in the device row records what you hear to a WAV file. The light is red while it runs, the
status line shows the file name, and the health readout at the bottom right counts the minutes and
seconds.

- The file is 32-bit float mono, which any editor opens, including Audacity. It is taken before the
  final clipping, so a take that went into the red can be turned down afterwards without damage.
- Files are named by date and time, with the name of the loaded preset after it:
  `2026-10-31 20-15-42 My Sound.wav`.
- They go to a folder called `Omega-Plugin` on the Desktop on Windows, or in your home folder on
  Linux. **☰ → Select Recording Destination…** changes that, permanently, and **☰ → Open Recordings
  Folder** opens it.
- The stream has to be running. Press Rec again to stop; stopping the stream also stops a recording.
- If the disk could not keep up, the status line says how much was lost when the take ends.
- The rhythm section's menu adds a count-in and a drums copy; see
  [The rhythm section](#the--menu).

The looper's SAVE button is a different thing: it writes one lane, not what you are playing.

## Presets: saving and loading sounds

A preset is a sound: the order of the pedals, every switch, every knob, the type of every dropdown,
how many FX slots there are and what they hold, the master volume, and which NAM capture and
impulse response were loaded. If the rig was split, both boards. It does not contain your audio
devices, so a preset made on one computer loads on another.

All of this is under **☰**, the menu at the top left of the title bar.

- **Save Preset → Save As…** asks for a name and saves a new preset.
- **Save Preset → Save** writes your changes back into the preset you have loaded. It is only
  available when a preset is loaded and something has changed.
- **Load Preset →** lists your presets by name. Each has **Load** and **Delete**. Delete asks first and
  cannot be undone.
- **Import Preset…** picks one or more preset files that somebody gave you and copies them into your
  presets folder. A file that will not load is reported by name.
- **Export Preset** opens your presets folder so you can copy files out of it. Each preset is one
  `.json` file, named after the preset, so sharing a sound means sending one file.

The title bar shows the name of the loaded preset. A **•** after it means you have changed something
since it was saved. Loading another preset while there are unsaved changes asks first.

Loading a preset while the stream is running stops and restarts the stream, because the order of the
pedals may have changed.

**When a preset's file has gone.** A preset remembers the NAM capture and the impulse response by
their location on disk. If the file has been moved or deleted, every knob is still restored and the
pedal's LOAD button turns red with the file's name on it. Click it to find the file. The status line
names what is missing.

## The menu

Everything under ☰, in order.

| Item | What it does |
| --- | --- |
| Save Preset | Save, Save As… See [Presets](#presets-saving-and-loading-sounds). |
| Load Preset | Your presets by name, each with Load and Delete. |
| Import Preset… | Copies preset files into your presets folder. |
| Export Preset | Opens your presets folder. |
| Select Recording Destination… | Where Rec writes its files. Remembered. |
| Open Recordings Folder | Opens it. |
| Logging | A switch. On, the program writes a diagnostic log to `logs/omega.txt` in its settings folder and keeps writing it across restarts, so an intermittent fault can be caught. Off, nothing is written. Off by default. |
| Open Logs Folder | Opens it. |
| *N* preset file(s) failed to load… | Only appears when some file in the presets folder could not be read. Lists them. |
| About Omega-Plugin | The version, the licence in short, and a link to this manual. |
| Licences | The full copyright and licence notice, and every third-party component with its licence. |
| Exit | Quits. Stops the stream and finishes any recording first. |

## What comes back when you reopen the program

The program puts itself back the way you left it:

- The sound on the board, whether or not it was ever saved as a preset, and the • if it had unsaved
  changes.
- The driver, the input and output devices, the rate and the buffer. If a device has gone, the
  program picks the first one on that driver and says so in the status line.
- The metronome's settings.
- Whether split mode and the loop box were on, and whether the eye was hiding pedals.
- Where the loop box's pop-out window was, and the keys bound to its lanes.

It does **not** start the stream for you. Press Start. And it does not keep the loop box's audio.

## The status line and the health readout

**The status line** at the bottom left is where the program talks to you: what it just did, what it
could not do and why, and warnings about the settings you have chosen. Green is good news, red is a
problem, grey is information. **Click the message to copy it**, so a driver's error text can be
pasted into a search or a bug report instead of retyped.

**The health readout** at the bottom right, while the stream runs, reads something like:

```
REC 1:23   9.9 ms driver   DSP  4%   CPU 1.2%   0 dropouts
```

| Figure | Meaning |
| --- | --- |
| REC m:ss | How long the current recording has run. Only while recording. |
| *n* ms driver | The round trip as the driver reports it, in and out. Includes the driver's own safety margins. |
| DSP *n*% | How much of each buffer's time the pedals are using. This is what predicts a dropout. Under 50 % is comfortable; near 100 % and you are about to hear it. |
| CPU *n*% | How much of the whole computer the program is using, the way Task Manager counts it. A healthy chain is a few per cent. |
| *n* dropouts | Buffers that were late or lost. Should stay at 0. Turns red otherwise. |

Hover over it for a longer explanation with the exact frame counts. The meter beside it, labelled
OUT, shows the output level with a peak hold.

## Keyboard and footswitch

- **Esc, Space or Enter** stops a loop-lane recording from anywhere in the program.
- **Esc** also cancels a knob value you were typing, and cancels a KEY binding in progress.
- **Enter** accepts a typed knob value or tempo.
- **Shift** while dragging a knob is fine adjustment.
- **KEY on a loop lane** binds any other key to that lane's ON. Click KEY, then press the key; the
  button shows its name. Click KEY again and press another key to change it, or Esc to clear. A
  footswitch that acts as a USB keyboard works the same way, hands free. A bound key does nothing
  while you are typing in a text field, so a bound digit still types. The bindings are remembered.
- **Alt+F4** or the ✕ at the top right quits.

## Where your files live

| What | Windows | Linux |
| --- | --- | --- |
| Presets, one `.json` each | `%APPDATA%\omega-plugin` | `~/.config/omega-plugin` (or under `$XDG_CONFIG_HOME`) |
| Settings and the restored session | `config\config.json` inside that folder | `config/config.json` inside that folder |
| The diagnostic log, when Logging is on | `logs\omega.txt` inside that folder | `logs/omega.txt` inside that folder |
| Recordings | `Omega-Plugin` on the Desktop, unless you changed it | `~/Omega-Plugin`, unless you changed it |
| The program itself | Wherever you extracted the zip | The AppImage file |

Deleting the program never touches the first four. Deleting the settings folder gives you a fresh
install's defaults and loses your presets, so copy them out first.

The settings file is plain text. If the program ever fails to start, so that the Logging switch is out
of reach, set `"loggingEnabled": true` in it with any text editor and the next launch will write a
log.

## When something is wrong

**It crackles, drops out or stutters.** The computer is not keeping up with the buffer size. Raise
BUFFER one step and try again. Check the health readout: DSP near 100 % or dropouts climbing confirms
it. A NAM capture wants 128 frames; two of them want 256. On Linux, the status line may say the audio
thread is not realtime; see the Linux note below.

**The delay is long, like playing through a bad video call.** You are almost certainly on Java
Sound. Look at DRIVER. If ASIO is not in the list on Windows, install the ASIO driver from your
interface's maker and press Rescan or restart. If it is in the list but you are not on it, choose
it. [Running the diagnostics](#running-the-diagnostics) tells you exactly which drivers loaded and
why the others did not.

**No sound at all.** In order: is Start lit green? Is MASTER muted (it says MUTED in red)? Is the
interface's input gain up? Does the OUT meter move when you play? If the meter moves and you hear
nothing, the output device or the headphone socket is wrong. If it does not move, the input is.

**The sound is clean when it should be distorted, or distorted when it should be clean.** Look at
which pedals are grey. The eye may be hiding some.

**The interface is not in the list.** Press Rescan. Under Java Sound Rescan cannot see devices
plugged in after the program started; restart the program. Under ASIO, make sure no other program
has the interface open.

**Two different devices in and out drop out at small buffers.** Each has its own clock and they drift
apart. Use a bigger buffer, the same device both ways, or ASIO.

**WASAPI exclusive refuses a device, or stops with a message about its event timing.** Some drivers
cannot run in exclusive mode at all; HDMI outputs on some graphics cards are known for it. The program
says so rather than playing badly. Use another device or WASAPI shared for that one.

**A preset's LOAD button is red.** The NAM capture or impulse response it wants has moved. Click the
button and find the file. The rest of the preset is already in place.

**The rate picker shows a C.** Not a fault: your input cannot run at the output's rate and is being
converted, at a cost of about 0.7 ms. Choosing a rate without the C, if there is one, avoids it.

**On Linux the status line says the audio thread is not realtime.** The program needs permission to
run its audio thread at realtime priority, and that is an account setting. Run the AppImage from a
terminal with `--list-backends`; its last line names the fix for your distribution, usually adding
your user to the `realtime` or `audio` group and logging out and in.

**The window is white or blank for a moment on Linux.** It should not be since 1.7.0. If it is,
Logging on and a report is welcome.

**Something else.** Turn **☰ → Logging** on, use the program until it happens again, then open the
logs folder and attach `omega.txt` to a report at <https://github.com/Sam-GN/Omega-Plugin/issues>,
together with the output of `--list-backends` below. Click the status line to copy any error text.

## Running the diagnostics

The program can answer a few questions from a command line without opening its window. These are
for when something is wrong; you never need them to play.

**Windows.** Open the folder you extracted, hold Shift and right-click in an empty space, choose
"Open PowerShell window here" or "Open in Terminal", and type:

```
.\OmegaPlugin.exe --list-backends > report.txt
```

Then open `report.txt`. The `> report.txt` part is needed because the Windows program has no window
of its own to print into.

**Linux.** From a terminal in the folder holding the AppImage:

```
./Omega-Plugin-*-Linux.AppImage --list-backends
```

It prints directly.

| Command | What it tells you |
| --- | --- |
| `--list-backends` | Every driver that loaded and, for each that did not, why. Each device's rates and buffer sizes. On Linux, whether realtime priority is available and how to get it. The first thing to run when there is too much delay. |
| `--list-devices` | The devices, per driver. |
| `--selftest --chain --stress <input> <output> 60` | Runs the real audio engine between two devices for 60 seconds with every pedal on and the computer under memory pressure, and reports whether it kept up. `<input>` and `<output>` are any part of the devices' names. Add `--backend=asio` or `--buffer=128` to choose. Watch `realtimeRatio`: below 1.0000 means sound was lost. Add `--tone` to send a test tone instead of your input. |
| `--measure <input> <output>` | With a cable patched from the interface's output back into its input, measures the true round trip. |

The README on the project page has more on these for the technically minded.

## Glossary

**ASIO.** A Windows driver standard made for music software. Your interface's maker provides it. The
lowest-latency way to reach an interface on Windows.

**Buffer.** The block of sound the program works on at a time, measured in frames. Smaller is less
delay and less margin.

**Capture.** A `.nam` file: the sound of one real amplifier learned by the Neural Amp Modeler
project, so it can be played back on any guitar.

**Dropout.** A moment of lost sound, heard as a click or a stutter, when the computer missed its
deadline for a buffer.

**Frame.** One sample of sound, at the sample rate. At 44 100 Hz there are 44 100 frames a second,
and 64 frames is 1.45 ms.

**Impulse response, IR.** A `.wav` file holding the sound of one speaker cabinet with one microphone.
Applied to an amp's output, it makes the amp sound like it is coming through that cabinet.

**Interface.** The box between the guitar and the computer, with the guitar socket, the headphone
socket and the USB lead.

**Latency.** The delay between playing a note and hearing it. Round trip means in and out together.

**Lane.** One of the loop box's four tracks.

**NAM.** Neural Amp Modeler, the project behind `.nam` captures, and the name of the pedal that
loads them.

**Preset.** A saved sound: pedal order, switches, knobs, dropdowns and files.

**Realtime priority.** Permission for the audio work to run ahead of everything else on the computer.
Automatic on Windows. An account setting on Linux.

**Sample rate.** How many times a second the sound is measured, in hertz. 44 100 and 48 000 are
usual.

**Session.** Everything the program puts back when you reopen it: the sound, the devices, the
metronome.

**Split.** Two boards fed the same guitar and heard together.

**WASAPI.** Windows' own sound system. Exclusive mode gives the program sole use of a device and low
delay; shared mode leaves it available to other programs at higher delay.

**Xrun.** A dropout reported by the driver, counted in the health readout.
