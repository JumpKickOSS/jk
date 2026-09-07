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
 */
public final class Freezer {

    public record Result(@Nullable String error, int accepted, int total) {}

    private Freezer() {}

    public static Result freeze(Path root, String ruleId, @Nullable String reason, boolean retire) throws IOException {
        String refusal = Baselines.freezeRefusal(retire ? "retire" : reason, Baselines.ciMode());
        if (refusal != null) return new Result(refusal, 0, 0);
        Path baselineFile = GuardsPresence.baselineFile(root);
        Baseline baseline = BaselineFile.read(baselineFile);
        GuardsConfig cfg = JkBuildParser.guardsConfig(root.resolve(ManifestPaths.MANIFEST));
        LoadResult load = GuardRules.load(root, cfg);
        if (load.hasErrors())
            return new Result(
                    "jk-guards.toml did not load: " + load.errors().get(0).render(), 0, 0);
        if (retire) {
            if (load.rules().rule(ruleId).isPresent())
                return new Result(
                        "`" + ruleId + "` is still declared; remove the rule first, then retire its baseline entries",
                        0,
                        0);
            int dropped = baseline.of(ruleId).entries().size();
            if (dropped == 0 && baseline.of(ruleId).isEmpty())
                return new Result("the baseline has no entries for `" + ruleId + "`", 0, baseline.entryCount());
            Baseline after = baseline.without(ruleId);
            BaselineFile.write(baselineFile, after);
            return new Result(null, dropped, after.entryCount());
        }
        Rule rule = load.rules().rule(ruleId).orElse(null);
        if (rule == null)
            return new Result(
                    "no rule `" + ruleId + "` in jk-guards.toml; ids are "
                            + String.join(", ", load.rules().ids()),
                    0,
                    0);
        RuleBaseline current = baseline.of(ruleId);
        int accepted = 0;
        Lane lane = Evaluators.laneOf(rule);
        for (EvalContext ctx : contexts(root, lane, rule)) {
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
                        baseline.entryCount());
            }
            String slice = lane == Lane.MODULE ? ctx.module() : "";
            Reconciliation rec = Reconciliation.of(ruleId, current, e.observations(), e.population(), slice);
            if (rec.fresh().isEmpty()) continue;
            accepted += rec.fresh().size();
            current = rec.frozen(reason == null ? "" : reason);
        }
        if (accepted == 0) return new Result(null, 0, baseline.entryCount());
        Baseline after = baseline.with(ruleId, current);
        BaselineFile.write(baselineFile, after);
        return new Result(null, accepted, after.entryCount());
    }

    /** One context per module for the module lane; one root context otherwise. */
    private static List<EvalContext> contexts(Path root, Lane lane, Rule rule) throws IOException {
        List<Path> modules = WorkspaceModules.of(root);
        List<EvalContext> out = new ArrayList<>();
        if (lane == Lane.MODULE) {
            for (Path m : modules) {
                String rel = root.equals(m) ? "" : root.relativize(m).toString().replace('\\', '/');
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
