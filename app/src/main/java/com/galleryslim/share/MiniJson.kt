package com.galleryslim.share

/**
 * A strict, small JSON reader for request bodies from the network (≤ 16 KiB): objects → Map, arrays → List,
 * strings, numbers (Long, or Double when fractional), booleans, null. Nesting is capped; anything malformed → null.
 */
object MiniJson {
    private const val MAX_DEPTH = 8

    fun parse(text: String): Any? = runCatching {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) error("trailing data")
        v
    }.getOrNull()

    private class Parser(val s: String) {
        var i = 0

        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH)
            ws()
            require(i < s.length)
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)); i += word.length; return v }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); require(i < s.length && s[i] == '"')
                val k = str()
                ws(); require(i < s.length && s[i] == ':'); i++
                m[k] = value(depth + 1)
                ws(); require(i < s.length)
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> error("object") }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                l += value(depth + 1)
                ws(); require(i < s.length)
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> error("array") }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                require(i < s.length)
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        require(i < s.length)
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { require(i + 4 <= s.length); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("escape")
                        }
                    }
                    c < ' ' -> error("control character")
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            require(t.isNotEmpty() && t.length <= 30)
            return t.toLongOrNull() ?: t.toDouble().also { require(!it.isNaN() && !it.isInfinite()) }
        }
    }
}
