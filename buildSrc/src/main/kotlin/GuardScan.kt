// SPDX-License-Identifier: Apache-2.0

/**
 * Text plumbing shared by structural guards: comments stripped, whitespace squashed between tokens, and ratchet
 * verdicts against a per-module allowlist.
 */
object GuardScan {

    private val fqcnPattern = Regex("""(?<![\w.$])(?:[a-z][a-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*""")

    /** Comments and imports gone, whitespace squashed. String literals stay intact. */
    fun guardText(src: String): String =
        blankNonCode(src, blankStrings = false)
            .lineSequence()
            .filterNot {
                val s = it.trimStart()
                s.startsWith("import ") || s.startsWith("package ")
            }
            .joinToString("\n")
            .let(::squashBetweenLiterals)

    fun blankNonCode(src: String, blankStrings: Boolean = true): String = CodeLines.blankNonCode(src, blankStrings)

    /** Drop whitespace between tokens, keeping every string / char / text-block literal verbatim. */
    fun squashBetweenLiterals(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' || c == '\'' -> {
                    val close = if (c == '"' && src.startsWith("\"\"\"", i)) "\"\"\"" else c.toString()
                    out.append(close)
                    i += close.length
                    while (i < src.length) {
                        if (src[i] == '\\') {
                            out.append(src, i, minOf(i + 2, src.length))
                            i += 2
                        } else if (src.startsWith(close, i)) {
                            out.append(close)
                            i += close.length
                            break
                        } else {
                            out.append(src[i])
                            i++
                        }
                    }
                }
                c.isWhitespace() -> i++
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    fun countIn(code: String, pattern: Regex): Int = pattern.findAll(code).count()

    /** Package-qualified references in a Java source, excluding its own import/package lines. */
    fun countFqcns(src: String): Int =
        blankNonCode(src).lineSequence().sumOf { raw ->
            val s = raw.trimStart()
            if (s.startsWith("import ") || s.startsWith("package ")) 0 else fqcnPattern.findAll(raw).count()
        }

    /**
     * Judge this module's per-file hit counts against an allowlist: (grew, unlisted, loose).
     *
     * Only entries under [here], this module's repo-relative directory, can be judged clean.
     */
    fun ratchetVerdict(
        hits: Map<String, Int>,
        allowed: Map<String, Int>,
        here: String,
    ): Triple<List<String>, List<String>, List<String>> {
        val grew = mutableListOf<String>()
        val unlisted = mutableListOf<String>()
        val loose = mutableListOf<String>()
        hits.toSortedMap().forEach { (rel, n) ->
            val at = allowed[rel]
            when {
                at == null -> unlisted.add("  %5d  %s".format(n, rel))
                n > at -> grew.add("  $rel: $n sites, allowed $at (+${n - at})")
                n < at -> loose.add("  %5d  %s   (was %d)".format(n, rel, at))
            }
        }
        allowed.toSortedMap().forEach { (rel, at) ->
            if (rel.startsWith(here) && rel !in hits) loose.add("  (clean) $rel   (was $at)")
        }
        return Triple(grew, unlisted, loose)
    }
}
