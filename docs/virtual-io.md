# Virtual I/O — routing Omega into and out of other apps

Two things are wanted:

1. **Hear another app through Omega.** Discord plays into Omega, and it comes out of whatever Omega
   is playing to, mixed with the guitar and **with no effects applied to it**.
2. **Let another app hear Omega.** Discord, OBS or a DAW takes Omega's processed guitar as if it
   were a microphone.

---

## 1. What cannot be done

**Omega cannot create audio devices on Windows.** Making "Omega Input" and "Omega Output" appear in
Windows' device list — so Discord can pick them — requires a **kernel-mode audio driver** (WDM /
PortCls or AVStream). That is what VB-Audio Cable, Virtual Audio Cable and VoiceMeeter are. There is
no user-space API for registering an endpoint, from the JVM or from anything else.

Shipping such a driver would mean:

- Writing it in C against the Windows Driver Kit — a separate codebase from this one.
- **Microsoft signing.** Since Windows 10 x64, kernel drivers must be signed through Partner Center
  attestation, which needs an EV code-signing certificate (roughly $300–500 a year) and a company
  identity. Self-signing only works with test-signing mode enabled, which is not a thing to ask a
  guitarist to do.
- An installer and administrator rights. Omega stops being "a jar you run".
- Ongoing maintenance against Secure Boot, HVCI and each Windows release.

There is also a licensing wrinkle worth noting given this project is GPLv3-or-later: a driver anyone
can *build* but nobody can *sign* is not really a driver anyone can use.

Worth knowing that this is a Windows problem specifically:

| Platform | User-space virtual devices? |
| --- | --- |
| Windows | **No.** Kernel driver, signed. |
| Linux | **Yes, trivially.** PipeWire and PulseAudio create null sinks on request. |
| macOS | **Yes.** `AudioServerPlugIn` runs in user space — it is how BlackHole works. |

So if this is ever ported, the feature is nearly free on Linux and cheap on macOS. On Windows it is a
driver or nothing.

---

## 2. What can be done instead

Omega does not have to *create* the pipe. It only has to *use* one — and the machine already has
VB-Audio Cable installed.

Rather than inventing devices, Omega grows two extra audio paths:

- **Aux Send** — a second output. Omega's processed signal is written there as well as to your
  headphones. Point it at a virtual cable's render end, and any app can pick up that cable's capture
  end as its microphone. *This is "Omega Output".*
- **Monitor Return** — a second input. Captured and mixed **dry** into Omega's main output, after
  the effect chain, so nothing is applied to it. Point it at a virtual cable's capture end and have
  Discord play into that cable. *This is "Omega Input".*

From the user's side the experience is the same as the original idea. The difference is that the
pipe is supplied by a cable driver they install once, rather than by us.

```
guitar ──► Omega input ──► effect chain ──┬──► main output ──► headphones
                                          │                        ▲
                                          └──► Aux Send ──► CABLE A ├─► Discord mic
                                                                    │
Discord ──► CABLE B ──► Monitor Return ── dry ──────────────────────┘
```

### Two cables, not one

A single VB-Audio Cable is one pipe with a render end and a capture end. Using it for both
directions would feed Omega's output straight back into its own input. Doing both directions at once
needs **two independent cables** — VB-Audio's Cable A/B add-on, VoiceMeeter's cables, or any second
virtual cable driver.

Using one direction only needs one cable, and that is probably the common case.

### The send must exclude the return

Whatever arrives on the Monitor Return must **not** be copied to the Aux Send, or Discord hears
itself with a round-trip delay. The send is tapped after the chain and after master, but before the
return is mixed in.

---

## 3. The hard part: three clocks

The main duplex stream has one clock. The Aux Send and the Monitor Return each have their own, and
none of them agree — crystals drift by tens of parts per million.

This is not theoretical. Measured on this machine already: two *different* physical devices on
WASAPI at the smallest period produced **7.7 xruns per second** at 144 frames and 3.9 at 288. That
is exactly the same problem, and it is why `CushionRegulator` exists in the Java Sound backend.

The design has to answer it directly:

- Each aux path gets a **lock-free ring** between its own thread and the audio callback. The
  callback pushes to the send ring and pops from the return ring; both are wait-free, allocate
  nothing, and never block. A starved return reads silence rather than waiting.
- The aux paths run at a **much larger buffer than the guitar path** — 20–50 ms. They are not
  latency-critical: nobody needs their Discord chat five milliseconds earlier, and that cushion is
  what absorbs the drift.
- Aux endpoints open in **shared mode**, not exclusive. They must coexist with the apps at the other
  end of the cable, and a virtual cable in exclusive mode would lock out the very app it exists to
  carry.
- Long-run drift is corrected the way the Java Sound path already does it: splice a frame in or out
  at the quietest point in a block, rate-limited, only when the ring approaches an end.

**The guitar path must not be affected.** No extra latency, no extra jitter, and if an aux path dies
the main stream carries on. That is the acceptance test.

---

## 4. What it looks like in the app

A **VIRTUAL I/O** toggle in the device bar. Off by default and off costs nothing — no threads, no
endpoints opened.

Switched on, two more pickers appear:

- **SEND TO** — a render device. "None" is valid.
- **MONITOR FROM** — a capture device. "None" is valid.
- A dry **MONITOR** level knob, so incoming chat can be balanced against the guitar.

Both lock while streaming, like every other device control. Both list only devices the current
backend can see. If neither is set the toggle is on but idle, which is honest rather than clever.

When a virtual cable is detected by name, the status line can say so — *"CABLE Input found; select
CABLE Output as your microphone in Discord"* — because the single hardest part of this feature for
a user is working out which end goes where.

---

## 5. What this does not solve

- It needs a cable driver installed. Omega can detect and explain, but not supply.
- Two-way needs two cables.
- The monitor path adds the aux buffer's latency to whatever is coming in — irrelevant for chat,
  wrong for anything that needs to stay in time with your playing.
- Nothing here makes the exclusive-mode lockout go away. If Omega holds the headset exclusively,
  Discord still cannot play to it directly — the monitor path is what gets its audio in, and it
  arrives through the cable rather than through the headset. A **WASAPI shared** backend remains the
  simpler answer when coexisting matters more than latency.

---

## 6. Recommendation

Build the routing. It gets both use cases working with the cable already on this machine, it is
ordinary user-space audio code of a kind the backend seam already supports, and it carries no
signing, no installer and no kernel.

Treat the driver as a separate question, and probably a "no" — the cost is not the code, it is the
certificate, the installer and owning a kernel component forever.

Sequence:

1. **Aux Send** first. It is the simpler half — one extra output, one ring, no mixing decisions —
   and it delivers "other apps can hear my playing" on its own.
2. **Monitor Return** second, with the dry mix and the level control.
3. **Cable detection and guidance** last, once both paths work.

Before any of it, a **WASAPI shared backend** is worth doing, is much smaller, and independently
fixes the complaint that started this: that Omega locks the headset while it runs.
