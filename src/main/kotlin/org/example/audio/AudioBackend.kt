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

/**
 * The seam between the effect chain and whatever moves audio on this machine.
 *
 * The unit is a **duplex stream driven by one clock**, and the direction of control is
 * push: the backend calls us. Every low-latency API works that way — ASIO and JACK call
 * on the driver's own thread, WASAPI-exclusive and ALSA `hw:` wake a thread of ours on an
 * event and are wrapped to look the same. Java Sound, which is genuinely two devices on two
 * crystals with a blocking read, is the awkward one and adapts internally rather than being
 * allowed to dictate the shape here.
 *
 * Everything device-shaped stops at this line. Channel selection, sample-format conversion and
 * interleaving are the backend's job, because only the backend knows its own buffer layout; what
 * crosses is mono float in [-1, 1]. That is what lets `dsp/` stay untouched by any of this, and it
 * is why a Linux ALSA or JACK implementation can drop in behind the same interface — see the notes
 * on [Pairing], [DeviceCapabilities.fixedSampleRate] and [StreamEvents.onFormatChanged], each of
 * which exists because of a backend that is not Windows.
 */

/** Which implementation, not which device. [rank] orders automatic selection: lowest wins. */
enum class BackendId(val displayName: String, val rank: Int) {
    ASIO("ASIO", 0),
    WASAPI_EXCLUSIVE("WASAPI exclusive", 1),

    /** Not implemented here. Listed so the ladder and the UI already have a place for it. */
    JACK("JACK / PipeWire", 1),

    /** Not implemented here. */
    ALSA("ALSA direct", 2),

    /**
     * The same API as [WASAPI_EXCLUSIVE], through the Windows mixer instead of around it.
     *
     * Ranked below exclusive because it is slower, but above Java Sound by a wide margin — and it is
     * the only rung short of Java Sound that lets other applications keep using the device while
     * Omega is running.
     */
    WASAPI_SHARED("WASAPI shared", 3),
    JAVA_SOUND("Java Sound", 9),
}

sealed class Availability {
    object Available : Availability()

    /** Shown to the user verbatim: "no ASIO driver is installed", "jna failed to load: …". */
    data class Unavailable(val message: String) : Availability()

    val isAvailable: Boolean get() = this is Available

    /** Null when available; otherwise the sentence to put in front of the user, unedited. */
    val reason: String? get() = (this as? Unavailable)?.message
}

/**
 * A device as its backend names it.
 *
 * [id] must survive a disconnect, a reconnect and a restart, so it is a driver-provided string —
 * never an index (USB indices shuffle when anything else is plugged in) and never a display name.
 * Java Sound has nothing stable to offer and synthesises one from the mixer name, accepting that a
 * driver rename breaks a saved selection.
 */
data class BackendDevice(
    val backend: BackendId,
    val id: String,
    val name: String,
    val inputChannels: Int,
    val outputChannels: Int,
) {
    override fun toString(): String = name
}

enum class Pairing {
    /** ASIO, JACK: one driver owns both directions, so the UI picks one device. */
    SINGLE_DEVICE,

    /** Java Sound, WASAPI: the two directions are chosen separately and run on separate clocks. */
    ANY_PAIR,
}

data class DeviceCapabilities(
    /** Rates the device will accept. Empty means the server dictates it — see [fixedSampleRate]. */
    val sampleRates: List<Int>,
    /**
     * Legal frame counts **at the rate this was asked about**, ascending. The UI must never offer
     * anything that is not in here.
     *
     * The rate qualifier is not pedantry: WASAPI's legal buffers are whole multiples of a device
     * period expressed in time, so the same endpoint yields different frame counts at 44.1 and
     * 48 kHz. ASIO reports frames directly and is mostly rate-independent. Ask
     * [AudioBackend.capabilities] again when the rate changes rather than scaling these.
     */
    val bufferSizes: List<Int>,
    /** What the driver would rather have. A first run that xruns is worse than one at 14 ms. */
    val preferredBufferFrames: Int,
    val pairing: Pairing,
    /**
     * The rate this device is already set to, when that is knowable — ASIO's current driver-panel
     * rate, WASAPI's endpoint mix-format rate. Not a guess, so it is what the picker opens on.
     */
    val preferredSampleRate: Int = sampleRates.firstOrNull() ?: 0,
    /** Non-null when the rate is not ours to choose (JACK). The UI shows it read-only. */
    val fixedSampleRate: Int? = null,
    val hasControlPanel: Boolean = false,
)

