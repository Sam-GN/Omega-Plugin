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
package org.example.dsp

/**
 * One board's share of the output in split mode: a mini master at the end of that board, just
 * before the two boards are summed.
 *
 * At the end rather than the start for the same reason the master moved there. A level ahead of the
 * drive sets how hard the drive is hit, so turning one board down to balance it against the other
 * would also clean up its distortion — a tone control with a side effect, not a volume.
 *
 * The default is 71 %, which through the squared taper is a gain of 0.5, or -6 dB. Two boards fed
 * the same guitar are *correlated*: their peaks land together, so their amplitudes add rather than
 * their powers, and two boards at -6 dB sum back to exactly the level one board had. That is what
 * keeps switching split on from clipping. -3 dB each — the constant-power figure — is for two
 * unrelated signals, and these are not.
 *
 * Inactive outside split mode, where [VolumeStage.active] puts it at unity and out of the way.
 */
class BoardLevel : VolumeStage("Level", 71f) {
    init {
        active = false
    }
}
