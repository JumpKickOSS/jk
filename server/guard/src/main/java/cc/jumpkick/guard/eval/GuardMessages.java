// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.rules.Rule;
import java.util.List;

/**
 * The failure text an agent reads. First line is the title (what {@code jk_diagnostics} shows as the
 * message); the rest is the detail. At most {@value #SITES} sites per rule; the full list is in the
 * run's {@code jk-guards.jsonl}. Every line carries the two fields that move one-shot fix rate:
 * {@code instead} and {@code why}.
 */
public final class GuardMessages {

    static final int SITES = 10;

    private GuardMessages() {}

    /** The diagnostic for one red rule. */
    public static String render(RuleReport r) {
        Rule rule = r.rule();
        StringBuilder sb = new StringBuilder();
        List<Observation> fresh = r.fresh();
        if (r.outcome() == Outcome.VIOLATIONS) {
            sb.append("GUARD ")
                    .append(rule.id())
                    .append("  (")
                    .append(fresh.size())
                    .append(" new");
            if (!r.baselined().isEmpty())
                sb.append("; ").append(r.baselined().size()).append(" baselined");
            sb.append(")\n");
            int shown = 0;
            for (Observation o : fresh) {
                if (shown++ == SITES) {
                    sb.append("  +").append(fresh.size() - SITES).append(" more — target/jk-guards.jsonl\n");
                    break;
                }
                sb.append("  ");
                if (o.file() != null) {
                    sb.append(o.file());
                    if (o.line() > 0) sb.append(':').append(o.line());
                    sb.append("  ");
                }
                sb.append(o.detail()).append('\n');
            }
        } else {
            sb.append("GUARD ")
                    .append(rule.id())
                    .append("  ")
                    .append(r.outcome().id())
                    .append('\n');
            if (!r.note().isEmpty()) sb.append("  Observed: ").append(r.note()).append('\n');
        }
        if (rule.instead() != null)
            sb.append("  Instead:  ").append(rule.instead()).append('\n');
        sb.append("  Why:      ").append(rule.why()).append('\n');
        if (r.outcome() == Outcome.VIOLATIONS) {
            sb.append("  Exempt:   ask the user to add [guards.")
                    .append(rule.id())
                    .append("].allow with a reason\n");
        }
        sb.append("  Explain:  jk guard explain ").append(rule.id());
        return sb.toString();
    }

    /** The one-line summary of a lane run, for the task label and the results file. */
    public static String summary(LaneRun.Result result, int rules) {
        StringBuilder sb = new StringBuilder();
        List<RuleReport> red = result.redReports();
        if (red.isEmpty()) {
            sb.append(rules).append(rules == 1 ? " rule" : " rules").append(" · clean");
        } else {
            sb.append(red.size()).append(red.size() == 1 ? " rule broken" : " rules broken");
            sb.append(" (").append(result.freshCount()).append(" new)");
        }
        if (result.tightened() > 0) sb.append(" · ").append(result.tightened()).append(" baseline entries tightened");
        return sb.toString();
    }

    /** Printed once per run, after the last violation. */
    public static final String TRAILER =
            "To exempt a site, stop and ask the user to add an `allow` entry with a reason to jk-guards.toml."
                    + " Never edit jk-guards-baseline.toml by hand; never add a suppression comment.";
}