data class StreamRequest(
    val input: BackendDevice?,
    /** Which hardware input the guitar is on. On a multi-input interface this is not cosmetic. */
    val inputChannel: Int,
    val output: BackendDevice?,
    /** One or two hardware outputs; the mono chain is duplicated across them. */
    val outputChannels: IntArray,
    val sampleRate: Int,
    val bufferFrames: Int,
) {
    // IntArray in a data class: identity equals/hashCode would be wrong and surprising.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * What the driver actually gave us, which is authoritative — [StreamRequest] was a preference and
 * drivers routinely round the buffer, refuse the rate, or change both later. Nothing downstream may
 * cache what was asked for.
 */
data class StreamFormat(
    val sampleRate: Int,
    val bufferFrames: Int,
    /** Driver-reported. ASIO `getLatencies`; WASAPI `GetStreamLatency` plus the period. */
    val inputLatencyFrames: Int,
    val outputLatencyFrames: Int,
    /** What this adapter added itself: zero for a true duplex callback, the cushion for Java Sound. */
    val adapterLatencyFrames: Int,
) {
    val callbackPeriodMs: Float get() = bufferFrames * 1000f / sampleRate

    /**
     * The driver's own round-trip claim. It includes the driver's safety offsets and excludes
     * converter latency, so it is a good number and not the truth — `--measure` is.
     */
    val reportedRoundTripMs: Float
        get() = (inputLatencyFrames + outputLatencyFrames + adapterLatencyFrames) * 1000f / sampleRate
}

fun interface AudioCallback {
    /**
     * Called on the driver's thread, or on a thread the adapter woke from a device event.
     *
     * Mono in, mono out, two distinct pre-allocated arrays reused on every call — retain neither.
     * Must return in a fraction of the callback period; see the threading contract in CLAUDE.md for
     * what that forbids, which at these block sizes is most things.
     */
    fun render(input: FloatArray, output: FloatArray, frames: Int)
}

/**
 * Out-of-band notifications. **Never** raised from the callback thread: the audio side sets a
 * volatile flag and a monitor thread — normal priority, allowed to allocate and log — dispatches.
 * That is what keeps "no I/O on the audio thread" intact at the moment it is most tempting to break.
 */
interface StreamEvents {
    /** The driver changed buffer size or rate under us (ASIO panel, JACK server). Re-read, redisplay. */
    fun onFormatChanged(format: StreamFormat) {}
    fun onDeviceLost(reason: String) {}
    fun onFatal(reason: String) {}
}

interface AudioStream : AutoCloseable {
    val format: StreamFormat

    /** Buffers the driver could not deliver or consume in time. Volatile; polled by the UI. */
    val xruns: Int

    /** Callbacks whose body ran longer than one period. The number that decides the buffer size. */
    val deadlineMisses: Int

    /** Longest single callback body, microseconds. */
    val worstCallbackMicros: Int

    /**
     * Frames actually moved, divided by frames wall-clock time called for. 1.0 is healthy.
     *
     * This is the metric that catches the failure the other two miss. A garbage collection stalls
     * the callback thread *between* callbacks, so the body is never slow and no deadline is missed —
     * the driver simply gets nothing and replays whatever was in its buffer. Measured at a 64-frame
     * ASIO buffer under allocation pressure, G1 sat at 0.9885 while generational ZGC sat at 0.9999.
     * Anything below about 0.995 means the device was starved and the user heard it.
     */
    val realtimeRatio: Float get() = 1f

    fun start()
    fun stop()
    override fun close()
}

class AudioBackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AudioBackend {
    val id: BackendId
    val availability: Availability

    /** Never throws: a backend that cannot enumerate returns empty and says why in [availability]. */
    fun devices(): List<BackendDevice>

    /**
     * @param sampleRate the rate the caller intends to open at, or null for the device's own
     *   preference. Backends whose legal buffer sizes depend on the rate must honour it; the rest
     *   may ignore it.
     */
    fun capabilities(device: BackendDevice, sampleRate: Int? = null): DeviceCapabilities

    /** @throws AudioBackendException with a sentence a user can act on. */
    fun open(request: StreamRequest, callback: AudioCallback, events: StreamEvents): AudioStream

    /** Optional capability — ASIO only, in practice. Blocks for seconds; never call from the EDT. */
    fun openControlPanel(device: BackendDevice) {}

    /**
     * "Forget what you learned about this machine." Called before a user-requested re-enumeration.
     *
     * Probing a device is expensive — `WasapiBackend` opens each endpoint exclusively to find out
     * which buffer sizes it will really take — so caching the answer is right. What was missing was
     * any way to say the answer has expired. Everything a
     * backend caches is a statement about hardware and about *Windows sound settings*, and both can
     * change while the app is running: a user told that their input and output share no sample rate
     * will go and change one of them, come back, and press Rescan. If nothing here discards the old
     * probe, the app repeats the stale complaint until it is restarted.
     *
     * The default body is empty, and that is the correct implementation for a backend that derives
     * its capabilities freshly on every call. Like [shutdown] and [openControlPanel] it must never
     * throw; the registry catches anyway, but a backend that relies on that is misreporting.
     *
     * [devices] is normally called immediately afterwards, so a backend does not need to re-probe
     * eagerly here — dropping what it holds is enough.
     */
    fun rescan() {}

    /** Released on exit. Under an exclusive-mode backend, failing to do this can need a reboot. */
    fun shutdown() {}
}
