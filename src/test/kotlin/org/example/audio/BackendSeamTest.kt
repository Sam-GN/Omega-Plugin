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

import org.example.audio.asio.legalBufferSizes
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts of the backend seam that can be pinned down with no device attached: the registry's
 * promise that it cannot fail, the buffer-size enumeration, and the latency arithmetic.
 *
 * Everything that touches a driver is verified with `--selftest` and `--measure` instead.
 */
class BackendSeamTest {

    // ---- the registry ------------------------------------------------------

    @Test
    fun `the registry always offers a working backend`() {
        // The whole degradation story rests on this. If natives fail on a user's machine the app
        // must still start and still make sound, and that has to be structural rather than hoped for.
        val all = Backends.all()
        assertTrue(all.isNotEmpty(), "the backend registry came back empty")
        assertTrue(
            all.any { it.id == BackendId.JAVA_SOUND && it.availability.isAvailable },
            "Java Sound is the bottom rung and must always be available",
        )
    }

    @Test
    fun `only this platform's backends are offered`() {
        // The guard on "Linux support must not change Windows": the driver picker on a Windows box
        // lists exactly what it listed before JACK and ALSA existed, and a Linux one is not offered
        // ASIO it can never have. Runs on both CI legs, so each half is checked where it matters.
        val ids = Backends.all().map { it.id }.toSet()
        val os = System.getProperty("os.name", "")
        val windowsOnly = setOf(BackendId.ASIO, BackendId.WASAPI_EXCLUSIVE, BackendId.WASAPI_SHARED)
        val linuxOnly = setOf(BackendId.JACK, BackendId.ALSA)
        when {
            os.startsWith("Windows") -> {
                assertTrue(ids.none { it in linuxOnly }, "a Linux backend reached the Windows picker: $ids")
                assertTrue(ids.containsAll(windowsOnly), "a Windows backend went missing: $ids")
            }
            os.startsWith("Linux") -> {
                assertTrue(ids.none { it in windowsOnly }, "a Windows backend reached the Linux picker: $ids")
                assertTrue(ids.containsAll(linuxOnly), "a Linux backend went missing: $ids")
            }
        }
    }

    @Test
    fun `backends are ordered best first`() {
        val ranks = Backends.all().map { it.id.rank }
        assertEquals(ranks.sorted(), ranks, "backends must be offered lowest rank first")
    }

    @Test
    fun `an unavailable backend explains itself`() {
        // A hidden option is indistinguishable from a broken build, so every backend that will not
        // run here has to carry a reason the user can act on.
        for (backend in Backends.all()) {
            val reason = backend.availability.reason ?: continue
            assertTrue(
                reason.isNotBlank() && reason.length > 10,
                "${backend.id} is unavailable with a useless reason: \"$reason\"",
            )
        }
    }

    @Test
    fun `enumeration never throws whatever the platform`() {
        for (backend in Backends.all()) {
            backend.devices()      // must not throw, even when the backend is unavailable
            backend.availability
        }
    }

    @Test
    fun `a preferred backend can always be chosen`() {
        val preferred = Backends.preferred()
        assertTrue(preferred.availability.isAvailable, "preferred() returned an unavailable backend")
    }

    // ---- cache invalidation -------------------------------------------------

    @Test
    fun `rescan reaches every backend, not just the selected one`() {
        // The stale probe the user is trying to clear is one click behind whichever backend they
        // are looking at: rescan on WASAPI, switch the combo to ASIO, same bug again.
        val seen = ArrayList<BackendId>()
        Backends.invalidate(
            listOf(
                Recording(BackendId.ASIO, seen),
                Recording(BackendId.WASAPI_EXCLUSIVE, seen),
                Recording(BackendId.JAVA_SOUND, seen),
            )
        )
        assertEquals(listOf(BackendId.ASIO, BackendId.WASAPI_EXCLUSIVE, BackendId.JAVA_SOUND), seen)
    }

    @Test
    fun `one misbehaving backend does not stop the others being invalidated`() {
        // Rescan runs from a button handler on the event thread. A backend that throws must not
        // take the window down, and must not leave the backends after it holding stale probes.
        val seen = ArrayList<BackendId>()
        Backends.invalidate(
            listOf(
                Recording(BackendId.ASIO, seen, throws = true),
                Recording(BackendId.WASAPI_EXCLUSIVE, seen, throws = true),
                Recording(BackendId.JAVA_SOUND, seen),
            )
        )
        assertContains(seen, BackendId.JAVA_SOUND, "a throwing backend stopped the ones after it")
    }

