package com.eevdf.capabilities.multidevicesync.logic

import java.util.Base64

/**
 * A deliberately tiny JSON reader/writer for the sync journal.
 *
 * WHY NOT org.json
 * ----------------
 * The whole merge engine (everything in this package that does not import
 * `android.*`) is plain Kotlin so it can be unit-tested on the JVM against a
 * real SQLite file. `org.json` on the JVM classpath is Android's stub jar
 * (every method throws "Stub!"), so depending on it would make the most
 * safety-critical code in the app the one part that cannot be tested.
 *
 * VALUE MODEL
 * -----------
 * Exactly the five things SQLite can store, and nothing else:
 *
 *   null       -> null
 *   Long       -> 42          (no '.', 'e' or 'E' in the token)
 *   Double     -> 42.0        (always carries '.', 'e' or 'E')
 *   String     -> "text"
 *   ByteArray  -> {"b":"<base64>"}
 *
 * Non-finite doubles, which JSON cannot spell, travel as {"f":"NaN"} etc.
 * Keeping INTEGER and REAL distinguishable in the text matters: a REAL
 * column holding 5.0 must not come back as the INTEGER 5 on another device.
 *
 * Containers decode to [LinkedHashMap] (objects, insertion-ordered) and
 * [ArrayList] (arrays).
 */
internal object MiniJson {

    // ── Writing ───────────────────────────────────────────────────────────────

    fun write(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Int -> out.append(value.toLong())
            is Long -> out.append(value)
            is Double -> writeDouble(value, out)
            is Float -> writeDouble(value.toDouble(), out)
            is String -> writeString(value, out)
            is ByteArray -> {
                out.append("{\"b\":\"").append(Base64.getEncoder().encodeToString(value)).append("\"}")
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    write(v, out)
                }
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeDouble(d: Double, out: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) {
            out.append("{\"f\":\"").append(d.toString()).append("\"}")
            return
        }
        // Double.toString always yields a '.' or an 'E' ("1.0", "1.0E10"), so
        // the reader can tell this token apart from an integer.
        out.append(d.toString())
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ' || ch == '\u2028' || ch == '\u2029') {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    /** Parses one JSON document. Throws [IllegalArgumentException] on any malformed input. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        require(p.pos == text.length) { "trailing characters at ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun readValue(): Any? {
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    require(c == '-' || c in '0'..'9') { "unexpected '$c' at $pos" }
                    readNumber()
                }
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return value
        }

        private fun readNumber(): Any {
            val start = pos
            var real = false
            if (s[pos] == '-') pos++
            while (pos < s.length) {
                val c = s[pos]
                if (c in '0'..'9') { pos++ }
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { real = true; pos++ }
                else break
            }
            val token = s.substring(start, pos)
            return if (real) token.toDouble() else (token.toLongOrNull() ?: token.toDouble())
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "bad escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad unicode escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(): Any {
            pos++
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                require(pos < s.length) { "unterminated array" }
                val c = s[pos++]
                if (c == ']') return list
                require(c == ',') { "expected ',' at ${pos - 1}" }
            }
        }

        private fun readObject(): Any? {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                require(pos < s.length && s[pos] == '"') { "expected key at $pos" }
                val key = readString()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "expected ':' at $pos" }
                pos++
                skipWs()
                map[key] = readValue()
                skipWs()
                require(pos < s.length) { "unterminated object" }
                val c = s[pos++]
                if (c == '}') break
                require(c == ',') { "expected ',' at ${pos - 1}" }
            }
            return map
        }
    }

    // ── SQLite cell values <-> JSON-ready values ──────────────────────────────

    /**
     * Turns a decoded JSON value back into a SQLite cell value, undoing the
     * `{"b":…}` / `{"f":…}` wrappers [write] produces. Anything unexpected
     * (a nested object that is neither wrapper, an array) is preserved as its
     * JSON text so no information is silently dropped.
     */
    fun toCell(decoded: Any?): Any? = when (decoded) {
        null, is Long, is Double, is String -> decoded
        is Boolean -> if (decoded) 1L else 0L
        is Map<*, *> -> {
            val b = decoded["b"]
            val f = decoded["f"]
            when {
                decoded.size == 1 && b is String -> Base64.getDecoder().decode(b)
                decoded.size == 1 && f is String -> f.toDouble()
                else -> write(decoded)
            }
        }
        else -> write(decoded)
    }
}
