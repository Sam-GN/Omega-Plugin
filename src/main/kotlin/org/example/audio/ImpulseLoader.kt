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

import java.io.File
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException

/** Why an impulse file could not be used. The message completes "\"<file>\" …". */
class ImpulseException(message: String) : Exception(message)

/**
 * Reads an impulse response from a sound file.
 *
 * Java Sound does the decoding, so whatever the JDK reads — WAV and AIFF at any bit depth it can
 * convert — works without a codec of our own. It is converted to 16-bit PCM first because that
 * conversion is the one Java Sound always supports; going straight to float is not.
 *
 * Channels are averaged to mono. A stereo cabinet impulse is two mic positions, and mixing them is
 * what a mono chain can honestly do with it.
 */
object ImpulseLoader {

    class Impulse(val samples: FloatArray, val sampleRate: Float, val name: String)

    /** Longer than any cabinet impulse worth the name; a guard against opening a whole album. */
    private const val MAX_SECONDS = 10

    fun load(file: File): Impulse {
        val stream = try {
            AudioSystem.getAudioInputStream(file)
        } catch (e: UnsupportedAudioFileException) {
            throw ImpulseException("is not a sound file this JDK can read — try a WAV")
        } catch (e: IOException) {
            throw ImpulseException("could not be read: ${e.message ?: "I/O error"}")
        }

        stream.use { input ->
            val source = input.format
            val channels = source.channels.coerceAtLeast(1)
            val target = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                source.sampleRate,
                16,
                channels,
                channels * 2,
                source.sampleRate,
                false, // little-endian
            )
            if (!AudioSystem.isConversionSupported(target, source)) {
                throw ImpulseException("is in a format this JDK cannot decode (${source.encoding})")
            }

            AudioSystem.getAudioInputStream(target, input).use { pcm ->
                val limit = (source.sampleRate * MAX_SECONDS).toInt() * channels * 2
                val bytes = pcm.readNBytes(limit)
                if (bytes.size < 4) throw ImpulseException("contains no audio")

                val frames = bytes.size / (channels * 2)
                val samples = FloatArray(frames)
                var at = 0
                for (frame in 0 until frames) {
                    var sum = 0
                    for (channel in 0 until channels) {
                        val low = bytes[at].toInt() and 0xFF
                        val high = bytes[at + 1].toInt()
                        sum += (high shl 8) or low
                        at += 2
                    }
                    samples[frame] = sum / (channels * 32_768f)
                }
                return Impulse(samples, source.sampleRate, file.name)
            }
        }
    }
}
