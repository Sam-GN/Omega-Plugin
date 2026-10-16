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
package org.example.util

import java.io.BufferedWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The troubleshooting log — `logs/omega.txt` in the settings folder, written only when switched on.
 *
 * This exists for one situation: something is wrong on a machine nobody here can touch, and the only
 * evidence is what the user is willing to send. Everything about the design follows from that.
 *
 * **Off by default, and off means no file.** Not "a file nothing is written to" — the writer thread
 * is not running and the handle is not open. A diagnostic that costs something when you are not using
 * it is one people turn off and then cannot turn on when it matters.
 *
 * **Never called from the audio thread.** Every rule in the threading contract applies to this class
 * with particular force: it allocates a string, it takes a queue slot, and behind it is a disk. The
 * calls placed in this program are on the EDT, on stream-monitor threads and on the opening thread —
 * never in `render` and never in a backend callback. The asynchronous writer below narrows the damage
 * if someone breaks that rule later, but it does not make it safe, and it is not an invitation.
 *
 * **The caller never waits for a disk.** A bounded queue and one daemon writer, so a stalled or full
 * filesystem cannot freeze the EDT mid-repaint. When the queue is full, lines are dropped and counted
 * rather than blocking the caller — and the count is written out, because a log with a silent hole in
 * it is worse than one that admits to it.
 *
 * **Flushed whenever the queue drains.** The interesting log is almost always one that ends in a
 * crash, so buffering the last few seconds into oblivion would lose exactly the part worth having.
 */
object Log {

    /** Whether anything is being written. Read from several threads; see [setEnabled]. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** Where the log would go, once [installIn] has been called. Null before that. */
    @Volatile
    var folder: Path? = null
        private set

    val file: Path? get() = folder?.resolve(FILE)

    private val queue = ArrayBlockingQueue<String>(QUEUE_LINES)
    private val dropped = AtomicInteger()

    /** Non-null only while running. Guarded by `this`, along with every start/stop transition. */
    private var writer: Thread? = null

    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * Names the folder. Does not create it and does not start anything.
     *
     * Separate from [setEnabled] so that startup order is not a trap: the settings root is known long
     * before the stored preference has been read, and a log call arriving in between should be
     * discarded quietly rather than land somewhere improvised.
     */
    fun installIn(settingsRoot: Path) {
        folder = settingsRoot.resolve(FOLDER)
    }

