// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Baselines;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.GuardsConfig;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk guard freeze}: accept a rule's current new violations into the baseline with a reason,
 * or retire a removed rule's entries. Evaluates the one rule in-process over the last build's
 * outputs — the facts indexes beside each module's classes, the manifests and lock, the tree — so
 * a freeze needs no build. Growth is the only privileged write; the engine tightens on its own.
 *
 * <p>A rule examining far less than its baseline recorded is {@code scope-shrunk}: a plain freeze
 * refuses it, because a shrink is a question, and {@code acceptScope} is the answer that the
 * corpus legitimately shrank — the observed population becomes the floor, with the reason beside
 * it.
 */
public final class Freezer {

    /**
     * @param accepted sites (or metric units) newly frozen, or entries dropped by a retire
     * @param total the baseline's entry count after the write
     * @param rebased lanes whose smaller population was accepted as the new floor
     */
    public record Result(@Nullable String error, int accepted, int total, int rebased) {}

    private Freezer() {}

    public static Result freeze(Path root, String ruleId, @Nullable String reason, boolean retire, boolean acceptScope)
            throws IOException {
        String refusal = Baselines.freezeRefusal(retire ? "retire" : reason, Baselines.ciMode());
        if (refusal != null) return new Result(refusal, 0, 0, 0);
        Path baselineFile = GuardsPresence.baselineFile(root);
        Baseline baseline = BaselineFile.read(baselineFile);
        GuardsConfig cfg = JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST));
        LoadResult load = GuardRules.load(root, cfg);
        if (load.hasErrors())
            return new Result(
                    "jk-guards.toml did not load: " + load.errors().get(0).render(), 0, 0, 0);
        if (retire) {
            if (load.rules().rule(ruleId).isPresent()
                    || GuardSuites.declaredIdsInSource(root).contains(ruleId))
                return new Result(
                        "`" + ruleId + "` is still declared; remove the rule first, then retire its baseline entries",
                        0,
                        0,
                        0);
            int dropped = baseline.of(ruleId).entries().size();
            if (dropped == 0 && baseline.of(ruleId).isEmpty())
                return new Result("the baseline has no entries for `" + ruleId + "`", 0, baseline.entryCount(), 0);
            Baseline after = baseline.without(ruleId);
            BaselineFile.write(baselineFile, after);
            return new Result(null, dropped, after.entryCount(), 0);
        }
        Rule rule = load.rules().rule(ruleId).orElse(null);
        if (rule == null) {
            GuardSuites.Located guard =
                    GuardSuites.declaredAcrossWorkspace(root).get(ruleId);
            if (guard != null) rule = GuardSuites.rule(guard.declared(), root, guard.module());
        }
        if (rule == null)
            return new Result(
                    "no rule `" + ruleId + "` in jk-guards.toml; ids are "
                            + String.join(", ", load.rules().ids()),
                    0,
                    0,
                    0);
        if (!Evaluators.acceptsBaseline(rule))
            return new Result(
                    "`" + ruleId
                            + "` has breaking = \"forbid\": a breaking change stays red; set breaking = \"baseline\" to accept one with a reason",
                    0,
                    baseline.entryCount(),
                    0);
        RuleBaseline current = baseline.of(ruleId);
        int accepted = 0;
        int rebased = 0;
        Lane lane = Evaluators.laneOf(rule);
        for (EvalContext bare : contexts(root, lane, rule)) {
            // The measure may name another rule (`metric matches:<id>`); the lane run hands every
            // evaluator the loaded set, and so must the freeze.
            EvalContext ctx = bare.withRules(load.rules());
            Evaluation e = LaneRun.evaluate(List.of(rule), ctx).get(ruleId);
            if (e == null) continue;
            // A module with nothing to examine (resources only, never built) is not this rule's
            // concern; the lane reports it, the freeze skips it.
            if (e.outcome() == Outcome.BLIND) continue;
            if (e.outcome() != Outcome.CLEAN
                    && e.outcome() != Outcome.VIOLATIONS
                    && e.outcome() != Outcome.STALE_ALLOW) {
                return new Result(
                        "`" + ruleId + "` is " + e.outcome().id() + " (" + e.note()
                                + "); only violations can be frozen",
                        0,
                        baseline.entryCount(),
                        0);
            }
            String slice = LaneRun.sliceOf(lane, rule, ctx.module());
            Reconciliation rec = Reconciliation.of(
                    ruleId, current, e.observations(), e.population(), slice, Evaluators.toleranceOf(rule));
            String shrunk = rec.scopeShrunk();
            if (shrunk != null && !acceptScope)
                return new Result(
                        "`" + ruleId + "` examines less than its baseline recorded (" + shrunk
                                + (slice.isEmpty() ? "" : " in " + slice)
                                + "); a shrink is a question, not a fact — `jk guard freeze " + ruleId
                                + " --accept-scope --reason \"…\"` records the smaller population as the floor",
                        0,
                        baseline.entryCount(),
                        0);
            if (rec.fresh().isEmpty() && shrunk == null) continue;
            accepted += rec.fresh().size();
            String why = reason == null ? "" : reason;
            current = rec.frozen(why);
            if (shrunk != null) {
                rebased++;
                current = current.withScopeReason(slice, why);
            }
        }
        if (accepted == 0 && rebased == 0) return new Result(null, 0, baseline.entryCount(), 0);
        Baseline after = baseline.with(ruleId, current);
        BaselineFile.write(baselineFile, after);
        return new Result(null, accepted, after.entryCount(), rebased);
    }

    /** One context per module for the module lane; one root context otherwise. */
    private static List<EvalContext> contexts(Path root, Lane lane, Rule rule) throws IOException {
        List<Path> modules = WorkspaceModules.of(root);
        List<EvalContext> out = new ArrayList<>();
        if (rule.kind() == Kind.TEST) {
            // a guard test lives in one module's suite; its report is there and nowhere else, whatever
            // it scopes over
            String home = GuardSuites.moduleOf(rule);
            Path m = home.isEmpty() ? root : root.resolve(home);
            out.add(new EvalContext(lane, root, home, m, modules, () -> FactsIndex.EMPTY, () -> null, List::of));
            return out;
        }
        if (lane == Lane.MODULE) {
            for (Path m : modules) {
                String rel = WorkspaceModel.rel(root, m);
                if (!rule.applies(rel)) continue;
                JkBuild build = JkBuildParser.parse(m.resolve(ManifestPaths.MANIFEST));
                BuildLayout layout = BuildLayout.of(m, build);
                FactsIndexing.Ensured main =
                        FactsIndexing.ensure(layout.classesDir(), FactsIndexing.indexPath(layout.buildDir(), "main"));
                if (main.tier() == FactsIndexing.Ensured.Tier.ABSENT) continue;
                out.add(new EvalContext(
                        lane,
                        root,
                        rel,
                        m,
                        List.of(m),
                        EvalContext.lazy(() -> FactsIndexing.load(main)),
                        () -> null,
                        List::of));
            }
            return out;
        }
        out.add(new EvalContext(lane, root, "", null, modules, () -> FactsIndex.EMPTY, () -> null, List::of));
        return out;
    }
}
