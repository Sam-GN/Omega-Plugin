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
package org.example.audio.linux

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference

/**
 * Realtime scheduling on Linux — the sibling of `win.Rt`, and the answer to a different problem.
 *
 * Windows needed MMCSS and a raised timer. Linux needs neither: its scheduler has no 15.6 ms tick
 * to fight, and there is no "Pro Audio" class to join. What it has is `SCHED_FIFO`, which a JVM
 * cannot request for one of its own threads from Java. Two routes reach it, tried in this order:
 *
 * 1. **`pthread_setschedparam` directly.** Works when the user's `RLIMIT_RTPRIO` allows it — the
 *    `realtime` group on Arch, `@audio - rtprio 95` in `limits.d` on Debian and Ubuntu.
 * 2. **rtkit, over D-Bus.** The desktop's own broker, present by default on Fedora, Ubuntu and most
 *    others: it grants a modest priority to an unprivileged process, which is how PipeWire itself
 *    gets realtime. It insists the process caps `RLIMIT_RTTIME` first, so a runaway realtime thread
 *    is killed rather than freezing the machine.
 *
 * Neither is needed on the JACK path, where the callback thread belongs to the JACK server and is
 * already realtime. This is for threads that are ours: the ALSA backend's.
 *
 * Every call here allocates and some block, so all of it happens **before** a stream's loop starts,
 * never inside it.
 */
object LinuxRt {

    /** The outcome, phrased for a log line and for `--list-backends`. */
    data class Grant(val realtime: Boolean, val detail: String)

    private interface LibC : Library {
        fun pthread_self(): Long
        fun pthread_setschedparam(thread: Long, policy: Int, param: IntByReference): Int
        fun sched_getscheduler(pid: Int): Int
        fun getrlimit(resource: Int, rlim: Pointer): Int
        fun setrlimit(resource: Int, rlim: Pointer): Int
        fun syscall(number: Long): Long
    }

    private interface DBus : Library {
        fun dbus_error_init(error: Pointer)
        fun dbus_error_free(error: Pointer)
        fun dbus_bus_get_private(type: Int, error: Pointer): Pointer?
        fun dbus_connection_close(connection: Pointer)
        fun dbus_connection_unref(connection: Pointer)
        fun dbus_message_new_method_call(destination: String, path: String, iface: String, method: String): Pointer?
        fun dbus_message_append_args(message: Pointer, firstType: Int, vararg rest: Any?): Int
        fun dbus_connection_send_with_reply_and_block(
            connection: Pointer, message: Pointer, timeoutMillis: Int, error: Pointer,
        ): Pointer?
        fun dbus_message_unref(message: Pointer)
    }

    private val libc: LibC? by lazy { runCatching { Native.load("c", LibC::class.java) }.getOrNull() }

    private val dbus: DBus? by lazy {
        runCatching { Native.load("libdbus-1.so.3", DBus::class.java) }.getOrNull()
    }

    private const val SCHED_FIFO = 1
    private const val SCHED_RESET_ON_FORK = 0x40000000
    private const val RLIMIT_RTPRIO = 14
    private const val RLIMIT_RTTIME = 15

    /**
     * What we ask for directly. Below the 88 PipeWire's own data thread uses and the ~80 a JACK
     * server sits at: the device thread that feeds us should win over us, never the other way.
     */
    const val PRIORITY = 70

    /** rtkit's usual ceiling. It refuses outright above its configured maximum rather than clamping. */
    private val RTKIT_PRIORITIES = intArrayOf(20, 10, 5)

    /** rtkit's default limit on realtime CPU time without blocking, in microseconds. */
    private const val RTKIT_RTTIME_MAX = 200_000L

    /**
     * Makes the **calling** thread realtime if anything on this machine will allow it.
     *
     * Not fatal when refused, only worse — the stream still runs, at normal priority, and the
     * result says why so the user can be told what to change.
     */
    fun promoteCurrentThread(): Grant {
        val c = libc ?: return Grant(false, "libc could not be loaded through JNA")

        val direct = runCatching {
            c.pthread_setschedparam(c.pthread_self(), SCHED_FIFO or SCHED_RESET_ON_FORK, IntByReference(PRIORITY))
        }.getOrDefault(-1)
        if (direct == 0) return Grant(true, "SCHED_FIFO priority $PRIORITY, granted by the account's rtprio limit")

        val viaRtkit = rtkit(c)
        if (viaRtkit != null) return viaRtkit

        return Grant(false, refusalAdvice())
    }

