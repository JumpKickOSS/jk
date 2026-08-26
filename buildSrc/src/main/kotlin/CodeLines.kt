// SPDX-License-Identifier: Apache-2.0

/**
 * Code-line count for `checkFileSizeCaps`. Comments, blank lines, and `package` / `import` lines do not count: the cap
 * is on behaviour in one file, not on the envelope format writes or the comments a type needs. String and text-block
 * contents do count — a fixture is data.
 */
object CodeLines {

    private const val LINE_COMMENT = "//"
    private const val BLOCK_OPEN = "/*"
    private const val BLOCK_CLOSE = "*/"
    private const val TEXT_DELIM = "\"\"\""

    /**
     * Lines of code in [src]. [extension] selects JS template-literal handling for `.js` / `.mjs`; every other
     * extension uses the Java/Kotlin comment and string lexeme set.
     */
    fun count(src: String, extension: String = "java"): Int {
        if (src.isEmpty()) return 0
        val js = extension.equals("js", ignoreCase = true) || extension.equals("mjs", ignoreCase = true)
        val commentsGone = blankNonCode(src, blankStrings = false, templateLiterals = js)
        val stringsGone = blankNonCode(src, blankStrings = true, templateLiterals = js)
        val visible = commentsGone.lineSequence().toList()
        val code = stringsGone.lineSequence().toList()
        val n = maxOf(visible.size, code.size)
        var lines = 0
        for (i in 0 until n) {
            val seen = (visible.getOrNull(i) ?: "").trim()
            if (seen.isEmpty()) continue
            val body = (code.getOrNull(i) ?: "").trim()
            if (body.isEmpty() || !isEnvelope(body)) lines++
        }
        return lines
    }

    /**
     * Blank comments — and, when [blankStrings], string/char/text-block literals — preserving length and newlines.
     * [templateLiterals] treats backtick strings as literals, so a block comment opener inside a JS template cannot eat
     * the rest of the file.
     */
    fun blankNonCode(src: String, blankStrings: Boolean = true, templateLiterals: Boolean = false): String {
        val out = StringBuilder(src.length)
        var i = 0
        var line = false
        var block = false
        var text = false
        var str = false
        var chr = false
        var tick = false
        fun lit(s: String) {
            out.append(if (blankStrings) " ".repeat(s.length) else s)
        }
        while (i < src.length) {
            val c = src[i]
            val two = if (i + 2 <= src.length) src.substring(i, i + 2) else ""
            val three = if (i + 3 <= src.length) src.substring(i, i + 3) else ""
            val escape = if (i + 2 <= src.length) src.substring(i, i + 2) else "$c "
            when {
                line -> {
                    if (c == '\n') {
                        line = false
                        out.append(c)
                    } else {
                        out.append(' ')
                    }
                }
                block -> {
                    if (two == BLOCK_CLOSE) {
                        block = false
                        out.append("  ")
                        i += 2
                        continue
                    }
                    out.append(if (c == '\n') '\n' else ' ')
                }
                text -> {
                    if (three == TEXT_DELIM) {
                        text = false
                        lit(three)
                        i += 3
                        continue
                    }
                    if (c == '\n') out.append('\n') else lit(c.toString())
                }
                str -> {
                    if (c == '\\') {
                        lit(escape)
                        i += 2
                        continue
                    }
                    if (c == '"') str = false
                    lit(c.toString())
                }
                chr -> {
                    if (c == '\\') {
                        lit(escape)
                        i += 2
                        continue
                    }
                    if (c == '\'') chr = false
                    lit(c.toString())
                }
                tick -> {
                    if (c == '\\') {
                        lit(escape)
                        i += 2
                        continue
                    }
                    if (c == '`') tick = false
                    lit(c.toString())
                }
                two == LINE_COMMENT -> {
                    line = true
                    out.append("  ")
                    i += 2
                    continue
                }
                two == BLOCK_OPEN -> {
                    block = true
                    out.append("  ")
                    i += 2
                    continue
                }
                three == TEXT_DELIM -> {
                    text = true
                    lit(three)
                    i += 3
                    continue
                }
                c == '"' -> {
                    str = true
                    lit("\"")
                    i += 1
                    continue
                }
                c == '\'' -> {
                    chr = true
                    lit("'")
                    i += 1
                    continue
                }
                templateLiterals && c == '`' -> {
                    tick = true
                    lit("`")
                    i += 1
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** First token is `package` or `import` — the file envelope, not the type. */
    private fun isEnvelope(trimmed: String): Boolean = trimmed.startsWith("package ") || trimmed.startsWith("import ")
}
