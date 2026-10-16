/*
 * Omega-Plugin: a realtime guitar effects processor.
 * Copyright (C) 2026  Sam Ganji <sam.ganji.n@gmail.com>
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.example.dsp.rhythm

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The transport, asserted against arithmetic worked out here rather than against what it returns.
 *
 * Every timing figure below is derived from the tempo and the sample rate on the spot — 130 BPM at
 * 44.1 kHz is 60 × 44100 ÷ 130 = 20 353.846 samples a beat — so a change that made the clock wrong
 * cannot make the test agree with it.
 */
class TransportTest {

    private val rate = 44_100f
    private val block = 64

    // ---- helpers ------------------------------------------------------------

    /** Runs the transport for [seconds] and returns everything it produced, one block at a time. */
    private fun render(transport: Transport, seconds: Double, rate: Float = this.rate): FloatArray {
        val total = (seconds * rate).toInt()
        val out = FloatArray(total)
        val scratch = FloatArray(block)
        var at = 0
        while (at + block <= total) {
            java.util.Arrays.fill(scratch, 0f)
            transport.mix(scratch, block)
            System.arraycopy(scratch, 0, out, at, block)
            at += block
        }
        return out
    }

    /**
     * Sample indices where a new tick started.
     *
     * Deduplicated by a refractory gap rather than by "went quiet first", which was the obvious
     * version and was wrong: a tick is a *sine* burst, so it passes through zero every half cycle,
     * and once its envelope decays near the threshold those crossings sit below the floor for long
     * enough to read as the end of one tick and the start of the next. It counted 22 onsets in
     * 8 beats.
     *
     * [MIN_GAP] has to clear the whole burst, not merely one cycle of it: a tick decays 60 dB in
     * 40 ms, so it stays over a 0.05 floor for about 17 ms — 760 samples — and a gap of 512 split
     * every tick in two and doubled every count. 2 048 samples is 46 ms, past the end of any burst
     * and still far inside the closest steps these tests ask for (triplets at 90 BPM are 9 800
     * samples apart). The reported index is the first sample over the threshold, which lags the
     * trigger by under two samples and by the same amount every time, so intervals stay exact.
     */
    private fun onsets(signal: FloatArray, threshold: Float = 0.05f): List<Int> {
        val found = ArrayList<Int>()
        var last = -MIN_GAP
        for (i in signal.indices) {
            if (abs(signal[i]) >= threshold && i - last >= MIN_GAP) {
                found += i
                last = i
            }
        }
        return found
    }

    private fun playing(
        signature: TimeSignature = TimeSignature.FOUR_FOUR,
        feel: String = "Click",
        bpm: Double = 120.0,
        rate: Float = this.rate,
    ): Transport = Transport().apply {
        prepare(rate)
        this.signature = signature
        pattern = assertNotNull(Feels.find(signature, feel), "no such feel: $feel")
        tempoBpm = bpm
        level = 1f
        start()
    }

    // ---- the clock ----------------------------------------------------------

    @Test
    fun `plain click fires once per beat at the tempo asked for`() {
        // 120 BPM for 4 s is 8 beats. The first lands at sample 0, so 8 onsets.
        val hits = onsets(render(playing(bpm = 120.0), 4.0))
        assertEquals(8, hits.size, "expected 8 beats in 4 s at 120 BPM, got ${hits.size}")
    }

    /**
     * The regression guard for rounding the beat to a whole number of samples.
     *
     * 130 BPM at 44.1 kHz is 20 353.846 samples — deliberately not an integer. Truncating each beat
     * would lose 0.846 samples every time, so by the 60th beat the error is about 50 samples and by
     * the 130th about 109. Three samples of tolerance passes the fractional implementation and fails
     * every rounding one.
     */
    @Test
    fun `a fractional beat length does not accumulate error`() {
        val samplesPerBeat = 60.0 * rate / 130.0
        val hits = onsets(render(playing(bpm = 130.0), 60.0))
        assertTrue(hits.size >= 120, "only ${hits.size} beats in 60 s at 130 BPM")

        val last = hits.size - 1
        val expected = hits[0] + last * samplesPerBeat
        val error = abs(hits[last] - expected)
        assertTrue(
            error < 3.0,
            "beat $last landed at ${hits[last]}, expected ${"%.1f".format(expected)} " +
                "— drifted ${"%.1f".format(error)} samples",
        )
    }

