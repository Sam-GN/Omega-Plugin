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

/**
 * WASAPI through the Windows mixer rather than around it.
 *
 * The same API and the same stream code as [WasapiBackend]; only the share mode differs, and with it
 * three things that follow from it:
 *
 * - **The device stays shared.** Discord, a browser and Omega can all use the same headset at once.
 *   That is the entire reason this rung exists: exclusive mode locks the endpoint for as long as the
 *   stream is open, and the only alternative until now was Java Sound at around 200 ms.
 * - **The rate is Windows'.** A shared client cannot ask the engine for a different rate; whatever
 *   the endpoint is set to in Sound settings is what it runs at.
 * - **The period is Windows'.** Buffer sizes are what the engine grants, not what we ask for, and it
 *   rounds up to its own period. Measured on this machine that floor is 970 frames at 44.1 kHz and
 *   1056 at 48 — 22 ms either way, on every one of seventeen endpoints — for a 65.99 ms round trip.
 *   That is why this can never be the fast path.
 *
 * `IAudioClient3` is the documented way to ask for a shorter period, and it does not help here: see
 * [AudioClient.sharedModeEnginePeriod], where the measured periods are recorded. Every endpoint
 * answers, and every one that matters answers `minimum == default`.
 */
class WasapiSharedBackend : WasapiBackend(WasapiIds.SHARED)
