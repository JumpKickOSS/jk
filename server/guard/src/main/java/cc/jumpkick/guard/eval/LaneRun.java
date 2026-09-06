// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Lane;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every rule a lane owns. Collected: one run reports every red rule. A throwing evaluator is
 * that rule's {@code scanner-failed}; the next rule still runs. Reconciliation against the baseline
 * is applied per rule; the tightened baseline is returned for the engine to write (never under CI).
 */
public final class LaneRun {

    private LaneRun() {}

    /**
     * @param reports one per rule the lane owns, in id order
     * @param baseline the baseline after tightening (identical to the input when nothing tightened)
     * @param tightened how many entries were dropped or lowered
     */
    public record Result(Lane lane, List<RuleReport> reports, Baseline baseline, int tightened) {

        public boolean red() {
            for (RuleReport r : reports) if (r.red()) return true;
            return false;
        }

        public List<RuleReport> redReports() {
            List<RuleReport> out = new ArrayList<>();
            for (RuleReport r : reports) if (r.red()) out.add(r);
            return out;
        }

        public int freshCount() {
            int n = 0;
            for (RuleReport r : reports) n += r.fresh().size();
            return n;
        }

        public int baselinedCount() {
            int n = 0;
            for (RuleReport r : reports) n += r.baselined().size();
            return n;
        }
    }

    /** The rules of {@code set} this lane owns for {@code module} ({@code ""} at the root). */
    public static List<Rule> rulesFor(Lane lane, RuleSet set, String module) {
        List<Rule> out = new ArrayList<>();
        for (String id : set.ids()) {
            Rule r = set.rules().get(id);
            if (r == null || Evaluators.laneOf(r) != lane) continue;
            if (lane == Lane.MODULE && !r.applies(module)) continue;
            out.add(r);
        }
        return out;
    }

    public static Result run(Lane lane, List<Rule> rules, EvalContext ctx, Baseline baseline) {
        List<RuleReport> reports = new ArrayList<>();
        Baseline current = baseline;
        int tightened = 0;
        for (Rule rule : rules) {
            Evaluation ev;
            try {
                ev = Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
            } catch (Throwable t) {
                ev = Evaluation.failed(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
            RuleBaseline before = current.of(rule.id());
            if (ev.outcome() == Outcome.CLEAN || ev.outcome() == Outcome.VIOLATIONS) {
                Reconciliation rec = Reconciliation.of(rule.id(), before, ev.observations(), ev.population());
                if (rec.scopeShrunk() != null) {
                    reports.add(
                            new RuleReport(rule, Outcome.SCOPE_SHRUNK, ev, rec, "scope-shrunk: " + rec.scopeShrunk()));
                    continue;
                }
                if (rec.tighteningNeeded()) {
                    tightened += rec.stale().size()
                            + Math.max(0, before.entries().size() - rec.stale().size() - unchanged(before, rec));
                    current = current.with(rule.id(), rec.tightened());
                }
                Outcome outcome =
                        rec.fresh().isEmpty() && rec.baselined().isEmpty() ? Outcome.CLEAN : Outcome.VIOLATIONS;
                reports.add(new RuleReport(rule, outcome, ev, rec, ev.note()));
            } else {
                reports.add(new RuleReport(rule, ev.outcome(), ev, null, ev.note()));
            }
        }
        return new Result(lane, reports, current, tightened);
    }

    /** Entries carried over unchanged, so the tightened count is drops plus lowered lines. */
    private static int unchanged(RuleBaseline before, Reconciliation rec) {
        int n = 0;
        for (var e : before.entries()) if (rec.tightened().entries().contains(e)) n++;
        return n;
    }
}
