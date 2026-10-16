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

import org.example.dsp.DEFAULT_SAMPLE_RATE

/**
 * The rhythm section's clock, voices and mixer.
 *
 * **This is the first thing in the program that generates audio rather than shaping it**, which is
 * why it is not an `AudioProcessor` and not a member of `EffectChain`. Those model a pedal on the
 * board: something the guitar signal passes *through*, that the user drags around, that a preset
 * stores. A metronome is none of those. It is mixed into the monitor path after the chain has run,
 * so the click does not arrive at the listener via the overdrive and the reverb.
 *
 * ### The clock
 *
 * Time is counted in **samples off the audio callback**, never from a `Timer` or a wall clock. At
 * 130 BPM and 44.1 kHz a pulse is 20 353.846 samples, so [countdown] is a `Double` and the
 * remainder carries from one step into the next. Rounding each step to a whole sample instead
 * drifts by about a sample a beat, which is a quarter of a second every hour and audible against a
 * loop long before that.
 *
 * ### The threading contract
 *
 * [mix] runs on the audio thread and obeys the same rules as `AudioEngine.render`: no allocation,
 * no locks, no I/O, no lazy initialisation. Every control the UI owns is a plain `@Volatile` scalar
 * — a tempo change is one unsynchronised store picked up on the next block — and every figure the
 * UI reads back ([displayBeat], [beatSerial]) is the same in the other direction. The one compound
 * value, the selected [Pattern], travels as a single volatile reference to an immutable object,
 * which is the pattern the threading contract prescribes for exactly this case.
 */
class Transport {

    // ---------------------------------------------------------------- controls (UI writes)

    /**
     * Pulses per minute. The pulse, not always the quarter note — see [TimeSignature].
     *
     * Clamped by the UI on entry rather than here: a field that silently repaired a bad value on
     * the audio thread would hide a bug in the field that produced it.
     */
    @Volatile
    var tempoBpm: Double = 120.0

    @Volatile
    var signature: TimeSignature = TimeSignature.FOUR_FOUR

    @Volatile
    var pattern: Pattern = Feels.defaultFor(TimeSignature.FOUR_FOUR)

    /** Level of the whole rhythm bus, 0..1, linear. */
    @Volatile
    var level: Float = 0.5f

    /**
     * Whether the plain click is layered over a drum pattern.
     *
     * Read by [mix] and currently never consequential, because every pattern shipped so far is a
     * click already. It exists now because the menu item that drives it exists now; it starts
     * mattering the moment the kit lands.
     */
    @Volatile
    var clickOverDrums: Boolean = true

    // ---------------------------------------------------------------- telemetry (UI reads)

    /** Which pulse of the bar is sounding, 0-based, or -1 when nothing is playing. */
    @Volatile
    var displayBeat: Int = -1
        private set

    /** Increments on every pulse. The UI flashes a lamp when it changes; 30 Hz is enough at 300 BPM. */
    @Volatile
    var beatSerial: Long = 0L
        private set

    @Volatile
    var state: State = State.STOPPED
        private set

    /** Bars still to pass before an armed take opens, for the status line. 0 when nothing is armed. */
    val countInBarsRemaining: Int get() = barsUntilRecord

    val isRunning: Boolean get() = state != State.STOPPED

    /**
     * Two states, not three.
     *
     * There used to be a `COUNTING_IN` between them, for a count-in that had started the transport
     * itself. Record no longer does that — see [armForRecord] — so counting in is simply playing
     * with a take armed, and the third state had nothing left to represent.
     */
    enum class State { STOPPED, PLAYING }

    // ---------------------------------------------------------------- record arming

    /**
     * Set by the audio thread at the exact downbeat a take should begin on, cleared by whoever
     * consumes it.
     *
     * This is how a count-in stays sample-accurate without the audio thread ever opening a file.
     * The UI creates the `Recorder` up front — that is the part with a disk behind it — and parks
     * it; the callback only flips a boolean, and `AudioEngine` swaps the parked recorder in when it
     * sees the flag. The take therefore starts on the beat rather than on the next 30 Hz UI poll,
     * which would have put it up to 33 ms late.
     */
    @Volatile
    private var recordDue: Boolean = false

    /** Downbeats still to pass before the take opens, or 0 when nothing is armed. */
    @Volatile
    private var barsUntilRecord: Int = 0

    /**
     * Reads and clears [recordDue]. Called from the audio thread once per block.
     *
     * A read-then-write of a volatile from a single consumer. The producer is the same thread, so
     * there is no race to lose here; the flag exists to carry the event out of [mix] and into the
     * top of the next `render`, not between threads.
     */
    fun consumeRecordDue(): Boolean {
        if (!recordDue) return false
        recordDue = false
        return true
    }

