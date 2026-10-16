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

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * A mixer that can actually carry audio in the direction we need, plus the channel count we
 * negotiated with it.
 */
data class AudioDevice(val mixerInfo: Mixer.Info, val channels: Int) {
    val name: String get() = mixerInfo.name.trim().ifEmpty { mixerInfo.description }
    override fun toString(): String = "$name  (${if (channels == 1) "mono" else "stereo"})"
}

/**
 * Enumerates usable capture/playback devices.
 *
 * Java Sound reports plenty of mixers that exist only to expose volume controls ("Port ..." on
 * Windows, some ALSA entries on Linux). Those fail the [DataLine.Info] support check below and are
 * dropped, so the combo boxes only ever list devices that can be opened.
 */
object AudioDevices {

    /** Guitar in is mono in practice, so prefer a mono line and fall back to stereo. */
    fun inputs(): List<AudioDevice> = scan(TargetDataLine::class.java, intArrayOf(1, 2))

    /** Monitoring is nicer in stereo, so prefer stereo and fall back to mono. */
    fun outputs(): List<AudioDevice> = scan(SourceDataLine::class.java, intArrayOf(2, 1))

    private fun scan(lineClass: Class<*>, channelPreference: IntArray): List<AudioDevice> {
        val found = LinkedHashMap<String, AudioDevice>()
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = try {
                AudioSystem.getMixer(info)
            } catch (_: Exception) {
                continue
            }
            val channels = channelPreference.firstOrNull { ch ->
                try {
                    mixer.isLineSupported(DataLine.Info(lineClass, AudioConfig.format(ch)))
                } catch (_: Exception) {
                    false
                }
            } ?: continue
            val device = AudioDevice(info, channels)
            // Linux in particular repeats the same card under several mixer aliases.
            found.putIfAbsent(device.name, device)
        }
        return found.values.toList()
    }
}
