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

import org.example.audio.javasound.JavaSoundBackend
import org.example.util.Log

/**
 * The backend ladder.
 *
 * "The app always starts and always makes sound" is a structural property here, not a runtime hope:
 *
 * - Optional backends are named as **strings** and reached through `Class.forName`, so a class that
 *   is not on the classpath is a caught exception rather than a link error while this object is
 *   still initialising.
 * - The catch is `Throwable`, not `Exception`. `UnsatisfiedLinkError` and `NoClassDefFoundError` are
 *   `Error`s, and catching only `Exception` is the single most common way this pattern fails — a
 *   missing JNA native, a 32/64-bit mismatch and an antivirus quarantine all arrive as `Error`.
 * - [JavaSoundBackend] is compiled in with no optional dependency, so it cannot fail to be present.
 *
 * A backend that will not load therefore becomes an entry whose [AudioBackend.availability] is
 * [Availability.Unavailable] carrying the reason verbatim, and the UI can say *why* instead of
 * silently offering less. `--list-backends` prints exactly that, and is the fastest answer to "why
 * is it 200 ms on this machine".
 */
object Backends {

    private val OPTIONAL = listOf(
        "org.example.audio.asio.AsioBackend",
        "org.example.audio.wasapi.WasapiBackend",
        "org.example.audio.wasapi.WasapiSharedBackend",
        // Linux, when it is written. Nothing above this line assumes Windows.
        "org.example.audio.jack.JackBackend",
        "org.example.audio.alsa.AlsaBackend",
    )

    private val cached: List<AudioBackend> by lazy { load() }

    /** Never throws, never returns empty, ordered by [BackendId.rank]. */
    fun all(): List<AudioBackend> = cached

    fun available(): List<AudioBackend> = cached.filter { it.availability.isAvailable }

    fun byId(id: BackendId): AudioBackend? = cached.firstOrNull { it.id == id }

    /**
     * The backend to use when nobody has chosen one: lowest rank that is available *and* can see a
     * device. A driver that loads but enumerates nothing is not a usable default, and silently
     * landing on it would look like the app is broken.
     */
    fun preferred(): AudioBackend =
        available().firstOrNull { runCatching { it.devices().isNotEmpty() }.getOrDefault(false) }
            ?: available().first()

    /**
     * Discards every backend's cached view of this machine, ahead of a user-requested re-enumerate.
     *
     * **Every loaded backend, not just the selected one.** The stale cache the user is trying to
     * clear is one click behind whichever backend they are looking at: rescan on WASAPI, then switch
     * the driver combo to ASIO, and a per-backend invalidation would have left the same bug sitting
     * there. Backends that cache nothing inherit an empty [AudioBackend.rescan] and cost nothing.
     *
     * Wrapped in `runCatching` for the same reason [shutdown] is: this is called from a button
     * handler on the event thread, and one misbehaving backend must not stop the others being
     * refreshed or take the window down with it.
     */
    fun rescan() = invalidate(cached)

    /**
     * The body of [rescan], over a list the caller supplies.
     *
     * Split out for one reason: it is the tolerance that matters here, and a test cannot put a
     * misbehaving backend into the real registry. This way the "keep going past a throw" promise is
     * asserted against the code that actually runs rather than against a copy of it in the test.
     */
    internal fun invalidate(backends: List<AudioBackend>) {
        for (backend in backends) runCatching { backend.rescan() }
    }

    fun shutdown() {
        cached.forEach { runCatching { it.shutdown() } }
    }

    private fun load(): List<AudioBackend> {
        val found = ArrayList<AudioBackend>()
        for (className in OPTIONAL) {
            try {
                val instance = Class.forName(className).getDeclaredConstructor().newInstance()
                found += instance as AudioBackend
            } catch (_: ClassNotFoundException) {
                // Not built for this platform. Nothing to report — an ALSA backend missing on
                // Windows is not a fault, and listing it as broken would be noise.
            } catch (t: Throwable) {
                found += Broken(className, t)
            }
        }
        found += JavaSoundBackend()
        val ordered = found.sortedBy { it.id.rank }

        // The single most useful thing in a submitted log. "Why is there 200 ms of delay on this
        // PC" is nearly always answered here — no ASIO driver installed, JNA failing to load, a
        // device held exclusively by something else — and the reason each backend gives is written
        // to be read by the person who has to act on it.
        for (backend in ordered) {
            val reason = backend.availability.reason
            if (reason == null) {
                Log.info("backends", "${backend.id.displayName}: available.")
            } else {
                Log.warn("backends", "${backend.id.displayName}: UNAVAILABLE — $reason")
            }
        }
        return ordered
    }

    /**
     * A backend whose class exists but could not be constructed. It keeps its place in the list so
     * the reason reaches the user rather than the backend just vanishing.
     */
    private class Broken(className: String, cause: Throwable) : AudioBackend {
        override val id: BackendId = when {
            className.contains(".asio.") -> BackendId.ASIO
            className.endsWith("WasapiSharedBackend") -> BackendId.WASAPI_SHARED
            className.contains(".wasapi.") -> BackendId.WASAPI_EXCLUSIVE
            className.contains(".jack.") -> BackendId.JACK
            else -> BackendId.ALSA
        }
        override val availability = Availability.Unavailable(rootCause(cause))

        override fun devices(): List<BackendDevice> = emptyList()
        override fun capabilities(device: BackendDevice, sampleRate: Int?) =
            DeviceCapabilities(emptyList(), emptyList(), 0, Pairing.ANY_PAIR)

        override fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents) =
            throw AudioBackendException(availability.reason ?: "unavailable")
    }

    /**
     * Digs the real failure out from under reflection.
     *
     * `newInstance` wraps everything in `InvocationTargetException`, and a class initialiser failure
     * arrives wrapped again in `ExceptionInInitializerError`. Reporting the wrapper produces
     * "InvocationTargetException: no detail", which tells a user nothing — the useful sentence is
     * two or three `cause` hops down, and it is the whole reason this entry exists at all.
     */
    private fun rootCause(t: Throwable): String {
        var current = t
        var guard = 0
        while (guard++ < 8) {
            val next = current.cause ?: break
            if (current !is java.lang.reflect.InvocationTargetException &&
                current !is ExceptionInInitializerError &&
                current.message != null
            ) break
            current = next
        }
        val detail = current.message?.takeIf { it.isNotBlank() }
        return when (current) {
            is NoClassDefFoundError, is ClassNotFoundException ->
                "a required library is missing (${detail ?: current.javaClass.simpleName}). " +
                    "The build that produced this jar is incomplete."
            is UnsatisfiedLinkError ->
                "a native library would not load (${detail ?: "no detail"}). On Windows this is " +
                    "usually a missing Visual C++ runtime or an antivirus quarantine; on Linux, a " +
                    "/tmp mounted noexec."
            else -> "${current.javaClass.simpleName}: ${detail ?: "no detail"}"
        }
    }
}