    // ---------------------------------------------------------------- audio-thread state

    private var sampleRate: Double = DEFAULT_SAMPLE_RATE.toDouble()

    /** Samples until the next step fires. Fractional, and the remainder carries — see the class docs. */
    private var countdown: Double = 0.0

    private var beatInBar: Int = 0
    private var stepInBeat: Int = 0

    /** The pattern the current bar started with, so a mid-bar change cannot index out of range. */
    private var activePattern: Pattern = pattern


    /**
     * One voice per [Voice], allocated once, **indexed by ordinal**.
     *
     * The order here is the enum's order and nothing enforces that but this comment and the test
     * that checks it. Get it wrong and every pattern still plays, in time, with a kick coming out of
     * the hi-hat.
     *
     * A drum machine retriggers rather than layers: a second hit on the same voice restarts it. That
     * is both what the hardware does and what keeps this array a fixed size, which is what keeps
     * [mix] allocation-free.
     */
    private val voices: Array<DrumVoice> = arrayOf(
        Tick(frequency = 1000f, decaySeconds = 0.040f),   // CLICK_HI
        Tick(frequency = 800f, decaySeconds = 0.032f),    // CLICK_LO
        Kick(),                                           // KICK
        Snare(),                                          // SNARE
        Rim(),                                            // RIM
        Hat(decaySeconds = 0.045f, seed = 0x2468_ACE1),   // HAT_CLOSED
        Hat(decaySeconds = 0.280f, seed = 0x7F4A_91C3),   // HAT_OPEN
    )

    init {
        prepare(DEFAULT_SAMPLE_RATE)
    }

    // ---------------------------------------------------------------- lifecycle

    /** Binds to a device rate. Off the audio thread, before the stream starts. */
    fun prepare(rate: Float) {
        sampleRate = rate.toDouble()
        // Touched from here, where allocating is allowed. The table's initialiser is a 4 097-element
        // array and a first use from inside a callback would be class loading against the deadline.
        SineTable.warm()
        for (index in voices.indices) voices[index].prepare(rate)
        reset()
    }

    /** Silences everything and puts the playhead at the top of a bar. */
    fun reset() {
        countdown = 0.0
        beatInBar = 0
        stepInBeat = 0
        activePattern = pattern
        for (index in voices.indices) voices[index].silence()
        displayBeat = -1
    }

    /** Starts on the next block, at the top of a bar. */
    fun start() {
        reset()
        barsUntilRecord = 0
        state = State.PLAYING
    }

    /**
     * If the transport is already playing there is no count-in to give — the user is already in
     * time — so it simply waits for the next downbeat, which is what makes every take start on a
     * bar line however sloppily the button was hit.
     */
    /**
     * Asks for the take to begin after [bars] more downbeats. Returns false if there is nothing to
     * count with, in which case the caller should start recording immediately.
     *
     * **A count-in never starts the transport.** Pressing Record is not pressing Play: a metronome
     * that switched itself on because a take was starting is the program making a noise nobody asked
     * for. If the transport is stopped there is no count-in — Record simply records.
     *
     * With it running, [bars] is honoured as downbeats: 1 opens the file on the next bar line, 2 on
     * the one after, which is a full bar of lead-in.
     */
    fun armForRecord(bars: Int): Boolean {
        if (state != State.PLAYING) return false
        barsUntilRecord = bars.coerceAtLeast(1)
        return true
    }

    /** Cancels a pending arm. The transport is left exactly as it was — this never stops it. */
    fun disarm() {
        barsUntilRecord = 0
        recordDue = false
    }

    fun stop() {
        state = State.STOPPED
        barsUntilRecord = 0
        recordDue = false
        displayBeat = -1
    }

    // ---------------------------------------------------------------- the audio thread

