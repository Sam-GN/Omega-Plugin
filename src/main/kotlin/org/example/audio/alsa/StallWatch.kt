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
package org.example.audio.alsa

/**
 * Notices a stream that has stopped moving without failing (2.3.1).
 *
 * A loop waiting for a device that never becomes ready raises no error, no xrun and no deadline
 * miss: 2.3.0 sat in silence at 44.1 kHz into PipeWire with every counter reading clean. Counted in
 * looks rather than time, so a process that was suspended is not mistaken for a stalled stream.
 */
internal class StallWatch(private val looks: Int) {
    private var last = -1L
    private var same = 0

    /** [progress] only ever grows while the stream moves. True once, when it has stood for [looks] looks. */
    fun stalled(progress: Long): Boolean {
        if (progress != last) {
            last = progress
            same = 0
            return false
        }
        return ++same == looks
    }
}
