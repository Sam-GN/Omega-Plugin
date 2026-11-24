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
package org.example

import java.lang.management.ManagementFactory
import kotlin.test.assertTrue

/**
 * Bytes allocated so far by the calling thread, for the tests that hold the audio thread to no
 * allocation (2.3.0).
 *
 * Until 2.3.0 each of those tests read this through reflection, which Java 21's module system
 * refuses — `InaccessibleObjectException` — and returned -1, and each test took -1 to mean "not a
 * HotSpot JVM" and returned without asserting anything. Every "allocates under 64 kB" check in
 * `AudioEngineTest`, `BlockSizeContractTest` and `TransportTest` passed without measuring. This
 * reads it through `com.sun.management.ThreadMXBean`, which `jdk.management` exports, and fails
 * rather than skips if the counter is missing: the build pins Temurin, so a missing counter is a
 * fault to hear about.
 *
 * **A read is the counter and nothing else.** The checks run once, when the object is first
 * touched — the read before the measured stretch, never the one after it. A first version checked
 * on every read, and kotlin.test's assertions allocate: 135 kB landed inside the measured window
 * and read as the tuner allocating, where the callback measured 136 bytes over 20 000 blocks.
 */
object Allocation {
    private val bean: com.sun.management.ThreadMXBean = run {
        val b = ManagementFactory.getThreadMXBean()
        assertTrue(b is com.sun.management.ThreadMXBean, "this JVM has no per-thread allocation counter")
        assertTrue(b.isThreadAllocatedMemorySupported, "this JVM cannot count per-thread allocation")
        if (!b.isThreadAllocatedMemoryEnabled) b.isThreadAllocatedMemoryEnabled = true
        assertTrue(b.currentThreadAllocatedBytes >= 0, "the allocation counter does not read")
        b
    }

    fun currentThreadBytes(): Long = bean.currentThreadAllocatedBytes
}
