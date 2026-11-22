package com.ukpro.core

/**
 * Minimal, dependency-free JSON codec (objects → LinkedHashMap, arrays → ArrayList,
 * integers → Long, other numbers → Double). Used by the shared core so it compiles
 * unchanged on Android, plain JVM and in tests.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonException("trailing data at ${p.i}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): MutableMap<String, Any?> =
        parse(text) as? MutableMap<String, Any?> ?: throw JsonException("not an object")

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> str(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else
                if (v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong().toString()) else sb.append(v.toString())
            is Float -> write(v.toDouble(), sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(k.toString(), sb); sb.append(':'); write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(x, sb) }
                sb.append(']')
            }
            is Array<*> -> write(v.toList(), sb)
            else -> str(v.toString(), sb)
        }
    }

    private fun str(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", c - '\u0000')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(): Any? {
            if (i >= s.length) throw JsonException("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(): MutableMap<String, Any?> {
            if (++depth > 64) throw JsonException("too deep")
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; depth--; return m }
            while (true) {
                ws()
                if (peek() != '"') throw JsonException("expected key at $i")
                val k = string()
                ws(); expect(':'); ws()
                m[k] = value()
                ws()
                when (next()) {
                    ',' -> {}
                    '}' -> { depth--; return m }
                    else -> throw JsonException("expected , or } at ${i - 1}")
                }
            }
        }

        private fun arr(): MutableList<Any?> {
            if (++depth > 64) throw JsonException("too deep")
            i++
            val l = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; depth--; return l }
            while (true) {
                ws(); l.add(value()); ws()
                when (next()) {
                    ',' -> {}
                    ']' -> { depth--; return l }
                    else -> throw JsonException("expected , or ] at ${i - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") throw JsonException("bad number at $start")
            return if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                t.toLongOrNull() ?: t.toDouble()
            } else t.toDouble()
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        private fun next(): Char = if (i < s.length) s[i++] else throw JsonException("unexpected end")
        private fun expect(c: Char) { if (next() != c) throw JsonException("expected '$c' at ${i - 1}") }
    }
}

class JsonException(msg: String) : RuntimeException(msg)

/** Typed accessors for payload maps. */
fun Map<String, Any?>.str(key: String, def: String = ""): String = this[key] as? String ?: def
fun Map<String, Any?>.long(key: String, def: Long = 0): Long = (this[key] as? Number)?.toLong() ?: def
fun Map<String, Any?>.bool(key: String, def: Boolean = false): Boolean = this[key] as? Boolean ?: def
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
