// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The coverage each module's {@code run-tests} step measured, published into the {@link RunResults}
 * sink of the request that ran it, keyed by module path; the journal drains it at request-finish
 * into the run's record and {@code jk-results.md}. Companion to {@link MarkdownTestReport}, which
 * carries the per-test entries the same way.
 */
public final class CoverageResults {

    /**
     * One module's whole-report counters and where its HTML report starts.
     *
     * @param dir the module directory, absolute
     * @param label the module coordinate ({@code group:name})
     * @param html the report's {@code index.html}, absolute
     */
    public record Module(
            String dir,
            String label,
            long linesCovered,
            long linesMissed,
            long branchesCovered,
            long branchesMissed,
            String html) {}

    private CoverageResults() {}

    /**
     * Covered as a percentage of covered + missed, one decimal, as every surface spells it:
     * {@code 85.2%}; {@code 100.0%} of nothing.
     */
    public static String pct(long covered, long missed) {
        long total = covered + missed;
        double percent = total == 0 ? 100.0 : covered * 100.0 / total;
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /**
     * Record {@code moduleDir}'s coverage in the request's sink; a second publish for the same module
     * replaces the first. Dropped when no request is open on this thread.
     */
    public static void publish(Path moduleDir, Module module) {
        RunResults sink = RunResults.ambient();
        if (sink == null) return;
        sink.publishCoverage(moduleDir.toAbsolutePath().normalize().toString(), module);
    }
}
