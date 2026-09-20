// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static cc.jumpkick.engine.journal.JkResultsMarkdown.escCell;

import cc.jumpkick.test.CoverageResults;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The coverage part of {@code jk-results.md}: the {@code Coverage:} line in the headline and the
 * {@code ## Coverage} table — per module, covered lines and branches as a percentage with the
 * counts behind it, and the change against {@code previous}, the last run of the same project that
 * measured coverage, when the journal has one. Nothing is written for a run that measured none.
 */
public final class JkResultsCoverageSection {

    private JkResultsCoverageSection() {}

    /** The headline's {@code Coverage:} line over every module; {@code true} when one was written. */
    static boolean appendCount(StringBuilder sb, BuildRecord r) {
        if (r.coverage().isEmpty()) return false;
        Totals t = Totals.of(r.coverage());
        sb.append("Coverage: **")
                .append(pct(t.linesCovered, t.linesMissed))
                .append("** lines · ")
                .append(pct(t.branchesCovered, t.branchesMissed))
                .append(" branches");
        if (r.coverage().size() > 1)
            sb.append(" · ").append(r.coverage().size()).append(" modules");
        sb.append('\n');
        return true;
    }

    /** The {@code ## Files} pointer at the HTML report(s). */
    static void appendFiles(StringBuilder sb, BuildRecord r) {
        if (r.coverage().isEmpty()) return;
        sb.append("- Coverage HTML: `").append(htmlPointer(r)).append("`\n");
    }

    /** The {@code ## Coverage} section; nothing when the run measured no coverage. */
    static void append(StringBuilder sb, BuildRecord r, @Nullable BuildRecord previous) {
        List<BuildRecord.Coverage> rows = r.coverage();
        if (rows.isEmpty()) return;
        List<BuildRecord.Coverage> before = previous == null ? List.of() : previous.coverage();
        boolean delta = !before.isEmpty();
        long previousRun = previous == null ? 0 : previous.buildNumber();
        sb.append("## Coverage\n\n");
        if (delta) {
            sb.append("_Δ vs run #").append(previousRun).append("_\n\n");
        }
        sb.append(
                delta
                        ? "| Module | Lines | Δ | Branches | Δ |\n|---|---|---|---|---|\n"
                        : "| Module | Lines | Branches |\n|---|---|---|\n");
        for (BuildRecord.Coverage c : rows) {
            row(
                    sb,
                    c.label(),
                    c.linesCovered(),
                    c.linesMissed(),
                    c.branchesCovered(),
                    c.branchesMissed(),
                    delta,
                    find(before, c.dir()));
        }
        if (rows.size() > 1) {
            Totals t = Totals.of(rows);
            BuildRecord.Coverage was = delta ? Totals.of(before).asCoverage() : null;
            row(sb, "**all**", t.linesCovered, t.linesMissed, t.branchesCovered, t.branchesMissed, delta, was);
        }
        sb.append("\nHTML: `")
                .append(htmlPointer(r))
                .append("` · floor: a `coverage.line` guard, judged by `jk guard`\n\n");
    }

    private static void row(
            StringBuilder sb,
            String label,
            long lc,
            long lm,
            long bc,
            long bm,
            boolean delta,
            BuildRecord.@Nullable Coverage before) {
        sb.append("| ")
                .append(label.startsWith("**") ? label : escCell(label))
                .append(" | ")
                .append(cell(lc, lm));
        if (delta) sb.append(" | ").append(before == null ? "new" : signed(percent(lc, lm) - before.linePercent()));
        sb.append(" | ").append(cell(bc, bm));
        if (delta) sb.append(" | ").append(before == null ? "new" : signed(percent(bc, bm) - before.branchPercent()));
        sb.append(" |\n");
    }

    /** {@code 85.2% (1204/1413)}. */
    static String cell(long covered, long missed) {
        return pct(covered, missed) + " (" + covered + "/" + (covered + missed) + ")";
    }

    /** {@link CoverageResults#pct}: the one spelling of a percentage across every surface. */
    static String pct(long covered, long missed) {
        return CoverageResults.pct(covered, missed);
    }

    /** A percentage-point change with its sign: {@code +1.3}, {@code −0.5}, {@code ±0.0}. */
    static String signed(double points) {
        double rounded = Math.round(points * 10) / 10.0;
        if (rounded == 0) return "±0.0";
        return (rounded > 0 ? "+" : "−") + String.format(Locale.ROOT, "%.1f", Math.abs(rounded));
    }

    private static double percent(long covered, long missed) {
        return BuildRecord.Coverage.percent(covered, missed);
    }

    private static BuildRecord.@Nullable Coverage find(List<BuildRecord.Coverage> rows, String dir) {
        for (BuildRecord.Coverage c : rows) if (c.dir().equals(dir)) return c;
        return null;
    }

    /**
     * Where the HTML starts, relative to the invocation root: the one module's page for a single
     * module, the roll-up for a workspace.
     */
    static String htmlPointer(BuildRecord r) {
        if (r.coverage().size() == 1)
            return relative(r.dir(), r.coverage().get(0).html());
        return relative(r.dir(), CoverageRollup.pageFor(r).toString());
    }

    /** {@code path} relative to {@code root} with forward slashes on every host; the markdown is read anywhere. */
    static String relative(String root, String path) {
        try {
            Path base = Path.of(root).toAbsolutePath().normalize();
            Path p = Path.of(path).toAbsolutePath().normalize();
            return p.startsWith(base) ? base.relativize(p).toString().replace('\\', '/') : path;
        } catch (RuntimeException e) {
            return path;
        }
    }

    private record Totals(long linesCovered, long linesMissed, long branchesCovered, long branchesMissed) {
        static Totals of(List<BuildRecord.Coverage> rows) {
            long lc = 0, lm = 0, bc = 0, bm = 0;
            for (BuildRecord.Coverage c : rows) {
                lc += c.linesCovered();
                lm += c.linesMissed();
                bc += c.branchesCovered();
                bm += c.branchesMissed();
            }
            return new Totals(lc, lm, bc, bm);
        }

        /** The totals as one row, for the {@code all} delta. */
        BuildRecord.Coverage asCoverage() {
            return new BuildRecord.Coverage("", "", linesCovered, linesMissed, branchesCovered, branchesMissed, "");
        }
    }
}
