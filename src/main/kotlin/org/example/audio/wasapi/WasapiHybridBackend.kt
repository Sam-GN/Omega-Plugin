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
package org.example.audio.wasapi

import org.example.audio.BackendId

/**
 * WASAPI with the input **exclusive** and the output **shared at a short period** (2.2.0).
 *
 * The same stream code as [WasapiBackend]; only the two directions' modes differ. Why it exists,
 * from one laptop:
 *
 * - **An exclusive output can be the slow one.** A Cirrus Logic SoundWire output (Intel's audio DSP
 *   in front of it) reported a 4 ms buffer in exclusive mode while its own `IAudioClock` showed
 *   102 ms written and not yet played — at every buffer size, on both of its endpoints, with RAW
 *   processing asked for or not. Through the Windows engine at `IAudioClient3`'s 96-frame period
 *   the same clock showed 2.4 ms. Nothing the program can ask of the exclusive path changes it, so
 *   the output goes the other way.
 * - **The input loses nothing by staying exclusive.** The interface's capture is as fast as its
 *   driver allows, which in shared mode would be a 10 ms engine period.
 * - **Other sound keeps playing.** The output is shared, so a backing track from a browser or a
 *   player comes out of the same speakers as the guitar.
 *
 * An output with no short period to offer (`minimum == default`, most of them) still runs, at the
 * ordinary shared period, and is no faster than shared. Exclusive and shared are untouched: this is
 * a separate choice, so no machine that worked before is moved onto it.
 */
class WasapiHybridBackend : WasapiBackend(
    captureShared = false,
    renderShared = true,
    lowLatencyRender = true,
    id = BackendId.WASAPI_HYBRID,
)
