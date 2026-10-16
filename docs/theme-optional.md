# Vintage Mode — an optional skeuomorphic theme

**Status: proposal. Nothing here is built.** Written 2026-08-12 against 1.0.46-alpha as a plan to
review later, not as a commitment.

The idea: a **Vintage Mode** toggle under ☰, off by default, which re-skins the whole application so
the pedals, knobs, switches and board read as real hardware rather than as flat panels. The setting
persists in `Config`, like `hideDisabledModules`.

---

## 1. Why this is more achievable here than it looks

Two things about the existing code decide the whole plan, and both are in our favour.

**The metaphor is already built.** The app is a pedal board with pedals in signal order, patch cables
with jacks and directional arrows, knobs, toggle switches, and a pick-up-and-drop drag that carries
its own shadow. Skeuomorphism is not being introduced — the *structure* is already skeuomorphic and
only the *materials* are flat. That is a far smaller gap than inventing the metaphor.

**We already paint nearly every pixel ourselves.** Measured at 1.0.46-alpha — 7,191 lines under
`ui/`, of which the custom-painted components are:

| File | Lines | What it paints |
| --- | ---: | --- |
| `ChainBoard.kt` | 880 | Board surface, cables, jacks, arrows, drag gap |
| `EffectPanel.kt` | 759 | Pedal enclosure, header, shadow, glyph icons |
| `Knob.kt` | 375 | Knob body, pointer, arc |
| `TitleBar.kt` | 362 | Window chrome |
| `ConfirmDialog.kt` | 286 | Dialog frame and buttons |
| `PushButton.kt` | 258 | Start / Stop / Rescan / Rec |
| `DarkMenu.kt` | 241 | ☰ menu and its popups |
| `DarkComboBoxUI.kt` | 160 | Device, rate and buffer pickers |
| `DarkChrome.kt` | 157 | Shared chrome painting |
| `LevelMeter.kt` | 102 | Output meter |
| `ToggleSwitch.kt` | 82 | Per-pedal bypass switch |
| `SlimScrollBarUI.kt` | 76 | Board scrollbar |

Nimbus is forced in `Theme.install()` only because it honours colour overrides on both Windows and
Linux; it is a base, not the thing doing the drawing. **This removes the usual reason skeuomorphic
Swing themes fail** — there is no stock combo box or menu popup showing through to break the
illusion, because we already replaced them.

---

## 2. Decision: no bitmap assets. Everything procedural.

Painted in Java2D — gradients, radial highlights, procedurally generated `TexturePaint` tiles for
brushed metal and Tolex, layered translucent shapes for soft shadows (the technique `EffectPanel`
already uses for the lifted pedal). Four reasons, heaviest first.

### 2.1 Pedals are dynamically sized, and bitmaps cannot follow

`EffectPanel.applyPreferredSize()` computes a pedal's width from its knob count and its header
content, and an **FX slot changes width when its type changes** — at runtime, from a dropdown. A
photographed enclosure would need 9-slice scaling, and 9-slicing a lit, textured surface is precisely
where fakery becomes visible: the highlight stretches, the grain stretches, and the eye catches it
immediately. Procedural painting re-renders correctly at whatever size the layout asks for.

### 2.2 Size

The fat jar today is **5.5 MB**, almost entirely JNA. A realistic bitmap set — enclosure faces, knob
rotation sprite sheets (the conventional 61–128 frame approach), switches, LEDs, screws, board
surface — lands around **20–80 MB**. Before HiDPI: `sun.java2d.uiScale.enabled=true` is already set,
so @1x/@2x/@3x variants would be needed or the art goes soft on scaled displays, and that is where
the multiplier comes from. Net effect: **5–15× the current application size.**

Procedural costs a few tens of KB of class files. Effectively zero.

### 2.3 Licence — every asset has an author

The project is **GPLv3-or-later**. CC0 / public domain assets are safe. CC-BY-4.0 is GPLv3-compatible,
and CC-BY-SA-4.0 is compatible one way — its material can be folded into a GPLv3 work — but both
carry attribution terms that would have to travel in the jar and in the Licences dialog. Most good
asset packs are under none of those, and each one that is not needs the same reading JNA got before
its election went into README.md and `../build.gradle.kts`.

Separately from copyright: photographic textures of real pedals raise **trade dress and trademark**
questions that no image licence resolves. We must not ship something wearing a real manufacturer's
distinctive look — badge, colourway, or overall get-up.

Painting it ourselves sidesteps both, and matches how this project already handles this class of
problem: no Steinberg SDK, `IASIO` hand-declared from its published vtable.

### 2.4 The one asset worth bundling: a font

A period-correct typeface carries a disproportionate share of the impression. **SIL OFL** fonts are
safe to bundle alongside software under any licence. Budget **50–300 KB**. This is the only asset
this plan proposes adding, and it is optional even so — Phase 4.