    @Test
    fun `tempo is a time, not a sample count — 96 kHz agrees with 44_1`() {
        val at441 = onsets(render(playing(bpm = 100.0, rate = 44_100f), 3.0, 44_100f))
        val at96 = onsets(render(playing(bpm = 100.0, rate = 96_000f), 3.0, 96_000f))
        assertEquals(at441.size, at96.size, "a rate change altered the number of beats in 3 s")

        // Same instants, expressed in each rate's own samples.
        for (i in at441.indices) {
            val seconds441 = at441[i] / 44_100.0
            val seconds96 = at96[i] / 96_000.0
            assertTrue(
                abs(seconds441 - seconds96) < 0.001,
                "beat $i at ${seconds441}s and ${seconds96}s — the rate leaked into the clock",
            )
        }
    }

    @Test
    fun `subdivisions multiply the hits and leave the pulse alone`() {
        val plain = onsets(render(playing(feel = "Click", bpm = 90.0), 4.0))
        val eighths = onsets(render(playing(feel = "Click + 8ths", bpm = 90.0), 4.0))
        val triplets = onsets(render(playing(feel = "Click + triplets", bpm = 90.0), 4.0))

        assertEquals(plain.size * 2, eighths.size, "eighths should double the hits")
        assertEquals(plain.size * 3, triplets.size, "triplets should treble them")
        // Every pulse still lands where it did: the eighths pattern hits it on every other onset.
        for (i in plain.indices) {
            assertTrue(
                abs(plain[i] - eighths[i * 2]) <= 2,
                "pulse $i moved from ${plain[i]} to ${eighths[i * 2]} when eighths were added",
            )
        }
    }

    // ---- the bar ------------------------------------------------------------

    @Test
    fun `the lamp counts the pulses of the bar, not the notes in it`() {
        val seen = HashSet<Int>()
        val transport = playing(TimeSignature.THREE_FOUR, bpm = 200.0)
        val scratch = FloatArray(block)
        repeat(2_000) {
            transport.mix(scratch, block)
            if (transport.displayBeat >= 0) seen += transport.displayBeat
        }
        assertEquals(setOf(0, 1, 2), seen, "3/4 should light three lamps and only three")
    }

    @Test
    fun `compound signatures count their pulses, not their eighths`() {
        // 6/8 is two dotted-quarter pulses; 12/8 is four. If either counted eighths it would show
        // six and twelve, which is the mistake this exists to catch.
        assertEquals(2, TimeSignature.SIX_EIGHT.beatsPerBar)
        assertEquals(4, TimeSignature.TWELVE_EIGHT.beatsPerBar)
        assertTrue(TimeSignature.SIX_EIGHT.isCompound)
        assertTrue(TimeSignature.TWELVE_EIGHT.isCompound)
        assertTrue(!TimeSignature.FOUR_FOUR.isCompound)

        // And its natural division is three, so "Click + 6" is six hits to a 6/8 bar.
        val six = assertNotNull(Feels.find(TimeSignature.SIX_EIGHT, "Click + 6"))
        assertEquals(6, six.stepCount)
    }

    @Test
    fun `a sparse click leaves the silent beats silent`() {
        val transport = playing(feel = "Click on 2 & 4", bpm = 120.0)
        // Two hits a bar rather than four: 8 beats of time, 4 onsets.
        assertEquals(4, onsets(render(transport, 4.0)).size)
    }

    // ---- what it writes into the buffer -------------------------------------

    @Test
    fun `a stopped transport adds nothing at all`() {
        val transport = Transport().apply { prepare(rate); level = 1f }
        val buffer = FloatArray(block) { 0.25f }
        val untouched = buffer.copyOf()
        repeat(500) { transport.mix(buffer, block) }
        assertContentEquals(untouched, buffer, "a stopped transport must be bit-transparent")
    }

