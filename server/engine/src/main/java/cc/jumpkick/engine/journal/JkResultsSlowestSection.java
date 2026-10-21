// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static cc.jumpkick.engine.journal.JkResultsMarkdown.escCell;

import cc.jumpkick.run.SlowestTests;
import java.util.List;

/**
 * The {@code ## Slowest tests} section of {@code jk-results.md}: the run's slowest classes and
 * methods, each with its module and tier. Nothing when nothing reached {@link SlowestTests#SLOW_MS}.
 */
final class JkResultsSlowestSection {

    private JkResultsSlowestSection() {}

    static void append(StringBuilder sb, SlowestTests.Ranking ranking) {
        if (ranking.isEmpty()) return;
        sb.append("## Slowest tests\n\n")
                .append("Classes and methods of this run that took ")
                .append(SlowestTests.duration(SlowestTests.SLOW_MS))
                .append(" or more; a suite served from cache is not timed. ")
                .append("To speed one up: share the fixture, seam the wait, or move it to a tier.\n\n");
        table(sb, "Class", ranking.classes());
        table(sb, "Method", ranking.methods());
    }

    private static void table(StringBuilder sb, String what, List<SlowestTests.Row> rows) {
        if (rows.isEmpty()) return;
        sb.append("| ").append(what).append(" | Module | Tier | Took |\n|---|---|---|---|\n");
        for (SlowestTests.Row r : rows) {
            String name = r.method().isEmpty() ? r.className() : r.className() + "#" + r.method();
            sb.append("| `")
                    .append(escCell(name))
                    .append("` | ")
                    .append(escCell(r.module()))
                    .append(" | ")
                    .append(escCell(r.tier()))
                    .append(" | ")
                    .append(SlowestTests.duration(r.millis()))
                    .append(" |\n");
        }
        sb.append('\n');
    }
}
