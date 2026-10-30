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

/**
 * What the rhythm section can make a sound with.
 *
 * **Append only, and the order is the array index `Transport` uses.** `Transport.voices` is indexed
 * by ordinal, so inserting an entry in the middle silently re-points every pattern already written
 * — a kick would come out of the hi-hat. Adding at the end costs nothing.
 *
 * The click accent is a **different voice rather than a louder velocity** on purpose. A metronome
 * that marks beat 1 by playing the same tick harder is much harder to follow than one that marks it
 * by playing a different pitch: the ear locates a pitch change instantly and has to compare two
 * loudnesses in sequence to notice a level change.
 */
enum class Voice {
    /** Beat 1. A higher tick, so the top of the bar is unmistakable. */
    CLICK_HI,

    /** Every other beat, and every subdivision. */
    CLICK_LO,

    KICK,
    SNARE,

    /** Side-stick. What a quiet 6/8 wants where a backbeat would flatten it. */
    RIM,

    HAT_CLOSED,
    HAT_OPEN,
}

/**
 * A bar: how many pulses are in it, and what a pulse divides into.
 *
 * **Tempo is the pulse you tap your foot to, not always the quarter note.** In 4/4 those are the
 * same thing. In 6/8 they are not: the bar is six eighths grouped into two dotted-quarter pulses,
 * and a musician who says "this ballad is at 70" means 70 of those pulses, not 70 quarter notes.
 * Defining tempo as the quarter throughout would be arithmetically consistent and would mean typing
 * 105 to get a feel of 70, which is the kind of correctness nobody wants.
 *
 * So [beatsPerBar] is pulses, and it is also how many lamps the UI draws.
 */
enum class TimeSignature(
    val display: String,
    /** Pulses in a bar. Also the number of beat lamps. */
    val beatsPerBar: Int,
    /** What one pulse divides into naturally: 2 for simple metres, 3 for compound. */
    val naturalDivision: Int,
) {
    TWO_FOUR("2/4", 2, 2),
    THREE_FOUR("3/4", 3, 2),
    FOUR_FOUR("4/4", 4, 2),

    /** Two dotted-quarter pulses of three eighths each. */
    SIX_EIGHT("6/8", 2, 3),

    /** Four dotted-quarter pulses. Where slow blues actually lives. */
    TWELVE_EIGHT("12/8", 4, 3);

    val isCompound: Boolean get() = naturalDivision == 3

    override fun toString(): String = display
}

/**
 * One bar of rhythm, flattened into steps.
 *
 * Stored as parallel arrays rather than as a list of objects, and built once off the audio thread,
 * because the scheduler indexes into this from inside the callback. A `List<Hit>` here would mean
 * an iterator per step — the same allocation that made `Reverb` audible before it was rewritten
 * against arrays.
 *
 * A step may hold several voices at once (a kick and a hat together), which is why [voices] is an
 * array of arrays rather than a single voice per step. Click patterns only ever use one, but the
 * format has to be right before the kit arrives, not after.
 */
class Pattern(
    val name: String,
    val signature: TimeSignature,
    /** Steps per pulse: 1 for pulses only, 2 for eighths in a simple metre, 3 for a compound one. */
    val stepsPerBeat: Int,
    /** Voice ordinals fired at each step. Empty array for a silent step. */
    private val voices: Array<IntArray>,
    /** Velocity of each hit, matching [voices] index for index. */
    private val velocities: Array<FloatArray>,
    /**
     * How far the off-beats are pushed late, 0..1.
     *
     * Zero for everything shipped in step one. It is in the format from the start because swing is
     * a property of *timing* and cannot be expressed as extra rows in the step table — a shuffle is
     * not a different pattern from straight eighths, it is the same pattern played unevenly. Adding
     * the field later would mean revisiting every pattern already written.
     */
    val swing: Float = 0f,
) {

    /** Total steps in the bar. */
    val stepCount: Int = signature.beatsPerBar * stepsPerBeat

    init {
        require(voices.size == stepCount) { "$name: ${voices.size} steps, expected $stepCount" }
        require(velocities.size == stepCount) { "$name: velocity table does not match" }
    }

    /** True when this pattern is only ticks — used to lock the "click over drums" menu item. */
    val isClickOnly: Boolean = voices.all { step ->
        step.all { it == Voice.CLICK_HI.ordinal || it == Voice.CLICK_LO.ordinal }
    }

    fun hitCount(step: Int): Int = voices[step].size

    fun voiceAt(step: Int, hit: Int): Int = voices[step][hit]

    fun velocityAt(step: Int, hit: Int): Float = velocities[step][hit]

    override fun toString(): String = name
}

