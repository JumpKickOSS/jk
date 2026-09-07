// SPDX-License-Identifier: Apache-2.0

import java.io.File

/**
 * The per-module line-coverage ratchet behind `checkCoverageBand`: `coverage-baseline.txt` holds one line per module,
 * `<module-rel> <line coverage %>`; a module measured below its line by more than the band is a regression and fails; a
 * module above it by more than the band rewrites its line in the same run (an improvement is banked, never a failure);
 * a module the file has never seen is added at its measured value. Pure functions over strings, so the verdict is
 * testable without Gradle.
 */
object CoverageBand {
    /** Percentage points either side of the recorded value that count as "the same number". */
    const val BAND = 0.5

    /** One module's measured lines from a JaCoCo XML report (`<counter type="LINE" .../>` at the report root). */
    data class Measured(val module: String, val covered: Long, val missed: Long) {
        val total: Long
            get() = covered + missed

        val percent: Double
            get() = if (total == 0L) 0.0 else covered * 100.0 / total
    }

    enum class Kind {
        HOLD,
        IMPROVED,
        ADDED,
        REGRESSION,
    }

    data class Verdict(val module: String, val kind: Kind, val baseline: Double?, val measured: Double) {
        fun line(): String = "%s %.1f".format(module, measured)
    }

    /** The report-level LINE counter of a JaCoCo XML report; null when the report has none. */
    fun parseReport(module: String, xml: String): Measured? {
        // The report root's own counters come last, after every <package>; the last LINE counter is
        // the report total.
        val counters = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""").findAll(xml).toList()
        val last = counters.lastOrNull() ?: return null
        return Measured(module, last.groupValues[2].toLong(), last.groupValues[1].toLong())
    }

    /** `module percent` lines; `#` comments and blanks ignored. */
    fun parseBaseline(text: String): Map<String, Double> =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                require(parts.size == 2) { "coverage-baseline.txt: expected `<module> <percent>`, got `$line`" }
                parts[0] to parts[1].toDouble()
            }

    fun judge(baseline: Map<String, Double>, measured: List<Measured>): List<Verdict> =
        measured
            .filter { it.total > 0 }
            .sortedBy { it.module }
            .map { m ->
                val recorded = baseline[m.module]
                val kind =
                    when {
                        recorded == null -> Kind.ADDED
                        m.percent < recorded - BAND -> Kind.REGRESSION
                        m.percent > recorded + BAND -> Kind.IMPROVED
                        else -> Kind.HOLD
                    }
                Verdict(m.module, kind, recorded, m.percent)
            }

    /**
     * The baseline file after this run: every module keeps its recorded line except IMPROVED and ADDED, which take the
     * measured value; a REGRESSION keeps the recorded line (the bar does not move down). Modules the run did not
     * measure keep their lines. The header is preserved.
     */
    fun render(header: String, baseline: Map<String, Double>, verdicts: List<Verdict>): String {
        val next = baseline.toSortedMap()
        verdicts.forEach { v -> if (v.kind == Kind.IMPROVED || v.kind == Kind.ADDED) next[v.module] = v.measured }
        return buildString {
            append(header.trimEnd()).append('\n')
            next.forEach { (module, pct) -> append("%s %.1f\n".format(module, pct)) }
        }
    }

    /** The lines that fail the build, or empty. */
    fun regressions(verdicts: List<Verdict>): List<String> =
        verdicts
            .filter { it.kind == Kind.REGRESSION }
            .map {
                "%s: %.1f%% line coverage, baseline %.1f%% (band %.1f)"
                    .format(it.module, it.measured, it.baseline, BAND)
            }

    fun readBaseline(file: File): Pair<String, Map<String, Double>> {
        if (!file.isFile) return DEFAULT_HEADER to emptyMap()
        val text = file.readText()
        val header = text.lineSequence().takeWhile { it.startsWith("#") || it.isBlank() }.joinToString("\n")
        return (header.ifBlank { DEFAULT_HEADER }) to parseBaseline(text)
    }

    val DEFAULT_HEADER =
        """
        # coverage-baseline.txt — the ratchet behind `checkCoverageBand` (G91).
        #
        # One line per module with unit-tier tests:
        #
        #     <module-rel> <line coverage %>
        #
        # Measured by the nightly coverage run (`./gradlew checkCoverageBand -Pjk.coverage`). A module
        # more than 0.5 points BELOW its line fails the run: a regression, cover the change or say why
        # the line moves. A module more than 0.5 points ABOVE its line rewrites the line in the same
        # run — an improvement is banked, never a failure; commit the rewritten file. A module this file
        # has never seen is added at its measured value. No percentage target: the only direction the
        # number moves without a hand is up.
        """
            .trimIndent()
}