    /** rtkit's `MakeThreadRealtime`, by thread id. Null when it refused or is not there. */
    private fun rtkit(c: LibC): Grant? {
        val d = dbus ?: return null
        if (!capRealtimeCpuTime(c)) return null
        val tid = threadId(c) ?: return null

        val error = Memory(64).apply { clear() }
        d.dbus_error_init(error)
        val connection = runCatching { d.dbus_bus_get_private(DBUS_BUS_SYSTEM, error) }.getOrNull()
        if (connection == null) {
            d.dbus_error_free(error)
            return null
        }
        try {
            for (priority in RTKIT_PRIORITIES) {
                val message = d.dbus_message_new_method_call(
                    "org.freedesktop.RealtimeKit1",
                    "/org/freedesktop/RealtimeKit1",
                    "org.freedesktop.RealtimeKit1",
                    "MakeThreadRealtime",
                ) ?: return null
                try {
                    d.dbus_message_append_args(
                        message,
                        DBUS_TYPE_UINT64, LongByReference(tid),
                        DBUS_TYPE_UINT32, IntByReference(priority),
                        DBUS_TYPE_INVALID,
                    )
                    val reply = d.dbus_connection_send_with_reply_and_block(connection, message, 2_000, error)
                    if (reply != null) {
                        d.dbus_message_unref(reply)
                        return Grant(true, "SCHED_FIFO priority $priority, granted by rtkit")
                    }
                    d.dbus_error_free(error)
                    d.dbus_error_init(error)
                } finally {
                    d.dbus_message_unref(message)
                }
            }
            return null
        } finally {
            runCatching { d.dbus_connection_close(connection) }
            runCatching { d.dbus_connection_unref(connection) }
            d.dbus_error_free(error)
        }
    }

    /**
     * rtkit refuses any process whose `RLIMIT_RTTIME` is unlimited, so cap it at rtkit's own figure.
     *
     * Safe for this program: the only realtime thread is an audio loop that blocks on the device
     * every period, which resets the count. A thread that spun for 200 ms without blocking would be
     * signalled — and at a 1 ms deadline such a thread has already failed in every way that matters.
     */
    private fun capRealtimeCpuTime(c: LibC): Boolean {
        val limit = Memory(16)
        if (c.getrlimit(RLIMIT_RTTIME, limit) != 0) return false
        val soft = limit.getLong(0)
        val hard = limit.getLong(8)
        // RLIM_INFINITY is all ones, so it reads back negative.
        if (soft in 0..RTKIT_RTTIME_MAX && hard in 0..RTKIT_RTTIME_MAX) return true
        val cap = if (hard in 0..RTKIT_RTTIME_MAX) hard else RTKIT_RTTIME_MAX
        limit.setLong(0, cap)
        limit.setLong(8, cap)
        return c.setrlimit(RLIMIT_RTTIME, limit) == 0
    }

    /** The kernel's id for this thread — not `pthread_self`, which rtkit cannot use. */
    private fun threadId(c: LibC): Long? {
        val number = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> 186L
            "aarch64", "arm64" -> 178L
            else -> return null
        }
        return runCatching { c.syscall(number) }.getOrNull()?.takeIf { it > 0 }
    }

    /** The account's `RLIMIT_RTPRIO`, or null when it cannot be read. */
    fun rtprioLimit(): Long? {
        val c = libc ?: return null
        val limit = Memory(16)
        return if (c.getrlimit(RLIMIT_RTPRIO, limit) == 0) limit.getLong(0) else null
    }

    /**
     * The sentence to show when neither route worked. Distribution families differ in the fix, and
     * "it crackles" with no explanation is the failure this exists to prevent.
     */
    fun refusalAdvice(): String {
        val rtprio = rtprioLimit()
        return "not granted (rtprio limit ${rtprio ?: "unknown"}, rtkit refused or absent). " +
            "Arch/Manjaro: install realtime-privileges and add yourself to the realtime group. " +
            "Debian/Ubuntu/Mint: add yourself to the audio group and allow it rtprio in " +
            "/etc/security/limits.d. Fedora: rtkit should allow it; check it is running. " +
            "Log out and back in afterwards."
    }

    /**
     * Whether a thread of ours *would* be granted realtime, answered by trying it on a throwaway
     * thread. The promotion dies with that thread. Honest where reading limits would only be a
     * guess — rtkit's policy is not something a process can query in advance.
     */
    fun probe(): Grant {
        if (!LinuxPlatform.isLinux) return Grant(false, "not Linux")
        var result = Grant(false, "probe did not run")
        val t = Thread({ result = promoteCurrentThread() }, "omega-rt-probe")
        t.isDaemon = true
        t.start()
        t.join(5_000)
        return result
    }

    private const val DBUS_BUS_SYSTEM = 1
    private const val DBUS_TYPE_INVALID = 0
    private const val DBUS_TYPE_UINT32 = 'u'.code
    private const val DBUS_TYPE_UINT64 = 't'.code
}
