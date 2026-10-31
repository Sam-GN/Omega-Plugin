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

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rate negotiation, pinned against the two devices that actually exposed the bug: an HDMI output
 * that does 48 kHz and nothing else, and a USB interface that does 44.1 kHz and nothing else.
 *
 * The failure being guarded against was not a crash — it was the app asking the output for a rate
 * it cannot produce and then telling the user to change a Windows setting that does not exist.
 */
class SampleRatesTest {

    private val sonicake = listOf(44_100)                 // USB interface, 44.1 only
    private val aorus = listOf(48_000)                    // HDMI display, 48 only
    private val realtek = listOf(48_000)                  // USB headset, 48 only
    private val flexible = listOf(44_100, 48_000, 96_000)

    // ---- intersection --------------------------------------------------------

    @Test
    fun `two devices that share nothing produce no common rate`() {
        assertEquals(emptyList(), SampleRates.common(sonicake, aorus))
        assertEquals(emptyList(), SampleRates.common(aorus, sonicake))
    }

    @Test
    fun `a flexible device meets a fixed one at the fixed one's rate`() {
        assertEquals(listOf(48_000), SampleRates.common(flexible, aorus))
        assertEquals(listOf(44_100), SampleRates.common(flexible, sonicake))
    }

    @Test
    fun `the intersection is ascending and free of duplicates`() {
        val common = SampleRates.common(listOf(96_000, 44_100, 48_000, 44_100), listOf(48_000, 96_000, 44_100))
        assertEquals(listOf(44_100, 48_000, 96_000), common)
    }

    @Test
    fun `a device that reports no rates constrains nothing`() {
        // A backend that cannot enumerate must not be able to empty the picker — that would turn a
        // missing capability into "these devices are incompatible", which is a different claim.
        assertEquals(listOf(48_000), SampleRates.common(emptyList(), aorus))
        assertEquals(listOf(44_100), SampleRates.common(sonicake, emptyList()))
        assertEquals(emptyList(), SampleRates.common(emptyList(), emptyList()))
    }

    // ---- which rate is chosen -------------------------------------------------

    @Test
    fun `the device's own setting wins over policy`() {
        // 48 kHz would give a shorter period, but the input is already configured for 44.1 and
        // that is a fact about the hardware rather than a preference of ours.
        assertEquals(44_100, SampleRates.recommended(flexible, inputPreferred = 44_100))
    }

    @Test
    fun `the output's setting is used when the input has no opinion in range`() {
        assertEquals(48_000, SampleRates.recommended(flexible, inputPreferred = 192_000, outputPreferred = 48_000))
    }

    @Test
    fun `with no usable preference the highest common rate wins`() {
        // Higher rate, shorter period for the same buffer — which is this program's whole objective.
        assertEquals(96_000, SampleRates.recommended(flexible))
        assertEquals(96_000, SampleRates.recommended(flexible, inputPreferred = 176_400))
    }

    @Test
    fun `a single common rate is chosen whatever anyone prefers`() {
        assertEquals(48_000, SampleRates.recommended(listOf(48_000), inputPreferred = 44_100))
    }

    @Test
    fun `nothing available means no recommendation, not a fabricated default`() {
        assertNull(SampleRates.recommended(emptyList(), inputPreferred = 44_100))
    }

    // ---- what the user is told ------------------------------------------------

    @Test
    fun `the no-overlap message names both devices and both rates`() {
        val message = SampleRates.noOverlapMessage(
            "Line (Pocket Master Audio)", sonicake,
            "AORUS FI32Q X (NVIDIA High Definition Audio)", aorus,
        )
        assertContains(message, "Line (Pocket Master Audio)")
        assertContains(message, "AORUS FI32Q X (NVIDIA High Definition Audio)")
        assertContains(message, "44.1 kHz")
        assertContains(message, "48 kHz")
    }

