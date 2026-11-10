// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What the tests of a run printed, as the agent report says it: a hint under a failure, or the lines a {@code -v} run kept. */
final class JkResultsPrinted {

    /** Lines of test output the report quotes across every class a {@code -v} run kept. */
    static final int MAX_PRINTED = 200;

    private JkResultsPrinted() {}

    /**
     * What each class printed, for the classes whose lines a {@code -v} run kept: an {@code OUT}
     * line, the last lines indented, and where the earlier ones are. Capped at {@link #MAX_PRINTED}
     * lines.
     */
    static void append(StringBuilder sb, @Nullable List<MarkdownTestReport.ModuleRun> tests, String projectDir) {
        if (tests == null) return;
        int budget = MAX_PRINTED;
        int omitted = 0;
        String firstOmitted = "";
        for (MarkdownTestReport.ModuleRun run : tests) {
            if (run == null) continue;
            for (var kv : run.printed().entrySet()) {
                MarkdownTestReport.Printed p = kv.getValue();
                if (p.tail().isEmpty()) continue;
                String report = JkResultsAgent.one(p.report(), projectDir);
                if (budget <= 0) {
                    if (omitted++ == 0) firstOmitted = report;
                    continue;
                }
                List<String> tail = p.tail();
                if (tail.size() > budget) tail = tail.subList(tail.size() - budget, tail.size());
                budget -= tail.size();
                sb.append("OUT ")
                        .append(kv.getKey())
                        .append(" printed ")
                        .append(lines(p.lines()))
                        .append('\n');
                int earlier = p.lines() - tail.size();
                if (earlier > 0) {
                    sb.append("  +").append(lines(earlier)).append(" before");
                    if (!report.isEmpty()) sb.append(": ").append(report);
                    sb.append('\n');
                }
                for (String line : tail)
                    sb.append("  ").append(line.replace("\r", "")).append('\n');
            }
        }
        if (omitted > 0) {
            sb.append('+').append(omitted).append(omitted == 1 ? " more class printed" : " more classes printed");
            if (!firstOmitted.isEmpty()) sb.append(": ").append(firstOmitted);
            sb.append('\n');
        }
    }

    /** {@code 1 line}, {@code N lines}. */
    private static String lines(int n) {
        return n + (n == 1 ? " line" : " lines");
    }

    /** What each class printed, across {@code tests}. */
    static Map<String, MarkdownTestReport.Printed> byClass(@Nullable List<MarkdownTestReport.ModuleRun> tests) {
        Map<String, MarkdownTestReport.Printed> out = new HashMap<>();
        if (tests == null) return out;
        for (MarkdownTestReport.ModuleRun run : tests) if (run != null) out.putAll(run.printed());
        return out;
    }

    /**
     * Under a failed test whose class printed and whose lines the run did not keep: how many, and
     * the flag that shows them. Empty otherwise.
     */
    static String hint(MarkdownTestReport.@Nullable Printed p) {
        if (p == null || p.lines() == 0 || !p.tail().isEmpty()) return "";
        return "  printed " + lines(p.lines()) + "; jk test -v shows them\n";
    }
}
