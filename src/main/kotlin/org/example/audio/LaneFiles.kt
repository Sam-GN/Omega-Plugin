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

import org.example.dsp.loop.Looper
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.math.roundToInt

/** Why a lane file could not be used. The message completes "\"<file>\" …". */
class LaneFileException(message: String) : Exception(message)

/**
 * A looper lane on disk: a mono 32-bit float WAV, the format the recorder writes.
 *
 * Loading takes any sound file the JDK can decode, mixes its channels to mono, keeps the first
 * [Looper.MAX_SECONDS] and resamples to the rate asked for — all off the audio thread, so a file at
 * 48 kHz loaded into a 44.1 kHz stream arrives already at 44.1 and the callback never resamples.
 */
object LaneFiles {

    class Loaded(val samples: FloatArray, val count: Int, val name: String, val truncated: Boolean)

    /** Reads [file] into mono float at [targetRate], at most a lane's worth. */
    fun load(file: File, targetRate: Float): Loaded {
        val stream = try {
            AudioSystem.getAudioInputStream(file)
        } catch (e: UnsupportedAudioFileException) {
            throw LaneFileException("is not a sound file this JDK can read — try a WAV")
        } catch (e: IOException) {
            throw LaneFileException("could not be read: ${e.message ?: "I/O error"}")
        }
        stream.use { input ->
            val source = input.format
            val channels = source.channels.coerceAtLeast(1)
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, source.sampleRate, 16, channels, channels * 2, source.sampleRate, false,
            )
            if (!AudioSystem.isConversionSupported(target, source)) {
                throw LaneFileException("is in a format this JDK cannot decode (${source.encoding})")
            }
            AudioSystem.getAudioInputStream(target, input).use { pcm ->
                // One frame more than a lane, to know whether there was more.
                val limitFrames = (source.sampleRate * Looper.MAX_SECONDS).roundToInt() + 1
                val bytes = pcm.readNBytes(limitFrames * channels * 2)
                if (bytes.size < 2 * channels) throw LaneFileException("contains no audio")
                var frames = bytes.size / (channels * 2)
                val truncated = frames >= limitFrames
                if (truncated) frames = limitFrames - 1
                val mono = FloatArray(frames)
                var at = 0
                for (frame in 0 until frames) {
                    var sum = 0
                    for (channel in 0 until channels) {
                        val low = bytes[at].toInt() and 0xFF
                        val high = bytes[at + 1].toInt()
                        sum += (high shl 8) or low
                        at += 2
                    }
                    mono[frame] = sum / (channels * 32_768f)
                }
                if (source.sampleRate.roundToInt() == targetRate.roundToInt()) {
                    return Loaded(mono, frames, file.name, truncated)
                }
                val out = FloatArray((frames.toLong() * targetRate / source.sampleRate).toInt() + 64)
                val count = Looper.resample(mono, frames, source.sampleRate, targetRate, out)
                return Loaded(out, count, file.name, truncated)
            }
        }
    }

    /** Writes [count] frames of [samples] to [file] as a mono IEEE-float WAV at [sampleRate]. */
    fun save(file: File, samples: FloatArray, count: Int, sampleRate: Int) {
        val dataBytes = count * 4
        val buffer = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(tag("RIFF"))
        buffer.putInt(HEADER_BYTES - 8 + dataBytes)
        buffer.put(tag("WAVE"))
        buffer.put(tag("fmt "))
        buffer.putInt(18)
        buffer.putShort(3) // WAVE_FORMAT_IEEE_FLOAT
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 4)
        buffer.putShort(4)
        buffer.putShort(32)
        buffer.putShort(0) // cbSize
        buffer.put(tag("fact"))
        buffer.putInt(4)
        buffer.putInt(count)
        buffer.put(tag("data"))
        buffer.putInt(dataBytes)
        for (i in 0 until count) buffer.putFloat(samples[i])
        try {
            file.writeBytes(buffer.array())
        } catch (e: IOException) {
            throw LaneFileException("could not be written: ${e.message ?: "I/O error"}")
        }
    }

    /** RIFF header, 18-byte `fmt `, `fact`, and the `data` chunk header. */
    private const val HEADER_BYTES = 12 + 26 + 12 + 8

    private fun tag(text: String) = text.toByteArray(Charsets.US_ASCII)
}
