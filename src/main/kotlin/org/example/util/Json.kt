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

/**
 * A very small JSON reader and writer.
 *
 * Hand-rolled rather than pulled in: the preset schema is a name, a list of strings and two flat
 * maps of primitives, and a library for that would be a second runtime dependency and another
 * licence line for a page of code. It is deliberately not a general-purpose parser — it covers what
 * this file format uses and rejects the rest with a clear message.
 *
 * Values map to `Map<String, Any?>`, `List<Any?>`, `String`, `Double`, `Boolean` and `null`.
 */
object Json {

    class MalformedException(message: String) : Exception(message)

    /** UTF-8 byte-order mark, as it arrives once the bytes have been decoded. See [parse]. */
    private const val BOM = "\uFEFF"

    // ------------------------------------------------------------------ write

    fun write(value: Any?, indent: String = ""): String = buildString { writeTo(this, value, indent) }

    private fun writeTo(out: StringBuilder, value: Any?, indent: String) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            // Double.toString is locale-independent; String.format is not, and a machine with a
            // comma decimal separator would write files it could not read back.
            is Double -> out.append(java.lang.Double.toString(value))
            is Float -> out.append(java.lang.Double.toString(value.toDouble()))
            is Int -> out.append(value.toString())
            is String -> writeString(out, value)
            is Map<*, *> -> writeObject(out, value, indent)
            is List<*> -> writeArray(out, value, indent)
            else -> throw IllegalArgumentException("cannot write ${value.javaClass.name} as JSON")
        }
    }

    private fun writeObject(out: StringBuilder, map: Map<*, *>, indent: String) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        val inner = "$indent  "
        out.append("{\n")
        map.entries.forEachIndexed { index, (key, value) ->
            out.append(inner)
            writeString(out, key.toString())
            out.append(": ")
            writeTo(out, value, inner)
            if (index < map.size - 1) out.append(',')
            out.append('\n')
        }
        out.append(indent).append('}')
    }

    private fun writeArray(out: StringBuilder, list: List<*>, indent: String) {
        if (list.isEmpty()) {
            out.append("[]")
            return
        }
        val inner = "$indent  "
        out.append("[\n")
        list.forEachIndexed { index, value ->
            out.append(inner)
            writeTo(out, value, inner)
            if (index < list.size - 1) out.append(',')
            out.append('\n')
        }
        out.append(indent).append(']')
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------------ read

    fun parse(text: String): Any? {
        // A leading byte-order mark is skipped rather than rejected. Nothing here writes one, but
        // these files are hand-editable and both Notepad and PowerShell's `Set-Content -Encoding
        // utf8` add one — and JSON's own grammar has no room for it, so the parse fails at offset 0
        // with a message about an unexpected character that is invisible in every editor that
        // produced it. Losing a preset, or a whole session, to a byte nobody can see is not a
        // failure worth defending.
        val reader = Reader(text.removePrefix(BOM))
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("trailing content")
        return value
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun atEnd() = at >= text.length

        fun fail(what: String): Nothing = throw MalformedException("$what at offset $at")

        fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end of input")

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c' but found '${peek()}'")
            at++
        }

        fun readValue(): Any? {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                at++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return map
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                at++
                return list
            }
            while (true) {
                list += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return list
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (at >= text.length) fail("unterminated string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (at >= text.length) fail("unterminated escape")
                        when (val escape = text[at++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'u' -> {
                                if (at + 4 > text.length) fail("truncated \\u escape")
                                out.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> fail("unknown escape '\\$escape'")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Double {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            return text.substring(start, at).toDoubleOrNull() ?: fail("malformed number")
        }

        private fun readBoolean(): Boolean = when {
            text.startsWith("true", at) -> {
                at += 4
                true
            }
            text.startsWith("false", at) -> {
                at += 5
                false
            }
            else -> fail("malformed boolean")
        }

        private fun readNull(): Any? {
            if (!text.startsWith("null", at)) fail("malformed null")
            at += 4
            return null
        }
    }
}
