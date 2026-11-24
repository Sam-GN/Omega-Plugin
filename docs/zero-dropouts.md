# Towards zero dropouts

Findings of 2026-11-23/24 on one laptop (Framework, i7-1165G7, Manjaro 7.2, PipeWire 1.6.9, Pocket
Master into `Default`, 256 frames, ALSA), and the plan that came of them. §0 is the current status and
overrides the text below it.

## 0. Status

| Item | State |
| --- | --- |
| Z1 Faster NAM | built in 2.3.2, not yet heard; see §4 |
| Z2 Count every dropout | built in 2.3.2 (ALSA), not yet run on a device; see §4 |
| Z3 Settle the drift follower | open; needs a log from Z2 |
| Z4 Realtime hardening | open, and **next**: §4 shows the CPU clock is now the larger half |
| Z5 A Safe mode | open |
| Z6 One-device and JACK paths | open |
| Z7 Soak test | open |

## 1. What was measured

**From the log, 2.3.1, full NAM capture loaded:**

| | 44.1 kHz | 48 kHz (input converted) |
| --- | ---: | ---: |
| Length | 2 min 22 s | 3 min 13 s |
| Counted dropouts (xruns) | 0 | 2 |
| Callbacks over the deadline | 7 | 2 |
| Worst callback / period | 8.8 / 5.8 ms | 8.5 / 5.3 ms |
| Load at the moment sampled | 50–56 % | 54–57 % |

The player heard **more** dropouts at 44.1, where the counter said none, and **none in over a minute
without NAM**. Logged sessions before 2026-11-17 never had a callback over 1 ms.

**Offline, no audio device, normal priority**, the three captures in use (`SlimmableContainer`, full
size, 11 776 multiply-adds a sample): median 2.3–3.0 ms of a 5.3–5.8 ms block, and 8 to 15 blocks in
4 000 over the period when paced as a stream paces them.

## 2. What it means

- **Confirmed: NAM.** It spends about half of every block, so anything that stalls the thread for the
  other half is a dropout. Not shown to be the only cause.
- **Dropouts go uncounted.** PipeWire's ALSA plugin may run dry without returning an error. Which of
  that and a late callback was heard at 44.1 is not known.
- **The 44.1 drift follower is not settled**: -250 ppm at one look, -36 at another, 1.2 ms under its
  target at the first. It should sit within tens of ppm.
- Other things against it, not measured separately: the audio thread is `SCHED_FIFO` 20 through rtkit
  while threaded interrupts (Wi-Fi among them) run at 50; the CPU governor is `powersave`, and its
  deepest idle state takes 1 ms to leave; memory is not locked; `Default` adds PipeWire's buffering.

## 3. The plan

- **Z1 Faster NAM.** It ran sample by sample. A block-wise layout of the dilated convolution ran about
  2.6 times faster with identical output in a prototype.
- **Z2 Count every dropout**: late callbacks and an output queue found empty, each with its cause.
- **Z3 Settle the follower** against what Z2 logs.
- **Z4 Realtime hardening**: lock memory, hold CPU wake latency down while streaming, say when the
  priority is low.
- **Z5 A Safe mode**: two or three output periods, chosen by the player, at 5–6 ms each at 256.
- **Z6 One device both ways, or JACK**: one clock, no PipeWire plugin.
- **Z7 Soak**: zero counted dropouts in an hour on a real preset under load.

Zero cannot be promised on a desktop OS and a JVM. The target is none in an hour at 256 frames with a
full capture.

**Without code**: NAM's SIZE under 0.5 picks the small capture at about 15 % of the cost; join the
`realtime` group (`sudo usermod -aG realtime <user>`, then log in again); the performance power profile
while playing; the interface for both input and output.

## 4. What 2.3.2 did

**Z1.** `WaveNet` runs each stage over up to 128 frames before the next. Held to the old code's output
bit for bit in `WaveNetBlockTest`. Offline, 256 frames at 44.1 kHz, normal priority, three captures:

| | Before | 2.3.2 |
| --- | ---: | ---: |
| Flat out, median | 2.6–3.0 ms | 1.2–1.5 ms |
| Paced as a stream paces it, median | 2.7–3.0 ms | 2.5 ms |
| Blocks over the period, of 4 000 | 11–14 | 0–4 |

**The paced median hardly moved because the CPU slows down to meet the lighter load**: the busiest core
read 4.5 GHz flat out and 2.4 GHz paced, on the `balanced` power profile. The work is half of what it
was; the clock takes most of the saving back. So Z4 should begin with holding the clock up while a
stream runs — `power-profiles-daemon` lets a program hold the performance profile over D-Bus and
releases it when the program exits. Not tried.

**Z2.** `DropoutCounters` on ALSA: periods longer than they last, split into *slow* (the thread ran for
80 % of it or more: the pedals) and *kept off the CPU*; the output queue found empty when a period was
ready, after a late period or otherwise; the least ever queued; the longest gap between writes. The log
gets a `Dropout watch` line within a quarter second of any of them. An empty queue is a suspect and
not in the xrun count, because a server's lumps may leave exactly nothing without a gap. Nothing here
sees inside PipeWire: a gap it plays on its own side still goes uncounted.