---

## 3. Architecture: a `Skin` seam

`Theme.kt` is 82 lines of colour constants read directly by every component. **A material cannot be
expressed as a constant**, so the work is an indirection, not a palette swap.

Introduce a `Skin` with the surfaces that carry the look:

```
paintPedalBody(g2, width, height, state)
paintKnob(g2, size, value, state)
paintSwitch(g2, width, height, on)
paintBoardSurface(g2, width, height)
paintCable(g2, from, to, routing)
paintMeter(g2, ...)
```

Today's flat look becomes the default implementation; Vintage is the second. `Theme`'s constants stay
for text and accents and are read *through* the skin.

This is the same shape as the `AudioBackend` seam, with the same payoff: a seam validated by one
implementation is probably the wrong seam. Two is evidence.

**Toggling at runtime:** rebuild the window. All state lives in the effects, the chain and `Config`,
so nothing is lost, and it avoids chasing every component for a live re-skin. `Config.vintageMode`
sits beside `hideDisabledModules`.

---

## 4. Performance rules, which are not optional here

Painting happens on the EDT, so there is no *direct* realtime risk. The indirect one is real and is
already documented in CLAUDE.md: **allocation is what triggers the collection that stops the audio
thread**, which is attached and cannot opt out of a safepoint.

Building gradients and textures inside `paintComponent` at 60 fps during a drag would be measurable.
So:

- **Pre-render each pedal's static enclosure** to a `BufferedImage` cached by `(width, height,
  state)`, and blit it. Paint live only the parts that move: knob pointer, meter, LED, fade.
- **Invalidate that cache on resize** — which now happens on an FX type change *and* during the
  board animations added in 1.0.43.
- Generate `TexturePaint` tiles **once**, at skin construction, never per paint.
- Verify with the existing counter: `realtimeRatio`, not `deadlineMisses`. A skin that costs the
  audio thread will show there and nowhere else.

---

## 5. Phases

Roughly 90 % of the impression comes from three surfaces. Phase 1 exists to get those in front of a
human early, because **the acceptance criterion for this feature is taste, not a test** — see §7.

### Phase 1 — the seam, and three surfaces
- `Skin` interface; current look extracted into `FlatSkin` with **no visual change** (assert this by
  eye before going further — if Phase 1 changes how the app looks today, the extraction is wrong).
- `VintageSkin` implementing: **pedal enclosure**, **knob**, **bypass switch**.
- `Config.vintageMode` + ☰ toggle + window rebuild.
- Enclosure image cache with correct invalidation.

**Exit criteria:** toggle works, survives a restart, nothing in `FlatSkin` looks different from
today, `realtimeRatio` unchanged at 64 frames with `--selftest --chain --stress`.

### Phase 2 — the board
- Board surface material, cables as physical patch leads with shading and a real jack, plate
  screws, drop shadows under seated pedals.
- The lifted-pedal shadow re-done in the new material language.

### Phase 3 — chrome
- Title bar, ☰ menu, combo pickers, push buttons, confirm dialog, scrollbar in the vintage language.
- This is the tail that decides whether the illusion holds or collapses at the edges.

### Phase 4 — typography and detail
- Optional OFL font.
- Screen-printed labels, wear, LED bloom, meter lamp.

---

## 6. What this plan does not cover

- **Linux appearance.** Nothing here has run on Linux at all. Procedural painting is the portable
  choice, but the font fallback and DPI behaviour are unverified there.
- **Accessibility.** A darker, lower-contrast vintage palette may read worse than the current theme.
  Contrast should be checked, and the flat theme remains the default partly for this reason.
- **Per-module identity.** Whether each module gets its own enclosure colour is a design question
  left open — see §8.

---

## 7. The risk that makes this unlike the other work

Everything else delivered recently could be *verified*: a test passing, a millisecond figure, a
cache clearing, an allocation count. **This cannot.** "Does this knob look machined, or does it look
like a circle with a gradient on it" is answerable only by looking at it, in a warmer/colder loop
over several rounds with a human in it.

Budget review time, not just build time. Phase 1 is deliberately small so that loop starts early and
cheaply, and so abandoning the idea after seeing it costs little.

---

## 8. Open questions

1. **Per-module colour, or one enclosure?** Real boards are a jumble of colours; a uniform enclosure
   looks more like one product. Which is wanted?
2. **How far does "vintage" go** — clean 1970s studio hardware, or worn and scuffed with visible age?
   They are different amounts of work and different impressions.
3. **Does the FX slot dropdown stay a dropdown** in vintage mode, or become something more physical
   (a rotary selector)? A dropdown is the one control with no hardware analogue on a pedal.
4. **Is the flat theme still the default forever**, or does vintage become the default once it is
   good enough?