    @Test
    fun `the message never sends the user to Windows sound settings`() {
        // The exact advice that was impossible to follow: the HDMI output cannot do 44.1 kHz at
        // all, so there was no setting to change. Naming the constraint is the only honest option.
        val message = SampleRates.noOverlapMessage("in", sonicake, "out", aorus)
        assertTrue(
            !message.contains("Windows", ignoreCase = true) &&
                !message.contains("sound settings", ignoreCase = true),
            "the message still blames a Windows setting: $message",
        )
    }

    @Test
    fun `the message says this path does not convert, so the user knows where it is a choice`() {
        val message = SampleRates.noOverlapMessage("in", sonicake, "out", realtek)
        assertTrue(
            message.contains("convert", ignoreCase = true),
            "a user told 'no' deserves to know it is the driver path, not a missing feature: $message",
        )
    }

    // ---- the input converter ----------------------------------------------------

    @Test
    fun `a converting backend offers the output's rates, whatever the input does`() {
        assertEquals(listOf(48_000), SampleRates.offer(sonicake, aorus, convertsInput = true))
        assertEquals(listOf(44_100, 48_000, 96_000), SampleRates.offer(sonicake, flexible, convertsInput = true))
        // And one that does not convert still gets the intersection.
        assertEquals(emptyList(), SampleRates.offer(sonicake, aorus, convertsInput = false))
        assertEquals(listOf(44_100), SampleRates.offer(sonicake, flexible, convertsInput = false))
    }

    @Test
    fun `an output that reported nothing leaves the input's rates on offer`() {
        assertEquals(listOf(44_100), SampleRates.offer(sonicake, emptyList(), convertsInput = true))
    }

    @Test
    fun `a rate is converted exactly when the input cannot do it`() {
        assertTrue(SampleRates.needsConversion(48_000, sonicake))
        assertTrue(!SampleRates.needsConversion(44_100, sonicake))
        assertTrue(!SampleRates.needsConversion(48_000, flexible))
        // An input that reported no rates constrains nothing, so it is never "converted".
        assertTrue(!SampleRates.needsConversion(48_000, emptyList()))
    }

    @Test
    fun `the capture rate is the engine's when it can be and the input's own when it cannot`() {
        assertEquals(48_000, SampleRates.captureRate(48_000, flexible, 44_100))
        assertEquals(44_100, SampleRates.captureRate(48_000, sonicake, 44_100))
        // Preferred wins among the input's rates; the highest stands in when there is none.
        assertEquals(44_100, SampleRates.captureRate(192_000, flexible, 44_100))
        assertEquals(96_000, SampleRates.captureRate(192_000, flexible, null))
    }

    @Test
    fun `a native rate is recommended over a converted one, whatever anybody prefers`() {
        // Output flexible, input 44.1 only: 48 and 96 would be converted. 44.1 is native and wins
        // even though 96 is the higher rate and the output is set to 48.
        val offered = SampleRates.offer(sonicake, flexible, convertsInput = true)
        assertEquals(44_100, SampleRates.recommended(offered, 44_100, 48_000, inputRates = sonicake))
        // Only converted rates on offer: the output's own setting is used.
        assertEquals(48_000, SampleRates.recommended(listOf(48_000, 96_000), 44_100, 48_000, inputRates = sonicake))
    }

    @Test
    fun `the converter costs half its filter at the input's rate`() {
        // 64 taps, so 32 frames: 0.73 ms at 44.1 kHz, 0.67 at 48 — in engine frames, 35 at 48 kHz.
        assertEquals(0.726f, SampleRates.conversionMs(44_100), 0.001f)
        assertEquals(0.667f, SampleRates.conversionMs(48_000), 0.001f)
        assertEquals(35, SampleRates.conversionFrames(44_100, 48_000))
        assertEquals(0, SampleRates.conversionFrames(0, 48_000))
    }