    /**
     * Adds the rhythm into [out] in place. **Audio thread. Allocates nothing.**
     *
     * Voices are rendered even when the transport is stopped, so that pressing stop lets the last
     * tick decay instead of chopping it off — a click cut mid-envelope is itself a click.
     */
    fun mix(out: FloatArray, frames: Int) {
        val running = state != State.STOPPED
        val gain = level * BUS_TRIM

        // One volatile read each, hoisted out of the sample loop.
        val selected = pattern
        if (selected !== activePattern) adoptPattern(selected)

        val beats = activePattern.signature.beatsPerBar
        val stepsPerBeat = activePattern.stepsPerBeat
        val samplesPerBeat = 60.0 * sampleRate / tempoBpm.coerceIn(MIN_BPM, MAX_BPM)
        val baseStep = (samplesPerBeat / stepsPerBeat).coerceAtLeast(MIN_STEP_SAMPLES)
        val swing = activePattern.swing

        // A click on top of a kit needs no second scheduler: it is a tick on every pulse, and the
        // pulse is already the moment `stepInBeat` returns to zero. Suppressed when the pattern is a
        // click already, where it would be the same tick fired twice at the same instant.
        val layerClick = clickOverDrums && !activePattern.isClickOnly

        for (i in 0 until frames) {
            if (running && countdown <= 0.0) {
                if (beatInBar == 0 && stepInBeat == 0) onDownbeat()
                if (stepInBeat == 0) {
                    displayBeat = beatInBar
                    beatSerial++
                    if (layerClick) {
                        val click = if (beatInBar == 0) Voice.CLICK_HI else Voice.CLICK_LO
                        voices[click.ordinal].trigger(if (beatInBar == 0) 0.85f else 0.60f)
                    }
                }
                fire(beatInBar * stepsPerBeat + stepInBeat)
                countdown += stepLength(baseStep, stepsPerBeat, stepInBeat, swing)
                advance(beats, stepsPerBeat)
            }

            var sample = 0f
            for (v in voices.indices) sample += voices[v].render()
            out[i] += sample * gain

            if (running) countdown -= 1.0
        }
    }

    /**
     * Swaps in a pattern the user picked mid-bar.
     *
     * [beatInBar] survives and [stepInBeat] resets, so the next *pulse* still lands where it was
     * always going to and only the subdivisions inside the beat being played are given up. Resetting
     * both would restart the bar under the user's fingers; keeping both would index a table that may
     * now be a different length.
     */
    private fun adoptPattern(next: Pattern) {
        activePattern = next
        stepInBeat = 0
        if (beatInBar >= next.signature.beatsPerBar) beatInBar = 0
    }

    /** The top of a bar, reached exactly now. */
    private fun onDownbeat() {
        if (barsUntilRecord > 0) {
            barsUntilRecord--
            if (barsUntilRecord == 0) recordDue = true
        }
    }

    private fun fire(step: Int) {
        val hits = activePattern.hitCount(step)
        for (hit in 0 until hits) {
            val voice = activePattern.voiceAt(step, hit)
            voices[voice].trigger(activePattern.velocityAt(step, hit))
        }
    }

    private fun advance(beats: Int, stepsPerBeat: Int) {
        stepInBeat++
        if (stepInBeat < stepsPerBeat) return
        stepInBeat = 0
        beatInBar++
        if (beatInBar >= beats) beatInBar = 0
    }

    /**
     * How long the step just fired lasts.
     *
     * Swing pushes the off-beat late by lengthening the on-beat and shortening the one after it, so
     * a pair still occupies exactly one beat and the pulse never moves. Only defined for a beat
     * split in two — a shuffle is a property of eighth-note pairs, and applying it to triplets or
     * sixteenths would move notes that a swung feel leaves alone.
     */
    private fun stepLength(base: Double, stepsPerBeat: Int, step: Int, swing: Float): Double {
        if (swing <= 0f || stepsPerBeat != 2) return base
        val skew = base * swing.coerceAtMost(MAX_SWING)
        return if (step == 0) base + skew else base - skew
    }

    companion object {
        const val MIN_BPM = 30.0
        const val MAX_BPM = 300.0

        /**
         * Headroom for the whole bus, above whatever [level] says.
         *
         * The voice gains set the balance *between* the instruments — a kick against a hi-hat — and
         * this sets where the group as a whole sits. Separating the two is how a mixer works, and it
         * is what stops a change to one drum's loudness turning into a clipping bug in a pattern
         * that happens to use it.
         *
         * The number is measured rather than chosen. A kick, a hat and a layered click landing on
         * the same downbeat are three oscillators that all start from phase zero, so their peaks do
         * line up: the 3/4 Waltz reached **1.71** with the raw gains at full level. This brings the
         * worst pattern in the catalogue to about 0.7, which leaves the guitar the rest of the
         * buffer — the rhythm is *added* to it and only then clamped, so a bus that reached full
         * scale on its own would leave nothing for the instrument it is there to accompany.
         *
         * `TransportTest.the kit stays inside the buffer at full level` is what holds it there, and
         * it walks every pattern rather than the one that failed.
         */
        private const val BUS_TRIM = 0.40f

        /** Belt and braces against a step shorter than a sample, which would stall the countdown. */
        private const val MIN_STEP_SAMPLES = 8.0

        /** A swing beyond this collapses the pair into one note. */
        private const val MAX_SWING = 0.9f
    }
}
