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
package org.example.audio

import javax.sound.sampled.AudioFormat

/**
 * The **Java Sound backend's** configuration, plus the unit conversions it shares.
 *
 * This is no longer engine-wide and must not become so again. Rate and block size now come from
 * whatever the driver granted, through `StreamFormat`; the constants here describe one backend's
 * line handling and are meaningless to ASIO or WASAPI, which negotiate their own. Anything outside
 * `audio/javasound/` reading [SAMPLE_RATE] or [BLOCK_FRAMES] is almost certainly a bug — that is the
 * hardcoded-44 100 defect this project already had once, in `dsp/`.
 *
 * Java Sound assumes 16-bit signed little-endian PCM at [SAMPLE_RATE]; the DSP chain works on mono
 * float samples in [-1, 1] and conversion happens only at the device boundary.
 *
 * ### Why the line buffers are so much larger than one block
 *
 * Java Sound's Windows backend (`com.sun.media.sound.DirectAudioDevice`) implements the blocking
 * part of `read`/`write` as a poll: when the native call cannot move all the requested bytes it
 * does `lock.wait(waitTime)` and tries again, where `waitTime = lineBufferMillis / 4`, forced to
 * 1 ms when that works out below 10. On Windows the scheduler tick is 15.6 ms unless some process
 * has raised the timer resolution, so **every one of those waits really costs ~15.6 ms** no matter
 * what was asked for. A line buffer only a few blocks long therefore spends most of its time
 * asleep: measured here, a 23 ms playback buffer moved 5 900 frames/s instead of 44 100, i.e. the
 * device was starved 87 % of the time and replayed stale buffer content — the "crackly, robotic"
 * symptom.
 *
 * The cure is headroom. Note that line buffer *capacity* is free: latency is set by how full the
 * playback buffer is kept ([CUSHION_MS]), not by how large it could get. So capacity is generous
 * enough that `write` never has to block, and only the cushion is spent on latency.
 *
 * All three can be overridden with `-Domega.audio.*` for experimentation on other machines.
 */
object AudioConfig {

    const val SAMPLE_RATE = 44_100f
    const val SAMPLE_BITS = 16

    /**
     * Frames handled per read/process/write cycle. 256 frames is ~5.8 ms per block. This is the DSP
     * granularity only — since the fix above it no longer sizes the device buffers.
     */
    const val BLOCK_FRAMES = 256

    /**
     * Capture line capacity — and, unavoidably, the dominant latency term.
     *
     * The backend derives its poll interval from this (buffer/4), so the capture line ends up
     * holding up to a quarter of it before a read returns. Smaller is therefore lower latency but
     * less tolerant of the 100-300 ms stalls Windows hands out: measured on this machine, 96 ms
     * dropped out roughly once a minute and 160 ms ran an hour's worth of minutes clean. Round trip
     * scales about 1:1 with it — cable to cable, 48 ms measured 148 ms, 160 ms measured 210 ms.
     */
    val CAPTURE_BUFFER_MS = prop("captureMs", 160)

    /**
     * The sizes the UI offers, in milliseconds.
     *
     * Discrete rather than free-form: an arbitrary number just produces failures nobody can read
     * back to a cause. Measured on this machine, 48 ms dropped out several times a minute, 96 ms
     * roughly once a minute, and 160 ms ran clean for an hour — which is why that is the default
     * rather than the lowest value on the list.
     */
    val CAPTURE_BUFFER_CHOICES = intArrayOf(48, 64, 96, 128, 160, 240, 320)

    /** Playback line capacity. Large enough that `write` never enters the polling path. */
    val PLAYBACK_BUFFER_MS = prop("playbackMs", 192)

    /**
     * Silence written ahead of the stream so the DAC always has something to play.
     *
     * This is what stops the very first block — and, on this backend, every block after it — from
     * arriving at an empty device buffer. Note that the driver keeps a lead of its own on top: the
     * queue settles nearer 70-110 ms however little is primed here, which is why the engine reports
     * the level it measures rather than the one it asked for.
     */
    val CUSHION_MS = prop("cushionMs", 35)

    /** Set `-Domega.audio.drift=false` to watch the playback buffer wander instead of correcting it. */
    val DRIFT_CORRECTION = System.getProperty("omega.audio.drift")?.toBoolean() ?: true

    fun framesForMs(ms: Int): Int = (SAMPLE_RATE * ms / 1000f).toInt()

    fun msForFrames(frames: Int): Float = frames * 1000f / SAMPLE_RATE

    fun format(channels: Int): AudioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        SAMPLE_RATE,
        SAMPLE_BITS,
        channels,
        channels * SAMPLE_BITS / 8,
        SAMPLE_RATE,
        false, // little-endian
    )

    private fun prop(name: String, default: Int): Int =
        System.getProperty("omega.audio.$name")?.toIntOrNull()?.coerceIn(5, 2_000) ?: default
}
