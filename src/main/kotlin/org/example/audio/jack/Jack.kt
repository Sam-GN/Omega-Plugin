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
package org.example.audio.jack

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.File

/**
 * The JACK client API, declared by hand from `jack/jack.h` — no headers, no build step, the same
 * technique as `IASIO`.
 *
 * Two mappings of one library, on purpose. [JackApi] is JNA's interface mapping: convenient, and
 * it allocates an argument array on every call, which is fine for the control path. The single
 * function the realtime path needs is in [JackRt] as a **direct** mapping, which passes primitives
 * straight through and allocates nothing — the difference between obeying the threading contract
 * and not.
 */
internal interface JackApi : Library {
    fun jack_client_open(name: String, options: Int, status: IntByReference?, vararg rest: Any?): Pointer?
    fun jack_client_close(client: Pointer): Int
    fun jack_get_client_name(client: Pointer): String
    fun jack_get_sample_rate(client: Pointer): Int
    fun jack_get_buffer_size(client: Pointer): Int
    fun jack_set_buffer_size(client: Pointer, frames: Int): Int
    fun jack_is_realtime(client: Pointer): Int
    fun jack_activate(client: Pointer): Int
    fun jack_deactivate(client: Pointer): Int

    fun jack_port_register(client: Pointer, name: String, type: String, flags: Long, bufferSize: Long): Pointer?
    fun jack_port_name(port: Pointer): String
    fun jack_port_by_name(client: Pointer, name: String): Pointer?
    fun jack_port_get_latency_range(port: Pointer, mode: Int, range: IntArray)
    fun jack_get_ports(client: Pointer, namePattern: String?, typePattern: String?, flags: Long): Pointer?
    fun jack_connect(client: Pointer, source: String, destination: String): Int
    fun jack_free(pointer: Pointer)

    fun jack_set_process_callback(client: Pointer, callback: ProcessCallback, arg: Pointer?): Int
    fun jack_set_buffer_size_callback(client: Pointer, callback: BufferSizeCallback, arg: Pointer?): Int
    fun jack_set_xrun_callback(client: Pointer, callback: XrunCallback, arg: Pointer?): Int
    fun jack_on_shutdown(client: Pointer, callback: ShutdownCallback, arg: Pointer?)
    fun jack_set_error_function(callback: MessageCallback)
    fun jack_set_info_function(callback: MessageCallback)

    fun interface ProcessCallback : Callback { fun invoke(frames: Int, arg: Pointer?): Int }
    fun interface BufferSizeCallback : Callback { fun invoke(frames: Int, arg: Pointer?): Int }
    fun interface XrunCallback : Callback { fun invoke(arg: Pointer?): Int }
    fun interface ShutdownCallback : Callback { fun invoke(arg: Pointer?) }
    fun interface MessageCallback : Callback { fun invoke(message: String?) }

    companion object {
        const val JackNoStartServer = 0x01
        const val JackPortIsInput = 0x1L
        const val JackPortIsOutput = 0x2L
        const val JackPortIsPhysical = 0x4L
        const val JackCaptureLatency = 0
        const val JackPlaybackLatency = 1
        const val AUDIO_TYPE = "32 bit float mono audio"
    }
}

/** The realtime half. Registered against whichever `libjack` [JackLibrary.load] settled on. */
internal object JackRt {
    /** `void* jack_port_get_buffer(jack_port_t*, jack_nframes_t)` — the address as a plain long. */
    @JvmStatic
    external fun jack_port_get_buffer(port: Pointer, frames: Int): Long

    @Volatile
    private var registered = false

    @Synchronized
    fun register(library: NativeLibrary) {
        if (registered) return
        Native.register(JackRt::class.java, library)
        registered = true
    }
}

/**
 * Which `libjack.so.0`, and why the choice is not just "the one on the library path".
 *
 * There are two implementations of the JACK API on a Linux desktop and they install the same
 * soname. Real JACK (jack2) talks to a `jackd` that may not be running; PipeWire's shim talks to the
 * PipeWire server that almost certainly is. Several distributions ship the shim *outside* the
 * library path — Debian and Ubuntu under `pipewire-0.3/jack/`, reached only through `pw-jack` — and
 * on a machine that also has jack2 installed for some other program, loading by soname finds jack2,
 * finds no server, and reports JACK as unusable while PipeWire sits there able to serve it.
 *
 * So when a PipeWire server is running, its shim is tried first by explicit path, and the soname
 * second. Each candidate has to actually *connect* before it counts.
 */
internal object JackLibrary {

    data class Loaded(val api: JackApi, val native: NativeLibrary, val path: String)

    /** The shim's locations by distribution family. Missing ones are skipped. */
    private val PIPEWIRE_SHIMS = listOf(
        "/usr/lib/x86_64-linux-gnu/pipewire-0.3/jack/libjack.so.0",
        "/usr/lib/aarch64-linux-gnu/pipewire-0.3/jack/libjack.so.0",
        "/usr/lib64/pipewire-0.3/jack/libjack.so.0",
        "/usr/lib/pipewire-0.3/jack/libjack.so.0",
    )

    fun pipewireRunning(): Boolean {
        val runtime = System.getenv("XDG_RUNTIME_DIR") ?: return false
        return File(runtime, "pipewire-0").exists()
    }

    /** Candidates in the order they should be tried, each a path or a soname. */
    fun candidates(): List<String> {
        val shims = PIPEWIRE_SHIMS.filter { File(it).isFile }
        return if (pipewireRunning()) shims + "libjack.so.0" else listOf("libjack.so.0") + shims
    }

    fun load(candidate: String): Loaded {
        val native = NativeLibrary.getInstance(candidate)
        val api = Native.load(candidate, JackApi::class.java)
        return Loaded(api, native, candidate)
    }

    /** Swallows libjack's own chatter — "Cannot connect to server socket" on every probe. */
    val silence = JackApi.MessageCallback { }
}