    /**
     * The tick is a sine and swings negative, so "the buffer stayed above the guitar's level" would
     * be the wrong assertion. Two identical transports, one mixed into silence and one into a
     * signal, must differ by exactly that signal.
     */
    @Test
    fun `the rhythm is added to the signal, never over it`() {
        val alone = playing(bpm = 240.0)
        val over = playing(bpm = 240.0)
        val empty = FloatArray(block)
        val carrying = FloatArray(block) { 0.5f }

        repeat(40) {
            java.util.Arrays.fill(empty, 0f)
            java.util.Arrays.fill(carrying, 0.5f)
            alone.mix(empty, block)
            over.mix(carrying, block)
            for (i in 0 until block) {
                assertTrue(
                    abs((carrying[i] - empty[i]) - 0.5f) < 1e-5f,
                    "sample $i: ${carrying[i]} against ${empty[i]}; mix() must add, not replace",
                )
            }
        }
    }

    @Test
    fun `level scales the bus and zero silences it`() {
        val loud = render(playing(bpm = 240.0).apply { level = 1f }, 1.0)
        val quiet = render(playing(bpm = 240.0).apply { level = 0.25f }, 1.0)
        val off = render(playing(bpm = 240.0).apply { level = 0f }, 1.0)

        val loudPeak = loud.maxOf { abs(it) }
        val quietPeak = quiet.maxOf { abs(it) }
        assertTrue(loudPeak > 0.1f, "nothing came out at full level")
        assertTrue(
            abs(quietPeak - loudPeak * 0.25f) < 0.01f,
            "quarter level gave $quietPeak against $loudPeak at full",
        )
        assertEquals(0f, off.maxOf { abs(it) }, "level 0 must be silent")
    }

    // ---- count-in -----------------------------------------------------------

    /**
     * The bug this pins was reported twice, and the second time was the important one: **pressing
     * Record must never start the metronome.**
     *
     * The first version started the transport so the count-in had something to count with, and left
     * it running for the whole take. The second stopped it afterwards — which still meant a bar of
     * clicking nobody had asked for. Arming a stopped transport is now refused outright, and the
     * caller records immediately instead.
     */
    @Test
    fun `arming a stopped transport is refused and makes no sound`() {
        val transport = Transport().apply {
            prepare(rate)
            tempoBpm = 120.0
            level = 1f
        }
        assertTrue(
            !transport.armForRecord(bars = 1),
            "a stopped transport accepted a count-in; Record would have started the metronome",
        )
        assertEquals(Transport.State.STOPPED, transport.state)

        val signal = render(transport, 4.0)
        assertEquals(
            0f,
            signal.maxOfOrNull { abs(it) } ?: 0f,
            "pressing Record produced clicks from a metronome that was not running",
        )
        assertTrue(!transport.consumeRecordDue(), "a refused arm still cued a recording")
    }

    @Test
    fun `a count-in leaves a transport that was already playing alone`() {
        val transport = playing(bpm = 120.0)
        val scratch = FloatArray(block)
        var elapsed = 0
        while (elapsed + block <= rate.toInt()) {
            transport.mix(scratch, block)
            elapsed += block
        }
        transport.armForRecord(bars = 1)

        var fired = false
        val limit = (4.0 * rate).toInt()
        while (elapsed + block <= limit) {
            transport.mix(scratch, block)
            if (transport.consumeRecordDue()) fired = true
            elapsed += block
        }
        assertTrue(fired, "the take never started")
        // The mirror of the case above: it was running before Record was pressed and nothing here
        // should stop it.
        assertEquals(
            Transport.State.PLAYING,
            transport.state,
            "recording stopped a metronome the user had started",
        )
    }

    @Test
    fun `two bars of count-in wait a bar longer than one`() {
        fun cueAt(bars: Int): Int {
            val transport = playing(bpm = 120.0)
            val scratch = FloatArray(block)
            // Half a bar in, so the arm lands mid-bar as a real one would.
            var elapsed = 0
            while (elapsed + block <= rate.toInt()) {
                transport.mix(scratch, block)
                elapsed += block
            }
            assertTrue(transport.armForRecord(bars), "a playing transport refused the arm")

            var firedAt = -1
            val limit = (8.0 * rate).toInt()
            while (elapsed + block <= limit) {
                transport.mix(scratch, block)
                if (transport.consumeRecordDue() && firedAt < 0) firedAt = elapsed
                elapsed += block
            }
            return firedAt
        }
        // A bar of 4/4 at 120 BPM is 2 s. Armed 1 s in, one bar cues at the 2 s line and two bars at
        // the 4 s line — a whole bar of lead-in more, which is what the option promises.
        val one = cueAt(1)
        val two = cueAt(2)
        assertTrue(one > 0 && two > 0, "no cue fired: $one, $two")
        assertTrue(
            abs((two - one) - 2.0 * rate) <= block * 2,
            "two bars cued at $two against one bar's $one — expected a bar of ${2.0 * rate} between",
        )
    }

