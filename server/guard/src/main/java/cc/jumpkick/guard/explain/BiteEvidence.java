// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import cc.jumpkick.guard.eval.Evaluators;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Lane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Must-bite across module lanes. A module lane sees one module, so a {@code forbid} whose owner and
 * sites live elsewhere is rightly clean there; whether the rule can fire at all is a question about
 * every lane together, answered here from the per-lane summaries once the module lanes have run: a
 * module-lane rule that no lane found evidence for is {@code no-bite}. Lanes that predate the bite
 * record, and rules no lane has evaluated, are left alone — silence is not a verdict either way.
 */
public final class BiteEvidence {

    private BiteEvidence() {}

    /** One rule with no bite evidence anywhere, and the note that says what to add. */
    public record Missing(Rule rule, String note) {}

    public static List<Missing> missing(Path root, RuleSet rules) throws IOException {
        Map<String, List<RuleSummaries.Summary>> byRule = new TreeMap<>();
        for (RuleSummaries.Summary s : RuleSummaries.read(root)) {
            byRule.computeIfAbsent(s.code(), k -> new ArrayList<>()).add(s);
        }
        List<Missing> out = new ArrayList<>();
        for (Rule rule : new LinkedHashMap<>(rules.rules()).values()) {
            if (Evaluators.laneOf(rule) != Lane.MODULE) continue;
            List<RuleSummaries.Summary> seen = byRule.getOrDefault(rule.id(), List.of());
            boolean anyRecord = false;
            boolean anyBite = false;
            boolean anyRed = false;
            Set<String> notes = new TreeSet<>();
            for (RuleSummaries.Summary s : seen) {
                if (s.bite() == null) continue;
                anyRecord = true;
                if (s.bite()) anyBite = true;
                if (!s.outcome().equals("clean") && !s.outcome().equals("violations")) anyRed = true;
                if (!s.note().isEmpty()) notes.add(s.note());
            }
            // A rule already red somewhere has its own diagnostic; piling no-bite on it says nothing new.
            if (!anyRecord || anyBite || anyRed) continue;
            String note = LaneRun.noBiteNote(rule);
            if (!notes.isEmpty() && notes.size() == 1 && seen.size() > 0) {
                // Every lane said the same thing — a type no module resolves is a typo, or a name
                // no module depends on.
                note = "in every module: " + notes.iterator().next() + " — " + note;
            }
            out.add(new Missing(rule, note));
        }
        return out;
    }
}