    @Test
    fun `the C is explained with both rates and the cost`() {
        val note = SampleRates.conversionNote("Line (Pocket Master Audio)", 44_100, 48_000)
        assertContains(note, "Line (Pocket Master Audio)")
        assertContains(note, "44.1 kHz")
        assertContains(note, "48 kHz")
        assertContains(note, "0.7 ms")
        assertEquals("48 kHz C", SampleRates.label(48_000, converted = true))
        assertEquals("48 kHz", SampleRates.label(48_000, converted = false))
    }

    // ---- formatting -----------------------------------------------------------

    @Test
    fun `rates are formatted the way musicians write them`() {
        assertEquals("44.1 kHz", SampleRates.format(44_100))
        assertEquals("48 kHz", SampleRates.format(48_000))
        assertEquals("96 kHz", SampleRates.format(96_000))
        assertEquals("88.2 kHz", SampleRates.format(88_200))
    }

    @Test
    fun `rate lists read as sentences`() {
        assertEquals("only 48 kHz", SampleRates.describe(aorus))
        assertEquals("44.1 or 48 kHz", SampleRates.describe(listOf(48_000, 44_100)))
        assertEquals("44.1, 48 or 96 kHz", SampleRates.describe(flexible))
        assertEquals("no rate this backend could identify", SampleRates.describe(emptyList()))
    }

    // ---- rate as a latency lever ----------------------------------------------

    @Test
    fun `the period shortens as the rate rises for the same buffer`() {
        // The reason RATE is a control and not a constant. These are the figures the BUFFER picker
        // shows, and they must recompute when the rate changes rather than staying at 44.1 kHz.
        assertEquals(2.902f, SampleRates.periodMs(128, 44_100), 0.001f)
        assertEquals(2.667f, SampleRates.periodMs(128, 48_000), 0.001f)
        assertEquals(1.333f, SampleRates.periodMs(128, 96_000), 0.001f)
        assertTrue(SampleRates.periodMs(128, 96_000) < SampleRates.periodMs(128, 44_100))
    }

    @Test
    fun `a nonsense rate does not divide by zero`() {
        assertEquals(0f, SampleRates.periodMs(128, 0), 0f)
    }

    // ---- the real pairings ------------------------------------------------------

    @Test
    fun `the failing pair is caught before the driver is ever asked, where nothing converts`() {
        // Sonicake input at 44.1 with the AORUS HDMI output at 48: the exact case that produced
        // impossible advice. On a path with no converter there is no rate to try, so nothing
        // should be requested at all.
        val shared = SampleRates.common(sonicake, aorus)
        assertTrue(shared.isEmpty())
        assertNull(SampleRates.recommended(shared, 44_100, 48_000))
    }

    @Test
    fun `the same pair runs at 48 kHz with the input converted where the backend converts`() {
        // The engine takes the output's rate; the Sonicake opens at its own 44.1 and is converted.
        val offered = SampleRates.offer(sonicake, aorus, convertsInput = true)
        assertEquals(listOf(48_000), offered)
        val rate = SampleRates.recommended(offered, 44_100, 48_000, inputRates = sonicake)
        assertEquals(48_000, rate)
        assertTrue(SampleRates.needsConversion(rate!!, sonicake))
        assertEquals(44_100, SampleRates.captureRate(rate, sonicake, 44_100))
    }

    @Test
    fun `a 48 kHz input with a 48 kHz output negotiates cleanly`() {
        val shared = SampleRates.common(realtek, aorus)
        assertEquals(listOf(48_000), shared)
        assertEquals(48_000, SampleRates.recommended(shared, 48_000, 48_000))
    }

    @Test
    fun `a single-device driver is unaffected by any of this`() {
        // ASIO: one driver, one clock, one rate. Intersecting a device with itself must be a no-op.
        assertEquals(sonicake, SampleRates.common(sonicake, sonicake))
        assertEquals(44_100, SampleRates.recommended(sonicake, 44_100, 44_100))
    }
}
