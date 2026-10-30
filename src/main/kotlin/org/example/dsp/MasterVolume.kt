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
 * Master level, **last** in the signal path: after the boards have been summed and before the
 * rhythm section is mixed in.
 *
 * It used to be first, ahead of the gate, which made it an input trim rather than a master — turning
 * it down fed less into the drive and changed the sound as well as the loudness. At the end it
 * changes only how loud the finished sound is, which is what a master is for. It still does not
 * touch the metronome, which has a level of its own.
 *
 * Everything about how it moves — the mute that ramps rather than cuts, the squared taper — is
 * [VolumeStage]'s, shared with each board's LEVEL.
 */
class MasterVolume : VolumeStage("Master", 80f)
