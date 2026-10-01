// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ## Warnings} section of {@code jk-results.md}. A memory wait and a heap retry are
 * listed in full, ahead of the cap, one wait per step.
 */
final class JkResultsWarnings {

    private JkResultsWarnings() {}

    static void append(StringBuilder sb, BuildRecord r) {
        List<BuildRecord.Diag> memory = new ArrayList<>();
        List<BuildRecord.Diag> warnings = new ArrayList<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!JkResultsMarkdown.isWarning(d) || JkResultsLockNotesSection.isLockNote(d)) continue;
            if (memoryEvent(d)) memory.add(d);
            else warnings.add(d);
        }
        memory = oncePerStep(memory);
        if (memory.isEmpty() && warnings.isEmpty()) return;
        sb.append("## Warnings\n\n");
        for (BuildRecord.Diag d : memory) line(sb, r, d);
        int shown = 0;
        for (BuildRecord.Diag d : warnings) {
            if (shown >= JkResultsMarkdown.MAX_WARNINGS) {
                sb.append("- _+").append(warnings.size() - shown).append(" more — see `details.jsonl`._\n");
                break;
            }
            line(sb, r, d);
            shown++;
        }
        sb.append('\n');
    }

    private static boolean memoryEvent(BuildRecord.Diag d) {
        String code = d.code();
        return "memory-wait".equals(code) || "heap-retry".equals(code) || "memory-over-lease".equals(code);
    }

    /** One memory-wait per module and step. Distinct heap-retry lines on a step all stay. */
    private static List<BuildRecord.Diag> oncePerStep(List<BuildRecord.Diag> memory) {
        List<BuildRecord.Diag> out = new ArrayList<>();
        List<String> waits = new ArrayList<>();
        for (BuildRecord.Diag d : memory) {
            if ("memory-wait".equals(d.code())) {
                String step = (d.dir() == null ? "" : d.dir()) + "\0" + (d.step() == null ? "" : d.step());
                if (waits.contains(step)) continue;
                waits.add(step);
            }
            out.add(d);
        }
        return out;
    }

    private static void line(StringBuilder sb, BuildRecord r, BuildRecord.Diag d) {
        sb.append("- ");
        String module = JkResultsMarkdown.warningModule(r, d);
        if (!module.isEmpty()) sb.append('`').append(module).append("` ");
        String step = JkResultsMarkdown.some(d.step());
        if (step != null) sb.append('`').append(step).append("` ");
        String loc = JkResultsMarkdown.locus(d, r.dir());
        if (!loc.isEmpty()) {
            sb.append(loc);
            if (JkResultsMarkdown.notBlank(d.message())) sb.append(" — ");
        }
        if (JkResultsMarkdown.notBlank(d.message())) {
            sb.append(JkResultsMarkdown.clipOneLine(
                    JkResultsMarkdown.firstLine(d.message()), JkResultsMarkdown.MAX_WARNING_LINE));
        }
        sb.append('\n');
    }
}