/**
 * The catalogue: which patterns exist, and for which bar.
 *
 * The list is nested rather than flat — a signature, then the patterns available inside it — for a
 * reason that is only obvious once both halves are written down. Click styles are a genuine
 * cross-product: every one of them exists in every signature, so a flat list would carry five
 * copies of each. Drum patterns are the opposite: a waltz exists only in 3/4. Flattening pays the
 * cost on the regular half to remove a redundancy on the irregular half.
 *
 * [forSignature] is therefore what the FEEL dropdown reads, and it is rebuilt whenever SIG changes.
 */
object Feels {

    private const val ACCENT = 1.0f
    private const val PULSE = 0.72f
    private const val DIVISION = 0.42f

    /** Ticks on every pulse, beat 1 marked with the high click. */
    private fun pulses(signature: TimeSignature) =
        build("Click", signature, 1) { beat, _ ->
            if (beat == 0) Voice.CLICK_HI to ACCENT else Voice.CLICK_LO to PULSE
        }

    /** Ticks on every pulse and on every subdivision of it. */
    private fun subdivided(label: String, signature: TimeSignature, per: Int) =
        build(label, signature, per) { beat, step ->
            when {
                step != 0 -> Voice.CLICK_LO to DIVISION
                beat == 0 -> Voice.CLICK_HI to ACCENT
                else -> Voice.CLICK_LO to PULSE
            }
        }

    /** Ticks on the listed pulses only, silent elsewhere. */
    private fun onBeats(label: String, signature: TimeSignature, beats: IntArray, accent: Boolean) =
        build(label, signature, 1) { beat, _ ->
            when {
                !beats.contains(beat) -> null
                accent && beat == 0 -> Voice.CLICK_HI to ACCENT
                else -> Voice.CLICK_LO to PULSE
            }
        }

    /**
     * A drum pattern, written as the grid it is.
     *
     * Each lane is one character per step: `X` accented, `x` normal, `o` soft, `.` silent. That
     * makes the source read as the thing it produces —
     *
     * ```
     * Voice.HAT_CLOSED to "xoxoxox.",
     * Voice.SNARE      to "..X...X.",
     * Voice.KICK       to "X...X...",
     * ```
     *
     * — which matters more than it sounds, because a pattern written as nested lists of step indices
     * is a pattern nobody can check by eye, and these are checked by eye or not at all.
     */
    private fun groove(
        label: String,
        signature: TimeSignature,
        stepsPerBeat: Int,
        swing: Float,
        vararg lanes: Pair<Voice, String>,
    ): Pattern {
        val total = signature.beatsPerBar * stepsPerBeat
        val voices = Array(total) { IntArray(0) }
        val velocities = Array(total) { FloatArray(0) }

        for ((voice, lane) in lanes) {
            require(lane.length == total) {
                "$label: ${voice.name} lane is ${lane.length} steps, the bar is $total"
            }
            for (step in 0 until total) {
                val velocity = when (lane[step]) {
                    'X' -> 1.0f
                    'x' -> 0.75f
                    'o' -> 0.45f
                    '.' -> continue
                    else -> throw IllegalArgumentException("$label: unknown step '${lane[step]}'")
                }
                // Grown one at a time rather than pre-counted: this runs once at class-init and the
                // longest bar in the catalogue is twelve steps.
                voices[step] = voices[step] + voice.ordinal
                velocities[step] = velocities[step] + velocity
            }
        }
        return Pattern(label, signature, stepsPerBeat, voices, velocities, swing)
    }

    /**
     * Turns a per-step decision into the flat arrays [Pattern] wants.
     *
     * Everything here runs at class-init time on whatever thread touches [Feels] first, never on
     * the audio thread — which is exactly why the warm-up has to reach this object before the
     * stream starts.
     */
    private fun build(
        label: String,
        signature: TimeSignature,
        stepsPerBeat: Int,
        hit: (beat: Int, step: Int) -> Pair<Voice, Float>?,
    ): Pattern {
        val total = signature.beatsPerBar * stepsPerBeat
        val voices = Array(total) { IntArray(0) }
        val velocities = Array(total) { FloatArray(0) }
        for (index in 0 until total) {
            val decided = hit(index / stepsPerBeat, index % stepsPerBeat) ?: continue
            voices[index] = intArrayOf(decided.first.ordinal)
            velocities[index] = floatArrayOf(decided.second)
        }
        return Pattern(label, signature, stepsPerBeat, voices, velocities)
    }