    @Test
    fun `arming while already playing waits for the bar line rather than firing at once`() {
        val transport = playing(bpm = 120.0)
        val scratch = FloatArray(block)

        // Get to the middle of a bar — 1 s into a 2 s bar.
        var elapsed = 0
        while (elapsed + block <= rate.toInt()) {
            transport.mix(scratch, block)
            elapsed += block
        }
        transport.armForRecord(bars = 1)
        assertEquals(Transport.State.PLAYING, transport.state, "arming must not restart the transport")

        var firedAt = -1
        val limit = (3.5 * rate).toInt()
        while (elapsed + block <= limit) {
            transport.mix(scratch, block)
            if (transport.consumeRecordDue() && firedAt < 0) firedAt = elapsed
            elapsed += block
        }
        // The next downbeat after 1 s is at 2 s, not 3 s: it waits for the bar line it is closest
        // to reaching, not for a whole bar to elapse.
        val expected = 2.0 * rate
        assertTrue(
            firedAt >= 0 && abs(firedAt - expected) <= block * 2,
            "cue at $firedAt, expected the bar line at ${expected.toInt()}",
        )
    }

    @Test
    fun `disarming drops the cue and leaves the transport playing`() {
        val transport = playing(bpm = 120.0)
        assertTrue(transport.armForRecord(bars = 1))
        transport.disarm()

        val scratch = FloatArray(block)
        repeat(4_000) { transport.mix(scratch, block) }
        assertTrue(!transport.consumeRecordDue(), "a disarmed transport still cued a recording")
        // Abandoning a take must not silence a metronome the user started.
        assertEquals(Transport.State.PLAYING, transport.state)
    }

    // ---- the catalogue ------------------------------------------------------

    @Test
    fun `every signature offers a plain click and it is the fallback`() {
        for (signature in TimeSignature.entries) {
            val feels = Feels.forSignature(signature)
            assertTrue(feels.isNotEmpty(), "$signature has no feels")
            assertEquals("Click", feels.first().name, "$signature does not lead with a plain click")
            assertSame(
                feels.first(),
                Feels.defaultFor(signature),
                "$signature falls back to something other than its plain click",
            )
        }
    }

    @Test
    fun `every pattern's step table matches the bar it claims`() {
        for (pattern in Feels.all()) {
            assertEquals(
                pattern.signature.beatsPerBar * pattern.stepsPerBeat,
                pattern.stepCount,
                "${pattern.signature} ${pattern.name} has a step table of the wrong length",
            )
            for (step in 0 until pattern.stepCount) {
                for (hit in 0 until pattern.hitCount(step)) {
                    val voice = pattern.voiceAt(step, hit)
                    assertTrue(
                        voice in Voice.entries.indices,
                        "${pattern.name} step $step names voice $voice, which does not exist",
                    )
                    val velocity = pattern.velocityAt(step, hit)
                    assertTrue(
                        velocity > 0f && velocity <= 1f,
                        "${pattern.name} step $step has velocity $velocity",
                    )
                }
            }
        }
    }

    // ---- the kit ------------------------------------------------------------

    /**
     * A one-voice bar, for testing a voice rather than a pattern.
     *
     * Built through [Pattern]'s own constructor so it goes through the same length checks the
     * catalogue does; the point is to isolate a voice, not to bypass anything.
     */
    private fun single(voice: Voice, velocity: Float = 1f, swing: Float = 0f): Pattern {
        val steps = 4
        return Pattern(
            name = "only ${voice.name}",
            signature = TimeSignature.FOUR_FOUR,
            stepsPerBeat = 1,
            voices = Array(steps) { intArrayOf(voice.ordinal) },
            velocities = Array(steps) { floatArrayOf(velocity) },
            swing = swing,
        )
    }

