// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.Map;
import java.util.TreeMap;

/**
 * The guards a run could not check on this machine, as the {@code ## Guards} verdict names them: a
 * run that skipped a rule never reads as plainly clean.
 */
final class JkResultsGuardSkips {

    private JkResultsGuardSkips() {}

    /**
     * Rule id to the note it gave, for each guard notice whose first line is {@code GUARD <id>
     * skipped} (or {@code not-here}); a rule skipped in several lanes is named once.
     */
    static Map<String, String> of(BuildRecord r) {
        Map<String, String> out = new TreeMap<>();
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!JkResultsMarkdown.isWarning(d) || !JkResultsMarkdown.isGuard(d)) continue;
            String first = JkResultsMarkdown.firstLine(d.message()).strip();
            String head = "GUARD " + d.code() + "  ";
            if (!first.equals(head + "skipped") && !first.equals(head + "not-here")) continue;
            out.putIfAbsent(d.code(), JkResultsMarkdown.guardField(d.message(), "Observed:"));
        }
        return out;
    }

    /** Ends the verdict line with the skipped guards by id, then lists each with its note. */
    static void append(StringBuilder sb, Map<String, String> skipped) {
        if (!skipped.isEmpty())
            sb.append(" · ").append(skipped.size()).append(" skipped: ").append(String.join(", ", skipped.keySet()));
        sb.append("\n\n");
        if (skipped.isEmpty()) return;
        for (var e : skipped.entrySet()) {
            sb.append("- `").append(e.getKey()).append("` skipped");
            if (!e.getValue().isEmpty()) sb.append(": ").append(e.getValue());
            sb.append('\n');
        }
        sb.append('\n');
    }
}