    /**
     * Turns writing on or off. Idempotent, and safe to call before [installIn] (it does nothing).
     *
     * Turning off drains what is queued and closes the file, so the last thing a user does before
     * sending the log cannot truncate it.
     */
    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        if (on) {
            val target = folder ?: return
            enabled = true
            writer = Thread({ pump(target) }, "omega-log").apply {
                isDaemon = true
                // Below normal: this thread exists to serve threads that matter more than it does,
                // and it is doing I/O. It must never be scheduled ahead of the UI, let alone audio.
                priority = Thread.MIN_PRIORITY
                start()
            }
            banner()
        } else {
            // Ordered: the flag stops new lines, the sentinel ends the thread after the backlog.
            info("log", "Logging turned off.")
            enabled = false
            queue.offer(POISON)
            writer?.join(2_000)
            writer = null
        }
    }

    /**
     * Forgets the folder, so a test can have a virgin logger.
     *
     * `internal`, and it exists only because this is an object: the application calls [installIn]
     * exactly once at startup and never needs to undo it, but the test that pins "a call arriving
     * before the folder is known is discarded" cannot demonstrate that on an instance some earlier
     * test has already installed. The alternative was to drop that test, and it guards a startup
     * ordering trap that would otherwise be found the hard way.
     */
    @Synchronized
    internal fun resetForTests() {
        setEnabled(false)
        folder = null
        queue.clear()
        dropped.set(0)
    }

    /** Routine progress: what was chosen, what opened, what closed. */
    fun info(tag: String, message: String) = write("INFO ", tag, message)

    /** Something recoverable that changes behaviour — a fallback taken, a preference ignored. */
    fun warn(tag: String, message: String) = write("WARN ", tag, message)

    /**
     * Something failed. The stack trace is included when there is one, because the message alone is
     * routinely the least informative part of a JNA or driver failure.
     */
    fun error(tag: String, message: String, error: Throwable? = null) {
        write("ERROR", tag, message)
        if (error != null && enabled) {
            val trace = StringWriter()
            PrintWriter(trace).use { error.printStackTrace(it) }
            for (line in trace.toString().lineSequence()) {
                if (line.isNotBlank()) enqueue("                             $line")
            }
        }
    }

    /** Drains and closes on the way out, so a clean exit does not lose the tail. */
    @Synchronized
    fun shutdown() {
        if (!enabled) return
        info("app", "Shutting down.")
        setEnabled(false)
    }

    private fun write(level: String, tag: String, message: String) {
        if (!enabled) return
        enqueue("${LocalDateTime.now().format(timestamp)}  $level  ${tag.padEnd(8)}  $message")
    }

    private fun enqueue(line: String) {
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    /**
     * The one line that makes a submitted log worth reading.
     *
     * Machine, JVM and collector, up front. Nearly every "it crackles on my PC" report turns on one
     * of these three, and none of them is something a user can be expected to report accurately.
     */
    private fun banner() {
        val version = javaClass.`package`?.implementationVersion ?: "unknown (not run from the jar)"
        val collectors = java.lang.management.ManagementFactory
            .getGarbageCollectorMXBeans()
            .joinToString(" + ") { it.name }
        info("app", "--- Omega-Plugin $version ---")
        info(
            "app",
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                "(${System.getProperty("os.arch")}), ${Runtime.getRuntime().availableProcessors()} CPUs",
        )
        info(
            "app",
            "Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}), " +
                "collector: ${collectors.ifEmpty { "unknown" }}",
        )
        // The single most common cause of dropouts that no amount of buffer tuning will fix, and it
        // is invisible to the user. Worth saying loudly, once, at the top.
        if (!collectors.contains("ZGC", ignoreCase = true)) {
            warn("app", "NOT running generational ZGC — expect dropouts at small buffer sizes.")
        }
    }

    /**
     * The writer thread.
     *
     * Everything here is best-effort. A log that cannot be written must never become a second
     * problem on top of the one being investigated, so an I/O failure switches logging off and the
     * program carries on without it.
     */
    private fun pump(target: Path) {
        val out: BufferedWriter = try {
            Files.createDirectories(target)
            rotate(target)
            Files.newBufferedWriter(
                target.resolve(FILE),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        } catch (_: Exception) {
            enabled = false
            return
        }

        out.use {
            while (true) {
                val line = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                @Suppress("SuspiciousEqualsCombination")
                if (line === POISON) break
                try {
                    out.write(line)
                    out.newLine()
                    // Batched while lines are arriving, flushed the moment they stop — so an idle
                    // log is always complete on disk, which is the state it is usually collected in.
                    if (queue.isEmpty()) {
                        val lost = dropped.getAndSet(0)
                        if (lost > 0) {
                            out.write("  ... $lost line(s) dropped: the log queue was full")
                            out.newLine()
                        }
                        out.flush()
                    }
                } catch (_: Exception) {
                    enabled = false
                    break
                }
            }
            runCatching { out.flush() }
        }
    }

    /**
     * One generation of history, and a bound on the size.
     *
     * A log people are asked to send has to be attachable. Rotating at [MAX_BYTES] keeps it so, and
     * keeping one previous file means the run *before* the one that went wrong is still there —
     * which is often the one that shows the setting being changed.
     */
    private fun rotate(target: Path) {
        val current = target.resolve(FILE)
        val size = runCatching { Files.size(current) }.getOrDefault(0L)
        if (size < MAX_BYTES) return
        runCatching {
            Files.move(current, target.resolve(PREVIOUS), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private const val FOLDER = "logs"
    private const val FILE = "omega.txt"
    private const val PREVIOUS = "omega.prev.txt"

    /** 2 MB is a few long sessions, and still small enough to attach to a message. */
    private const val MAX_BYTES = 2L * 1024 * 1024

    /**
     * Deep enough that no burst in this program can fill it, shallow enough to bound the memory a
     * switched-on log can hold. A stream failing repeatedly is the realistic burst, and it is dozens
     * of lines, not thousands.
     */
    private const val QUEUE_LINES = 4096

    /** Ends the writer thread after the backlog rather than interrupting it mid-file. */
    private val POISON = String("--stop--".toCharArray())
}