    /**
     * How bright a signal is: the RMS of its sample-to-sample difference over its own RMS.
     *
     * Derived rather than measured. Differencing a sine of frequency f at rate r scales it by
     * roughly `2π·f/r`, so a 60 Hz kick comes out near 0.009 and an 8 kHz hat near 1.1 — two orders
     * of magnitude apart, which is what makes this enough to tell a kick from a hi-hat without
     * writing a spectrum analyser.
     *
     * It is the assertion that catches `Transport.voices` being ordered differently from the [Voice]
     * enum, which is a mistake that leaves every pattern playing perfectly in time with a kick
     * coming out of the hi-hat.
     */
    private fun brightness(signal: FloatArray): Double {
        var energy = 0.0
        var difference = 0.0
        for (i in 1 until signal.size) {
            energy += signal[i].toDouble() * signal[i]
            val d = signal[i] - signal[i - 1]
            difference += d.toDouble() * d
        }
        if (energy <= 0.0) return 0.0
        return Math.sqrt(difference / energy)
    }

    private fun renderOnly(voice: Voice, seconds: Double = 2.0): FloatArray {
        val transport = Transport().apply {
            prepare(rate)
            signature = TimeSignature.FOUR_FOUR
            pattern = single(voice)
            tempoBpm = 120.0
            level = 1f
            clickOverDrums = false
            start()
        }
        return render(transport, seconds)
    }

    @Test
    fun `each voice sounds, and sounds like itself`() {
        val measured = Voice.entries.associateWith { brightness(renderOnly(it)) }
        for ((voice, value) in measured) {
            assertTrue(
                renderOnly(voice).any { abs(it) > 0.01f },
                "$voice produced no sound at all",
            )
            assertTrue(value > 0.0, "$voice produced nothing to measure")
        }

        // The kick is a swept sine ending near 48 Hz: 2π·48/44100 is about 0.007, so anything above
        // 0.1 means the array is not pointing where the enum says.
        assertTrue(
            measured.getValue(Voice.KICK) < 0.10,
            "the kick measured ${measured.getValue(Voice.KICK)} — too bright to be a kick drum",
        )
        // The hats are noise above a 7.5 kHz corner; 2π·7500/44100 is about 1.07.
        for (hat in listOf(Voice.HAT_CLOSED, Voice.HAT_OPEN)) {
            assertTrue(
                measured.getValue(hat) > 0.8,
                "$hat measured ${measured.getValue(hat)} — too dark to be a hi-hat",
            )
        }
        // Ordering, stated as the thing that actually matters: dark to bright, kick to hat.
        assertTrue(
            measured.getValue(Voice.KICK) < measured.getValue(Voice.SNARE),
            "the snare is darker than the kick; the voice array is out of order",
        )
        assertTrue(
            measured.getValue(Voice.SNARE) < measured.getValue(Voice.HAT_CLOSED),
            "the closed hat is darker than the snare; the voice array is out of order",
        )
    }

    @Test
    fun `an open hat rings for longer than a closed one`() {
        // One hit, then silence: measure how long each takes to fall away.
        fun tail(voice: Voice): Int {
            val transport = Transport().apply {
                prepare(rate)
                pattern = single(voice)
                tempoBpm = 30.0                       // 2 s a beat, so the hits never overlap
                level = 1f
                clickOverDrums = false
                start()
            }
            val signal = render(transport, 1.0)
            return signal.indexOfLast { abs(it) > 0.01f }
        }
        val closed = tail(Voice.HAT_CLOSED)
        val open = tail(Voice.HAT_OPEN)
        assertTrue(open > closed * 3, "open hat rang $open samples against the closed hat's $closed")
    }

    @Test
    fun `the kit stays inside the buffer at full level`() {
        for (pattern in Feels.all().filter { !it.isClickOnly }) {
            val transport = Transport().apply {
                prepare(rate)
                signature = pattern.signature
                this.pattern = pattern
                tempoBpm = 140.0
                level = 1f
                clickOverDrums = true
            }
            transport.start()
            val peak = render(transport, 4.0).maxOf { abs(it) }
            assertTrue(peak > 0.05f, "${pattern.name} produced nothing")
            // The rhythm bus is added to the guitar and only then clamped, so a pattern that on its
            // own reached full scale would leave nothing for the instrument.
            assertTrue(peak < 1f, "${pattern.name} peaked at $peak with the click layered over it")
        }
    }