    /**
     * Every pattern, by signature, in the order the dropdown shows them.
     *
     * Simple metres get the four subdivisions a guitarist actually counts. Compound metres get
     * their own division instead: offering "triplets" inside 6/8 would be offering the eighths
     * that are already there, and "16ths" in 12/8 is a subdivision nobody counts.
     *
     * The two sparse styles at the end of 4/4 are the reason this list is worth having at all.
     * A click on 2 and 4 only, or on 1 only, is the standard drill for actually holding time
     * rather than leaning on a tick every beat.
     */
    private val BY_SIGNATURE: Map<TimeSignature, List<Pattern>> = TimeSignature.entries.associateWith { sig ->
        buildList {
            add(pulses(sig))
            if (sig.isCompound) {
                add(subdivided("Click + ${sig.beatsPerBar * 3}", sig, 3))
            } else {
                add(subdivided("Click + 8ths", sig, 2))
                add(subdivided("Click + triplets", sig, 3))
                add(subdivided("Click + 16ths", sig, 4))
            }
            // The backbeat drill only means anything where there is a backbeat to sit on.
            if (sig.beatsPerBar == 4 && !sig.isCompound) {
                add(onBeats("Click on 2 & 4", sig, intArrayOf(1, 3), accent = false))
            }
            add(onBeats("Click on 1 only", sig, intArrayOf(0), accent = true))
            addAll(grooves(sig))
        }
    }

