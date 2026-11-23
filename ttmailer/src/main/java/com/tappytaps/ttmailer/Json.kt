package com.tappytaps.ttmailer

/** A tiny JSON writer and flat-object reader (no dependency; org.json is not available in plain JVM tests). */
internal object Json {
    fun encode(map: Map<String, Any?>): String = map.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long -> v.toString()
        else -> str(v.toString())
    }

    private fun str(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append(String.format("\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /** The top-level string, boolean, number and null fields of a JSON object; nested values are skipped. */
    fun parseObject(text: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val p = Parser(text)
        p.ws()
        if (!p.eat('{')) return out
        p.ws()
        if (p.eat('}')) return out
        while (true) {
            p.ws()
            val k = p.string() ?: return out
            p.ws(); if (!p.eat(':')) return out; p.ws()
            out[k] = p.value()
            p.ws()
            if (p.eat(',')) continue
            return out
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun eat(c: Char): Boolean = if (i < s.length && s[i] == c) { i++; true } else false
        fun string(): String? {
            if (!eat('"')) return null
            val b = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) return null
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { if (i + 4 > s.length) return null; b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
            return null
        }
        fun value(): Any? {
            if (i >= s.length) return null
            return when (s[i]) {
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                '{', '[' -> { skipNested(); null }
                else -> { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; s.substring(st, i).toDoubleOrNull() }
            }
        }
        fun skipNested() {
            var depth = 0
            while (i < s.length) {
                when (s[i]) {
                    '"' -> { string(); continue }
                    '{', '[' -> depth++
                    '}', ']' -> { depth--; if (depth == 0) { i++; return } }
                }
                i++
            }
        }
    }
}