    @Test
    fun `the default rescan is a no-op and never throws`() {
        // The whole point of the default body: a backend that caches nothing is unaffected, and
        // adding the hook cannot have broken one that has not been touched.
        val plain = object : AudioBackend {
            override val id = BackendId.JAVA_SOUND
            override val availability = Availability.Available
            override fun devices() = emptyList<BackendDevice>()
            override fun capabilities(device: BackendDevice, sampleRate: Int?) =
                DeviceCapabilities(listOf(44_100), listOf(512), 512, Pairing.ANY_PAIR)

            override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents) =
                throw AudioBackendException("not a real backend")
        }
        val before = plain.capabilities(BackendDevice(BackendId.JAVA_SOUND, "x", "x", 2, 2))
        plain.rescan()
        assertEquals(before, plain.capabilities(BackendDevice(BackendId.JAVA_SOUND, "x", "x", 2, 2)))
    }

    @Test
    fun `invalidating the registry never throws and leaves it usable`() {
        // Includes the real backends on this machine, whatever they are — the registry's promise is
        // that none of its entries can fail this, the same as `devices()`.
        Backends.rescan()
        Backends.rescan()
        assertTrue(Backends.all().isNotEmpty(), "the registry came back empty after an invalidation")
        for (backend in Backends.all()) backend.devices()
    }

    /** Counts invalidations, and can be told to misbehave the way a broken driver would. */
    private class Recording(
        override val id: BackendId,
        private val seen: MutableList<BackendId>,
        private val throws: Boolean = false,
    ) : AudioBackend {
        override val availability = Availability.Available
        override fun devices() = emptyList<BackendDevice>()
        override fun capabilities(device: BackendDevice, sampleRate: Int?) =
            DeviceCapabilities(listOf(44_100), listOf(512), 512, Pairing.ANY_PAIR)

        override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents) =
            throw AudioBackendException("not a real backend")

        override fun rescan() {
            seen += id
            if (throws) throw IllegalStateException("a driver that dies on invalidation")
        }
    }

    // ---- ASIO buffer size enumeration --------------------------------------

    @Test
    fun `powers of two are enumerated when granularity is minus one`() {
        // These are the real numbers from the interface this was developed against.
        val sizes = legalBufferSizes(intArrayOf(8, 2048, 512, -1))
        assertEquals(listOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048), sizes)
    }

    @Test
    fun `a minimum that is not a power of two is rounded up, not stepped from`() {
        // min=100 with granularity -1 must give 128, 256, 512 — never 100, 200, 400, none of which
        // the driver would accept.
        val sizes = legalBufferSizes(intArrayOf(100, 600, 256, -1))
        assertEquals(listOf(128, 256, 512), sizes)
    }

    @Test
    fun `a fixed-size driver offers exactly one choice`() {
        // Realtek's ASIO reports min == max == preferred == 1024, granularity 0.
        assertEquals(listOf(1024), legalBufferSizes(intArrayOf(1024, 1024, 1024, 0)))
        assertEquals(listOf(1024), legalBufferSizes(intArrayOf(1024, 1024, 1024, -1)))
    }

    @Test
    fun `a positive granularity steps from the minimum`() {
        assertEquals(listOf(64, 96, 128, 160, 192), legalBufferSizes(intArrayOf(64, 192, 128, 32)))
    }

    @Test
    fun `the preferred size is always offered`() {
        // Drivers do report a preferred value outside their own sequence; refusing to offer the one
        // size the driver actually asked for would be perverse.
        val sizes = legalBufferSizes(intArrayOf(64, 512, 300, -1))
        assertContains(sizes, 300)
        assertEquals(sizes.sorted(), sizes)
    }

    @Test
    fun `a granularity of one does not enumerate thousands of sizes`() {
        val sizes = legalBufferSizes(intArrayOf(16, 8192, 256, 1))
        assertTrue(sizes.size <= 65, "a step of 1 produced ${sizes.size} entries")
    }

    @Test
    fun `nonsense from a driver produces something usable rather than an empty picker`() {
        assertEquals(listOf(256), legalBufferSizes(intArrayOf(0, 0, 256, 0)))
        assertEquals(listOf(512), legalBufferSizes(intArrayOf(2048, 64, 512, -1)))
        assertTrue(legalBufferSizes(intArrayOf(-1, -1, -1, -1)).isNotEmpty())
    }

    @Test
    fun `every enumerated size is inside the driver's own range`() {
        for (report in listOf(
            intArrayOf(8, 2048, 512, -1),
            intArrayOf(64, 192, 128, 32),
            intArrayOf(128, 128, 128, 0),
            intArrayOf(100, 600, 256, -1),
        )) {
            for (size in legalBufferSizes(report)) {
                assertTrue(
                    size in report[0]..report[1],
                    "offered $size which is outside ${report[0]}..${report[1]}",
                )
            }
        }
    }

    // ---- latency arithmetic -------------------------------------------------

    @Test
    fun `the reported round trip is the sum of the driver's own terms`() {
        // The Sonicake at 128 frames, as ASIO getLatencies actually reports it.
        val format = StreamFormat(
            sampleRate = 44_100,
            bufferFrames = 128,
            inputLatencyFrames = 194,
            outputLatencyFrames = 243,
            adapterLatencyFrames = 0,
        )
        assertEquals(2.902f, format.callbackPeriodMs, 0.001f)
        assertEquals(9.909f, format.reportedRoundTripMs, 0.01f)
    }

    @Test
    fun `what an adapter adds is counted, not hidden`() {
        // A two-clock backend pays a ring hop that a single-clock one does not, and the figure the
        // user sees has to include it or the comparison between backends is dishonest.
        val duplex = StreamFormat(44_100, 264, 264, 264, 0)
        val twoClock = duplex.copy(adapterLatencyFrames = 264)
        assertTrue(twoClock.reportedRoundTripMs > duplex.reportedRoundTripMs)
        assertEquals(
            264 * 1000f / 44_100,
            twoClock.reportedRoundTripMs - duplex.reportedRoundTripMs,
            0.001f,
        )
    }

    @Test
    fun `latency figures scale with the sample rate`() {
        // The same frame counts at 96 kHz are a little over half the milliseconds. Anything that
        // assumed 44 100 would fail here.
        val slow = StreamFormat(44_100, 128, 194, 243, 0)
        val fast = StreamFormat(96_000, 128, 194, 243, 0)
        assertEquals(slow.reportedRoundTripMs * 44_100f / 96_000f, fast.reportedRoundTripMs, 0.01f)
    }
}
