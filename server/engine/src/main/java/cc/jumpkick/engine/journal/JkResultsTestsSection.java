// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static cc.jumpkick.engine.journal.JkResultsMarkdown.MAX_FAILED_TESTS;
import static cc.jumpkick.engine.journal.JkResultsMarkdown.MAX_PACKAGES;
import static cc.jumpkick.engine.journal.JkResultsMarkdown.MAX_STACK_LINES;
import static cc.jumpkick.engine.journal.JkResultsMarkdown.escCell;
import static cc.jumpkick.engine.journal.JkResultsMarkdown.fence;
import static cc.jumpkick.engine.journal.JkResultsMarkdown.fmtDuration;
import static cc.jumpkick.engine.journal.JkResultsMarkdown.runTestsFailed;

import cc.jumpkick.test.MarkdownTestReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The test part of {@code jk-results.md}: the {@code Tests:} count in the headline and the
 * {@code ## Tests} section with its per-package table and failed tests.
 */
final class JkResultsTestsSection {

    private JkResultsTestsSection() {}

    /** The headline's {@code Tests:} line; {@code true} when one was written. */
    static boolean appendCount(StringBuilder sb, BuildRecord r, List<MarkdownTestReport.ModuleRun> tests) {
        if (hasTestEntries(tests)) {
            TestRollup roll = rollup(tests);
            sb.append("Tests: ");
            boolean green = r.success() && roll.fail == 0 && !runTestsFailed(r);
            if (green) sb.append("**100%** pass · ");
            else if (roll.fail > 0) sb.append("**").append(roll.fail).append(" failed** · ");
            sb.append(roll.pass).append(" passed");
            if (roll.skip > 0) sb.append(", ").append(roll.skip).append(" skipped");
            sb.append(" (").append(roll.total).append(" total)");
            if (runTestsFailed(r) && roll.fail == 0) sb.append(" · **run-tests failed**");
            if (roll.ms > 0) sb.append(" · _took ").append(fmtDuration(roll.ms)).append('_');
            sb.append('\n');
            return true;
        }
        BuildRecord.Tests t = r.tests();
        if (t != null && t.total() > 0) {
            sb.append("Tests: ");
            if (t.failed() > 0) sb.append("**").append(t.failed()).append(" failed**, ");
            sb.append(t.succeeded()).append(" passed");
            if (t.skipped() > 0) sb.append(", ").append(t.skipped()).append(" skipped");
            sb.append(" (").append(t.total()).append(" total)\n");
            return true;
        }
        return false;
    }

    /** The {@code ## Tests} section; nothing when no suite recorded an entry. */
    static void append(StringBuilder sb, BuildRecord r, List<MarkdownTestReport.ModuleRun> tests) {
        if (!hasTestEntries(tests)) return;
        TestRollup roll = rollup(tests);
        sb.append("## Tests\n\n");
        appendSummary(sb, r, roll);
        boolean multi = tests.size() > 1;
        appendPackageTable(sb, tests, multi);
        if (roll.fail > 0) appendFailedTests(sb, tests, roll, multi);
        sb.append('\n');
    }

    private static void appendSummary(StringBuilder sb, BuildRecord r, TestRollup roll) {
        if (roll.fail == 0) {
            if (runTestsFailed(r)) {
                sb.append("Recorded tests passed · **run-tests failed** — see Failures");
            } else if (!r.success() || r.cancelled()) {
                sb.append("Recorded tests passed");
            } else {
                sb.append("**100%** pass rate · No failures for **")
                        .append(roll.total)
                        .append("** ")
                        .append(roll.total == 1 ? "test" : "tests");
            }
        } else {
            sb.append("**").append(passRate(roll)).append("%** pass rate · ");
            if (roll.total == 1) {
                sb.append("**1 failure** out of **1** test");
            } else {
                sb.append("**")
                        .append(roll.fail)
                        .append(roll.fail == 1 ? " failure**" : " failures**")
                        .append(" out of **")
                        .append(roll.total)
                        .append("** tests");
            }
        }
        if (roll.ms > 0) sb.append(" · _took ").append(fmtDuration(roll.ms)).append('_');
        sb.append("\n\n");
    }

    private static void appendPackageTable(StringBuilder sb, List<MarkdownTestReport.ModuleRun> tests, boolean multi) {
        sb.append(
                multi
                        ? "| Module | Package | Fail | Skip | Pass | Total |\n|---|---|---|---|---|---|\n"
                        : "| Package | Fail | Skip | Pass | Total |\n|---|---|---|---|---|\n");
        Map<String, long[]> byPkg = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (MarkdownTestReport.ModuleRun run : tests) {
            String mod = run.label().isBlank() ? run.scopeKey() : run.label();
            for (MarkdownTestReport.Entry e : run.entries()) {
                String pkg = packageOf(e.className());
                String key = multi ? mod + "\0" + pkg : pkg;
                long[] c = byPkg.computeIfAbsent(key, k -> {
                    order.add(k);
                    return new long[4];
                });
                c[3]++;
                if (e.isFail()) c[0]++;
                else if (e.isSkip()) c[1]++;
                else c[2]++;
            }
        }
        int shownPkg = 0;
        for (String key : order) {
            if (shownPkg >= MAX_PACKAGES) {
                sb.append("| … | +").append(order.size() - shownPkg).append(" more | | |");
                if (multi) sb.append(" |");
                sb.append(" |\n");
                break;
            }
            // `order` only ever holds keys computeIfAbsent put in byPkg, so a miss is a
            // broken invariant rather than a missing row — say so instead of rendering zeros.
            long[] c = Objects.requireNonNull(byPkg.get(key), key);
            if (multi) {
                int split = key.indexOf('\0');
                sb.append("| ")
                        .append(escCell(split < 0 ? key : key.substring(0, split)))
                        .append(" | ")
                        .append(escCell(split < 0 ? key : key.substring(split + 1)));
            } else {
                sb.append("| ").append(escCell(key));
            }
            sb.append(" | ")
                    .append(c[0])
                    .append(" | ")
                    .append(c[1])
                    .append(" | ")
                    .append(c[2])
                    .append(" | ")
                    .append(c[3])
                    .append(" |\n");
            shownPkg++;
        }
    }

    private static void appendFailedTests(
            StringBuilder sb, List<MarkdownTestReport.ModuleRun> tests, TestRollup roll, boolean multi) {
        sb.append("\n### Failed tests\n");
        int shown = 0;
        for (MarkdownTestReport.ModuleRun run : tests) {
            Map<String, List<MarkdownTestReport.Entry>> byClass = new LinkedHashMap<>();
            for (MarkdownTestReport.Entry e : run.entries()) {
                if (!e.isFail()) continue;
                byClass.computeIfAbsent(e.className(), k -> new ArrayList<>()).add(e);
            }
            if (byClass.isEmpty()) continue;
            for (var kv : byClass.entrySet()) {
                if (shown >= MAX_FAILED_TESTS) break;
                sb.append("#### ").append(kv.getKey());
                if (multi && !run.label().isBlank()) sb.append(" — ").append(run.label());
                sb.append('\n');
                for (MarkdownTestReport.Entry e : kv.getValue()) {
                    if (shown >= MAX_FAILED_TESTS) break;
                    sb.append("##### `")
                            .append(e.displayName() == null ? "" : e.displayName())
                            .append("`");
                    if (e.durationMs() > 0) {
                        sb.append(" — _took ")
                                .append(fmtDuration(e.durationMs()))
                                .append('_');
                    }
                    sb.append('\n');
                    String detail = e.failureStack() != null
                                    && !e.failureStack().isBlank()
                            ? e.failureStack().trim()
                            : (e.failureMessage() != null ? e.failureMessage().trim() : "");
                    if (!detail.isEmpty()) fence(sb, JkResultsStack.clip(detail, e.className(), MAX_STACK_LINES));
                    shown++;
                }
            }
        }
        if (roll.fail > shown) {
            sb.append("_+").append(roll.fail - shown).append(" more failed tests — see `details.jsonl`._\n");
        }
    }

    static boolean hasTestEntries(List<MarkdownTestReport.ModuleRun> tests) {
        if (tests == null || tests.isEmpty()) return false;
        for (MarkdownTestReport.ModuleRun r : tests) {
            if (r != null && r.entries() != null && !r.entries().isEmpty()) return true;
        }
        return false;
    }

    private static int passRate(TestRollup roll) {
        if (roll.total == 0 || roll.fail == 0) return 100;
        // One failure in a large suite still rounds to 100. Never report a clean rate then.
        int pct = (int) Math.round((double) (roll.total - roll.fail) / roll.total * 100);
        return Math.min(pct, 99);
    }

    private static TestRollup rollup(List<MarkdownTestReport.ModuleRun> tests) {
        long fail = 0, skip = 0, pass = 0, ms = 0;
        for (MarkdownTestReport.ModuleRun run : tests) {
            for (MarkdownTestReport.Entry e : run.entries()) {
                if (e.isFail()) fail++;
                else if (e.isSkip()) skip++;
                else pass++;
                ms += Math.max(0, e.durationMs());
            }
        }
        return new TestRollup(fail, skip, pass, fail + skip + pass, ms);
    }

    private static String packageOf(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "(unknown)";
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? fqcn : fqcn.substring(0, dot);
    }

    private record TestRollup(long fail, long skip, long pass, long total, long ms) {}
}
