// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.run.TaskNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code ## Deliverables} section of {@code jk-results.md}: every step that leaves an artifact,
 * with the detail its last label gave (the jar written, a server's start command).
 */
final class JkResultsDeliverablesSection {

    private JkResultsDeliverablesSection() {}

    static void append(StringBuilder sb, BuildRecord r) {
        List<JkResultsMarkdown.Row> rows = new ArrayList<>();
        for (BuildRecord.Task t : r.steps()) {
            if (isDeliverable(t.name())) rows.add(new JkResultsMarkdown.Row("", t));
        }
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) {
                if (isDeliverable(t.name())) rows.add(new JkResultsMarkdown.Row(JkResultsMarkdown.moduleLabel(m), t));
            }
        }
        if (rows.isEmpty()) return;
        sb.append("## Deliverables\n\n");
        sb.append("| Module | Task | Status | Time | Detail |\n|---|---|---|---|---|\n");
        for (JkResultsMarkdown.Row row : rows) {
            sb.append("| ")
                    .append(JkResultsMarkdown.escCell(row.module()))
                    .append(" | `")
                    .append(JkResultsMarkdown.escCell(row.task().name()))
                    .append("` | ")
                    .append(JkResultsMarkdown.status(row.task().status()))
                    .append(" | ")
                    .append(JkResultsMarkdown.fmtDuration(row.task().millis()))
                    .append(" | ")
                    .append(JkResultsMarkdown.escCell(row.task().detail()))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    static boolean isDeliverable(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return "install".equals(n)
                || "publish".equals(n)
                || TaskNames.NATIVE_IMAGE.equals(n)
                || TaskNames.WRITE_IMAGE.equals(n)
                || TaskNames.PACKAGE_JAR.equals(n)
                || TaskNames.NODE_PACKAGE.equals(n)
                || TaskNames.PACKAGE_ASSEMBLY.equals(n)
                || TaskNames.PACKAGE_MINIFIED.equals(n)
                || TaskNames.CACHE_INSTALL.equals(n)
                || n.contains(TaskNames.NATIVE_IMAGE)
                || (n.endsWith("-image") && n.contains("write"));
    }
}
