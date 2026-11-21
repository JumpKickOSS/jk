// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Under a failed test in the agent report, the arguments and locals of the frames its exception
 * passed when it was thrown: the throwing frame first, then the project frames above it.
 */
final class JkResultsSnapshot {

    private JkResultsSnapshot() {}

    /** Every failed test's snapshot lines across {@code tests}, keyed by {@link MarkdownTestReport#testKey}. */
    static Map<String, List<String>> byTest(@Nullable List<MarkdownTestReport.ModuleRun> tests) {
        Map<String, List<String>> out = new HashMap<>();
        if (tests == null) return out;
        for (MarkdownTestReport.ModuleRun run : tests) if (run != null) out.putAll(run.snapshots());
        return out;
    }

    /** The block under a failed test: a heading line and one indented line per frame; empty for none. */
    static String block(@Nullable List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("  state when it threw:\n");
        for (String line : lines) sb.append("    ").append(line).append('\n');
        return sb.toString();
    }
}
