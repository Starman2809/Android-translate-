package com.kostysetinin.gametranslate.logic

/**
 * Parses a JSON array of strings. Gemini sometimes wraps the array in prose or a
 * markdown fence, so the scanner starts at the first '[' and stops at the matching ']'.
 */
object JsonStrings {
    fun parseArray(raw: String): List<String>? {
        val start = raw.indexOf('[')
        if (start < 0) return null
        return try {
            readArray(raw, start).value
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private class Parsed(val value: List<String>, val next: Int)

    private fun readArray(source: String, open: Int): Parsed {
        if (source[open] != '[') throw IllegalArgumentException("expected array")
        val items = mutableListOf<String>()
        var index = open + 1
        while (index < source.length) {
            index = skipSpace(source, index)
            if (index >= source.length) break
            if (source[index] == ']') return Parsed(items, index + 1)
            if (source[index] != '"') throw IllegalArgumentException("expected string")
            val parsed = readString(source, index)
            items += parsed.value
            index = skipSpace(source, parsed.next)
            if (index >= source.length) break
            when (source[index]) {
                ',' -> index++
                ']' -> return Parsed(items, index + 1)
                else -> throw IllegalArgumentException("expected comma")
            }
        }
        throw IllegalArgumentException("unterminated array")
    }

    private class StringParsed(val value: String, val next: Int)

    private fun readString(source: String, openQuote: Int): StringParsed {
        val out = StringBuilder()
        var index = openQuote + 1
        while (index < source.length) {
            val char = source[index]
            if (char == '"') return StringParsed(out.toString(), index + 1)
            if (char == '\\') {
                if (index + 1 >= source.length) throw IllegalArgumentException("bad escape")
                when (val escaped = source[index + 1]) {
                    '"', '\\', '/' -> {
                        out.append(escaped)
                        index += 2
                    }
                    'b' -> {
                        out.append('\b')
                        index += 2
                    }
                    'f' -> {
                        out.append('\u000C')
                        index += 2
                    }
                    'n' -> {
                        out.append('\n')
                        index += 2
                    }
                    'r' -> {
                        out.append('\r')
                        index += 2
                    }
                    't' -> {
                        out.append('\t')
                        index += 2
                    }
                    'u' -> {
                        if (index + 5 >= source.length) throw IllegalArgumentException("bad unicode")
                        val hex = source.substring(index + 2, index + 6)
                        out.append(hex.toInt(16).toChar())
                        index += 6
                    }
                    else -> throw IllegalArgumentException("bad escape")
                }
            } else {
                out.append(char)
                index++
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun skipSpace(source: String, start: Int): Int {
        var index = start
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }
}
