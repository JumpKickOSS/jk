// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * The baseline slice a lane's verdict on {@code rule} owns: the module for a module lane, the
     * root otherwise — except a workspace-scoped guard test, which runs in its suite's module lane
     * but judges the whole tree, so its entries live in the root slice where freeze puts them.
     */
    public static String sliceOf(Lane lane, Rule rule, String module) {
        if (rule.kind() == Kind.TEST && Evaluators.laneOf(rule) == Lane.WORKSPACE) return "";
        return lane == Lane.MODULE ? module : "";
    }

    public static Result run(Lane lane, List<Rule> rules, EvalContext ctx, Baseline baseline) {
        List<RuleReport> reports = new ArrayList<>();
        Baseline current = baseline;
        int tightened = 0;
        Map<String, Evaluation> evaluations = evaluate(rules, ctx);
        for (Rule rule : rules) {
            Evaluation ev = evaluations.getOrDefault(rule.id(), Evaluation.failed("the evaluator returned no result"));
            // A fixture is bite evidence the gate proves (`jk guard test`, the guard-fixtures step): a
            // rule that names one is not looking at nothing even when the tree has no site today.
            if (rule.fixture() != null && !ev.bites()) ev = ev.withBite(true);
            RuleBaseline before = Evaluators.acceptsBaseline(rule) ? current.of(rule.id()) : RuleBaseline.EMPTY;
            if (ev.outcome() == Outcome.CLEAN || ev.outcome() == Outcome.VIOLATIONS) {
                String slice = sliceOf(lane, rule, ctx.module());
                Reconciliation rec = Reconciliation.of(
                        rule.id(), before, ev.observations(), ev.population(), slice, Evaluators.toleranceOf(rule));
                if (rec.scopeShrunk() != null) {
                    reports.add(
                            new RuleReport(rule, Outcome.SCOPE_SHRUNK, ev, rec, "scope-shrunk: " + rec.scopeShrunk()));
                    continue;
                }
                if (rec.tighteningNeeded()) {
                    tightened += rec.stale().size()
                            + Math.max(
                                    0,
                                    before.entries(slice).size() - rec.stale().size() - unchanged(before, rec));
                    current = current.with(rule.id(), rec.tightened());
                }
                Outcome outcome =
                        rec.fresh().isEmpty() && rec.baselined().isEmpty() ? Outcome.CLEAN : Outcome.VIOLATIONS;
                if (outcome == Outcome.CLEAN && !ev.bites() && lane != Lane.MODULE) {
                    // A module lane sees one module; its bite is judged across lanes by the tree lane.
                    reports.add(new RuleReport(rule, Outcome.NO_BITE, ev, rec, noBiteNote(rule)));
                    continue;
                }
                reports.add(new RuleReport(rule, outcome, ev, rec, ev.note()));
            } else {
                reports.add(new RuleReport(rule, ev.outcome(), ev, null, ev.note()));
            }
        }
        return new Result(lane, reports, current, tightened);
    }

    /** Why a clean pass with no bite evidence is red, in the kind's own terms. */
    public static String noBiteNote(Rule rule) {
        String path =
                switch (rule.kind()) {
                    case FORBID ->
                        "add `owner` (a class that legitimately uses the primitive) so the rule proves it can see one";
                    case TEXT -> "add `hit` (a snippet the pattern must match through its view)";
                    default -> "give the rule something to examine";
                };
        String fixture = rule.fixture() == null ? "" : ", or `fixture` (a Bad/Ok directory `jk guard test` proves)";
        return "the rule fired nowhere and carries no evidence it can — " + path + fixture;
    }

    /**
     * Every rule's evaluation: batch kinds (the text lane) run once over all their rules, the rest one
     * by one. A throwing evaluator is that rule's — or, for a batch, those rules' — {@code
     * scanner-failed}; the next kind still runs. The type hierarchy the rules resolved through, and
     * the classpath jars it opened, are released once the last rule has run.
     */
    static Map<String, Evaluation> evaluate(List<Rule> rules, EvalContext ctx) {
        try {
            return evaluateAll(rules, ctx);
        } finally {
            ctx.closeHierarchy();
        }
    }

    private static Map<String, Evaluation> evaluateAll(List<Rule> rules, EvalContext ctx) {
        Map<String, Evaluation> out = new LinkedHashMap<>();
        Map<Kind, List<Rule>> batches = new EnumMap<>(Kind.class);
        for (Rule rule : rules) {
            Evaluator e = Evaluators.forKind(rule.kind());
            if (e instanceof BatchEvaluator) {
                batches.computeIfAbsent(rule.kind(), k -> new ArrayList<>()).add(rule);
                continue;
            }
            try {
                out.put(rule.id(), e.evaluate(rule, ctx));
            } catch (Throwable t) {
                out.put(rule.id(), failed(t));
            }
        }
        for (var e : batches.entrySet()) {
            try {
                out.putAll(((BatchEvaluator) Evaluators.forKind(e.getKey())).evaluateAll(e.getValue(), ctx));
            } catch (Throwable t) {
                for (Rule r : e.getValue()) out.put(r.id(), failed(t));
            }
        }
        return out;
    }

    private static Evaluation failed(Throwable t) {
        return Evaluation.failed(t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
    }

    /** Entries carried over unchanged, so the tightened count is drops plus lowered lines. */
    private static int unchanged(RuleBaseline before, Reconciliation rec) {
        int n = 0;
        for (var e : before.entries(rec.lane())) if (rec.tightened().entries().contains(e)) n++;
        return n;
    }
}