    /**
     * The drum patterns, per signature: two or three for each bar.
     *
     * Each one earns its place by being a *different* thing to practise against rather than by
     * filling out a list — Rock and Half-time differ only in where the snare lands, and that single
     * change is what makes a riff feel twice as slow. Within a signature the set is meant to span
     * what a guitarist actually meets in that bar: in 3/4 a waltz, a jazz waltz and a rock ballad
     * are three different time feels, not three flavours of one.
     *
     * A name shared between signatures — Driving in both 6/8 and 12/8 — is deliberate. SIG changes
     * keep the feel by name where the new bar has one, so a player switching between the two
     * compound bars stays on the same kind of groove.
     */
    private fun grooves(sig: TimeSignature): List<Pattern> = when (sig) {
        TimeSignature.FOUR_FOUR -> listOf(
            // The open hat on the last eighth is the pattern's one piece of punctuation: it says
            // where the bar ends, which is exactly what you lose when the click goes away.
            groove(
                "Rock", sig, 2, 0f,
                Voice.HAT_CLOSED to "xoxoxox.",
                Voice.HAT_OPEN to ".......o",
                Voice.SNARE to "..X...X.",
                Voice.KICK to "X...X...",
            ),
            groove(
                "Half-time", sig, 2, 0f,
                Voice.HAT_CLOSED to "xoxoxoxo",
                Voice.SNARE to "....X...",
                Voice.KICK to "X....X..",
            ),
            // Identical to Rock but for the swing, and that is the point worth seeing in the source:
            // a shuffle is not a different pattern played, it is the same pattern played unevenly.
            groove(
                "Shuffle", sig, 2, 0.33f,
                Voice.HAT_CLOSED to "xoxoxoxo",
                Voice.SNARE to "..X...X.",
                Voice.KICK to "X...X...",
            ),
        )

        TimeSignature.THREE_FOUR -> listOf(
            groove(
                "Waltz", sig, 2, 0f,
                Voice.HAT_CLOSED to "xoxoxo",
                Voice.RIM to "..x.x.",
                Voice.KICK to "X.....",
            ),
            // The ride figure of a jazz waltz — 1, 2, and-of-2, 3 — swung, with a cross-stick on 3.
            // The swing is what makes it one; straight, the same grid is a stiff Waltz with an
            // extra hat.
            groove(
                "Jazz waltz", sig, 2, 0.33f,
                Voice.HAT_CLOSED to "x.xxx.",
                Voice.RIM to "....x.",
                Voice.KICK to "X.....",
            ),
            // A rock ballad in three: full snare on 3 and a kick pushing in on the and-of-2, which
            // is what gives a slow 3/4 some forward lean instead of a lilt.
            groove(
                "Rock waltz", sig, 2, 0f,
                Voice.HAT_CLOSED to "xoxoxo",
                Voice.SNARE to "....X.",
                Voice.KICK to "X..x..",
            ),
        )

        // Rim rather than snare: a full backbeat at ballad tempo flattens the part it is under.
        TimeSignature.SIX_EIGHT -> listOf(
            groove(
                "Ballad", sig, 3, 0f,
                Voice.HAT_CLOSED to "xooxoo",
                Voice.RIM to "...X..",
                Voice.KICK to "X.....",
            ),
            // Both pulses on the kick and a side-stick on the third eighth of each — the skip that
            // makes a jig a jig rather than a slow 6/8.
            groove(
                "Jig", sig, 3, 0f,
                Voice.HAT_CLOSED to "xoxxox",
                Voice.RIM to "..x..x",
                Voice.KICK to "X..X..",
            ),
            // The rock 6/8: backbeat on the second pulse, and a kick on the last eighth leading
            // back into 1. The pickup is the whole difference from Ballad at the same tempo.
            groove(
                "Driving", sig, 3, 0f,
                Voice.HAT_CLOSED to "xoxxox",
                Voice.SNARE to "...X..",
                Voice.KICK to "X....x",
            ),
        )

        TimeSignature.TWELVE_EIGHT -> listOf(
            groove(
                "Slow blues", sig, 3, 0f,
                Voice.HAT_CLOSED to "xooxooxooxoo",
                Voice.SNARE to "...X.....X..",
                Voice.KICK to "X.....X.....",
            ),
            // Slow blues with the hats accented on every pulse and a kick on the third eighth of 1
            // and 3: the band leaning on it rather than sitting back.
            groove(
                "Driving", sig, 3, 0f,
                Voice.HAT_CLOSED to "XoxXoxXoxXox",
                Voice.SNARE to "...X.....X..",
                Voice.KICK to "X.x...X.x...",
            ),
            // The fifties ballad: soft triplets throughout and a side-stick on 2 and 4. Nothing in
            // it is loud, which is the point — it is the piano's part played on the hats.
            groove(
                "Doo-wop", sig, 3, 0f,
                Voice.HAT_CLOSED to "oooooooooooo",
                Voice.RIM to "...x.....x..",
                Voice.KICK to "X.....X.....",
            ),
        )

        TimeSignature.TWO_FOUR -> listOf(
            // Bass drum on both beats, snare on the second. The whole of a march, and the click
            // styles do not quite give it: the kick under the snare on 2 is what makes it walk.
            groove(
                "March", sig, 2, 0f,
                Voice.HAT_CLOSED to "xoxo",
                Voice.SNARE to "..X.",
                Voice.KICK to "X.x.",
            ),
            // Oom-pah: kick on the beats, snare on the off-beats, nothing on top.
            groove(
                "Polka", sig, 2, 0f,
                Voice.HAT_CLOSED to "xoxo",
                Voice.SNARE to ".x.x",
                Voice.KICK to "X.X.",
            ),
            // The country train beat: the snare runs sixteenths with the backbeat accented, over a
            // kick on both beats. Four steps to the pulse — the only pattern that needs them.
            groove(
                "Train", sig, 4, 0f,
                Voice.SNARE to "ooooXooo",
                Voice.KICK to "X...x...",
            ),
        )
    }

    fun forSignature(signature: TimeSignature): List<Pattern> = BY_SIGNATURE.getValue(signature)

    /**
     * What FEEL falls back to when SIG changes and the current choice does not exist in the new bar.
     *
     * Always the plain click: it is the one style present in every signature, and a silent fallback
     * to something merely similar would occasionally pick a pattern the user did not ask for and
     * cannot see the reason for.
     */
    fun defaultFor(signature: TimeSignature): Pattern = forSignature(signature).first()

    /** Looks a pattern up by name within a signature, for restoring a saved session. */
    fun find(signature: TimeSignature, name: String): Pattern? =
        forSignature(signature).firstOrNull { it.name == name }

    /** Every pattern in the catalogue. The warm-up walks this so no dropdown change loads a class. */
    fun all(): List<Pattern> = BY_SIGNATURE.values.flatten()
}
