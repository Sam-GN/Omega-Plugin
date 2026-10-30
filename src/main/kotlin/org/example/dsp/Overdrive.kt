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
 * The drive pedal: one fixed place on the board, and a dropdown that picks which kind of drive it
 * is. The knobs are the chosen type's own — three on a Screamer, two on a fuzz, four on a tube
 * stage — which is what makes one pedal do the job of a row of them.
 *
 * A [TypedModule], so switching is a fade rather than a click, every type is warmed before the
 * stream starts, and a preset keeps every type's knobs. The first type is the sound the pedal
 * always had, under the same three knob names, so a preset from before the dropdown existed lands
 * on it unchanged.
 *
 * These are voicings *in the style of* the pedals they are named after, not models of them: the
 * shape of the clipping and the filters that family has. A NAM capture is what a real one sounds
 * like. See `Drives.kt` for what each one does.
 */
class Overdrive : TypedModule(TYPES) {

    override val name = "Overdrive"

    private val smooth = instance(0) as SmoothDrive

    /** The original sound's knobs, reachable directly — the headless tests and `--selftest` use them. */
    val drive: Parameter get() = smooth.drive
    val tone: Parameter get() = smooth.tone
    val level: Parameter get() = smooth.level

    companion object {
        /**
         * The drives. **Append only** — see [TypedModule]. `Smooth` stays first because it is the
         * default, and the default is the sound every existing preset was saved with.
         */
        private val TYPES = listOf(
            ModuleType("Smooth") { SmoothDrive() },
            ModuleType("Screamer") { ScreamerDrive() },
            ModuleType("Rat") { RatDrive() },
            ModuleType("Fuzz") { FuzzDrive() },
            ModuleType("Tube") { TubeDrive() },
        )

        val typeNames: List<String> get() = TYPES.map { it.label }
    }
}
