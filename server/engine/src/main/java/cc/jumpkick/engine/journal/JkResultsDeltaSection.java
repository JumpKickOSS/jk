// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static cc.jumpkick.engine.journal.JkResultsMarkdown.fmtDuration;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code ## Since the previous run} part of {@code jk-results.md}: the run before from the same
 * origin and its wall, then counts first and short lists after — files changed, diagnostics that
 * appeared or went away, tests that flipped. Nothing is written for a run with no {@link JobDelta}
 * (the first of its origin, or history off). Lists are the delta's own {@link JobDelta.Rows}, so the
 * section stays a screen high however large the change.
 */
final class JkResultsDeltaSection {

    private JkResultsDeltaSection() {}

    static final String HEADING = "## Since the previous run";

    static void append(StringBuilder sb, BuildRecord r) {
        JobDelta d = r.delta();
        if (d == null) return;
        sb.append(HEADING).append("\n\n");
        sb.append("_vs #")
                .append(d.previousBuildNumber())
                .append(" (")
                .append(d.previousSuccess() ? "ok" : "failed")
                .append(", ")
                .append(fmtDuration(d.previousMillis()))
                .append(") · this run ")
                .append(fmtDuration(r.millis()))
                .append(" (")
                .append(signed(r.millis() - d.previousMillis()))
                .append(")_\n\n");
        if (d.quiet()) {
            sb.append("- Nothing changed: same files, diagnostics and tests.\n\n");
            return;
        }
        appendFiles(sb, d.files());
        sb.append("- Diagnostics: **")
                .append(d.appeared().count())
                .append("** appeared, **")
                .append(d.gone().count())
                .append("** gone\n");
        rows(sb, "appeared", d.appeared(), false);
        rows(sb, "gone", d.gone(), false);
        if (d.comparedTests()) {
            sb.append("- Tests: **")
                    .append(count(d.fixed()))
                    .append("** fixed, **")
                    .append(count(d.broke()))
                    .append("** broke, **")
                    .append(count(d.added()))
                    .append("** new, **")
                    .append(count(d.dropped()))
                    .append("** gone\n");
            rows(sb, "fixed", d.fixed(), true);
            rows(sb, "broke", d.broke(), true);
            rows(sb, "new", d.added(), true);
            rows(sb, "gone", d.dropped(), true);
        }
        sb.append('\n');
    }

    private static void appendFiles(StringBuilder sb, JobDelta.@Nullable Rows files) {
        if (files == null) return;
        sb.append("- Files changed: **").append(files.count()).append("**");
        List<String> shown = files.shown();
        if (!shown.isEmpty()) {
            sb.append(" — ");
            for (int i = 0; i < shown.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('`').append(shown.get(i)).append('`');
            }
            if (files.more() > 0) sb.append(", +").append(files.more()).append(" more");
        }
        sb.append('\n');
    }

    /** One indented bullet per shown row; test rows are code-spanned, diagnostic rows verbatim. */
    private static void rows(StringBuilder sb, String label, JobDelta.@Nullable Rows rows, boolean code) {
        if (rows == null || rows.count() == 0) return;
        for (String row : rows.shown()) {
            sb.append("  - ").append(label).append(": ");
            if (code) sb.append('`').append(row).append('`');
            else sb.append(row);
            sb.append('\n');
        }
        if (rows.more() > 0)
            sb.append("  - ").append(label).append(": +").append(rows.more()).append(" more\n");
    }

    private static int count(JobDelta.@Nullable Rows rows) {
        return rows == null ? 0 : rows.count();
    }

    /** {@code −2.3s} / {@code +410ms} / {@code ±0}. */
    static String signed(long deltaMillis) {
        if (deltaMillis == 0) return "±0";
        return (deltaMillis < 0 ? "−" : "+") + fmtDuration(Math.abs(deltaMillis));
    }
}