    @Test
    fun `drum patterns exist where they make sense and not where they do not`() {
        fun named(signature: TimeSignature) = Feels.forSignature(signature).map { it.name }
        assertTrue(named(TimeSignature.FOUR_FOUR).containsAll(listOf("Rock", "Half-time", "Shuffle")))
        assertTrue(named(TimeSignature.THREE_FOUR).contains("Waltz"))
        assertTrue(named(TimeSignature.SIX_EIGHT).contains("Ballad"))
        assertTrue(named(TimeSignature.TWELVE_EIGHT).contains("Slow blues"))
        // 2/4's march is a click on 1 and 2, which the click styles already are.
        assertTrue(Feels.forSignature(TimeSignature.TWO_FOUR).all { it.isClickOnly })
        // Clicks come first in every list, so the dropdown never opens on a drum kit.
        for (signature in TimeSignature.entries) {
            val feels = Feels.forSignature(signature)
            val firstGroove = feels.indexOfFirst { !it.isClickOnly }
            if (firstGroove < 0) continue
            assertTrue(
                feels.drop(firstGroove).none { it.isClickOnly },
                "$signature interleaves click styles with drum patterns",
            )
        }
    }

    // ---- swing --------------------------------------------------------------

    /**
     * Swing must lengthen the on-beat and shorten the off-beat by the same amount, so the *pulse*
     * never moves. A shuffle that dragged the beat would be a tempo change, not a feel.
     */
    @Test
    fun `swing moves the off-beat and leaves the pulse where it was`() {
        val swung = Pattern(
            name = "swung eighths",
            signature = TimeSignature.FOUR_FOUR,
            stepsPerBeat = 2,
            voices = Array(8) { intArrayOf(Voice.CLICK_LO.ordinal) },
            velocities = Array(8) { floatArrayOf(0.9f) },
            swing = 0.33f,
        )
        val transport = Transport().apply {
            prepare(rate)
            pattern = swung
            tempoBpm = 100.0
            level = 1f
            clickOverDrums = false
            start()
        }
        val hits = onsets(render(transport, 6.0))
        assertTrue(hits.size >= 8, "only ${hits.size} hits")

        val samplesPerBeat = 60.0 * rate / 100.0
        val gaps = (1 until hits.size).map { (hits[it] - hits[it - 1]).toDouble() }

        // Each pair still adds up to exactly one beat.
        for (i in 0 until gaps.size - 1 step 2) {
            assertTrue(
                abs(gaps[i] + gaps[i + 1] - samplesPerBeat) < 3.0,
                "a swung pair spanned ${gaps[i] + gaps[i + 1]}, expected $samplesPerBeat",
            )
        }
        // And the long one really is long: 1.33 against 0.67 of half a beat.
        assertTrue(gaps[0] > gaps[1] * 1.8, "swing produced ${gaps[0]} then ${gaps[1]}")
        assertTrue(
            abs(gaps[0] - samplesPerBeat * 0.5 * 1.33) < 3.0,
            "the long half of the pair was ${gaps[0]}",
        )
    }

    @Test
    fun `shuffle is the rock pattern with swing on it`() {
        val rock = assertNotNull(Feels.find(TimeSignature.FOUR_FOUR, "Rock"))
        val shuffle = assertNotNull(Feels.find(TimeSignature.FOUR_FOUR, "Shuffle"))
        assertTrue(shuffle.swing > 0f, "the shuffle has no swing on it")
        assertEquals(0f, rock.swing, "straight rock should not be swung")
        assertEquals(rock.stepCount, shuffle.stepCount)
    }

    // ---- the click over a kit -----------------------------------------------

    @Test
    fun `the click layers over a drum pattern and only when asked`() {
        fun peakOfClickBand(layered: Boolean): Int {
            val transport = Transport().apply {
                prepare(rate)
                signature = TimeSignature.FOUR_FOUR
                pattern = assertNotNull(Feels.find(TimeSignature.FOUR_FOUR, "Rock"))
                tempoBpm = 120.0
                level = 1f
                clickOverDrums = layered
                start()
            }
            // The kick lands on beats 1 and 3 and the snare on 2 and 4, so beats are indistinguishable
            // by count. Counting onsets is not the test — total energy is, because a tick on every
            // pulse is four extra bursts a bar.
            return render(transport, 4.0).count { abs(it) > 0.3f }
        }
        val with = peakOfClickBand(true)
        val without = peakOfClickBand(false)
        assertTrue(with > without, "layering the click added nothing: $with against $without")
    }

    @Test
    fun `layering never doubles a click that is already the pattern`() {
        // With a click style selected the layer must be suppressed, or beat 1 fires CLICK_HI twice
        // at the same instant — which retriggers rather than doubling, but would still silence the
        // first tick's first sample and is simply wrong.
        val on = Transport().apply {
            prepare(rate); tempoBpm = 120.0; level = 1f; clickOverDrums = true; start()
        }
        val off = Transport().apply {
            prepare(rate); tempoBpm = 120.0; level = 1f; clickOverDrums = false; start()
        }
        assertContentEquals(
            render(off, 3.0),
            render(on, 3.0),
            "clickOverDrums changed a click-only pattern",
        )
    }

    @Test
    fun `a feel can be found again by the name a session stored`() {
        for (signature in TimeSignature.entries) {
            for (pattern in Feels.forSignature(signature)) {
                assertSame(pattern, Feels.find(signature, pattern.name))
            }
        }
        assertEquals(null, Feels.find(TimeSignature.THREE_FOUR, "Click on 2 & 4"))
    }

    /**
     * Changing FEEL mid-bar must not index off the end of a shorter table.
     *
     * Switching from "Click + 16ths" (four steps a beat) to "Click" (one) while the playhead is on
     * step 3 of a beat is the case that would throw, and a throw on the audio thread is silence and
     * a fault flag rather than a click.
     */
    @Test
    fun `switching feel and signature under the playhead is safe`() {
        val transport = playing(feel = "Click + 16ths", bpm = 280.0)
        val scratch = FloatArray(block)
        val signatures = TimeSignature.entries.toTypedArray()

        repeat(3_000) { i ->
            transport.mix(scratch, block)
            if (i % 7 == 0) {
                val signature = signatures[(i / 7) % signatures.size]
                transport.signature = signature
                transport.pattern = Feels.forSignature(signature)[(i / 7) % Feels.forSignature(signature).size]
            }
        }
        // Reaching here without an exception is the assertion; this pins the invariant it relied on.
        assertTrue(transport.displayBeat < transport.signature.beatsPerBar)
    }

    // ---- the threading contract ---------------------------------------------

    /** The full kit with the click layered over it — the most voices that can be sounding at once. */
    @Test
    fun `mix does not allocate with a drum pattern running`() {
        val transport = playing(feel = "Rock", bpm = 240.0).apply { clickOverDrums = true }
        val scratch = FloatArray(256)

        repeat(2_000) { transport.mix(scratch, 256) }
        val before = allocatedBytes()
        if (before < 0) return
        repeat(20_000) { transport.mix(scratch, 256) }
        val allocated = allocatedBytes() - before
        assertTrue(
            allocated < 64 * 1024,
            "mix() allocated $allocated bytes over 20 000 blocks with the kit running",
        )
    }

    @Test
    fun `mix does not allocate`() {
        val transport = playing(feel = "Click + 16ths", bpm = 240.0)
        val scratch = FloatArray(256)

        repeat(2_000) { transport.mix(scratch, 256) }          // let the JIT settle first
        val before = allocatedBytes()
        if (before < 0) return                                  // not a HotSpot JVM; nothing to assert
        repeat(20_000) { transport.mix(scratch, 256) }
        val allocated = allocatedBytes() - before
        assertTrue(
            allocated < 64 * 1024,
            "mix() allocated $allocated bytes over 20 000 blocks; it runs on the audio thread",
        )
    }

    private fun allocatedBytes(): Long = try {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val method = bean.javaClass.getMethod("getCurrentThreadAllocatedBytes")
        method.isAccessible = true
        method.invoke(bean) as Long
    } catch (_: Throwable) {
        -1L
    }

    private companion object {
        /** Refractory gap between onsets, in samples. See [onsets] for why it is not a quiet run. */
        const val MIN_GAP = 2_048
    }
}
