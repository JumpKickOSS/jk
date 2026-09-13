// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Sample;
import cc.jumpkick.guard.extract.fixture.Tier;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Kind;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaneRunTest {

    private static final String RULES = """
            [guards.one-owner]
            kind = "split-package"
            why  = "one module per package"

            [guards.no-sysout]
            kind       = "forbid"
            signatures = ["java.lang.System#out"]
            instead    = "a logger"
            why        = "stdout is not a log"

            [guards.only-web]
            kind  = "vocabulary"
            owner = "a.Owner"
            scope = "web/*"
            instead = "Owner.X"
            why   = "typed once"
            """;

    private static EvalContext ctx(Path root, Lane lane, String module) {
        return new EvalContext(
                lane,
                root,
                module,
                root.resolve(module),
                List.of(root.resolve(module)),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
    }

    private static LoadResult load(Path dir) throws IOException {
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), RULES);
        LoadResult r = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(r.hasErrors()).as(r.problems().toString()).isFalse();
        return r;
    }

    @AfterEach
    void unregister() {
        Evaluators.restoreDefaults();
    }

    @Test
    void rules_route_to_lanes_and_scope(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir);
        assertThat(LaneRun.rulesFor(Lane.WORKSPACE, r.rules(), ""))
                .extracting(Rule::id)
                .containsExactly("one-owner");
        assertThat(LaneRun.rulesFor(Lane.MODULE, r.rules(), "web/app"))
                .extracting(Rule::id)
                .containsExactly("no-sysout");
        assertThat(LaneRun.rulesFor(Lane.TREE, r.rules(), ""))
                .as("vocabulary scans every module after the module lanes: the tree lane")
                .extracting(Rule::id)
                .containsExactly("only-web");
        assertThat(LaneRun.rulesFor(Lane.MODEL, r.rules(), "")).isEmpty();
    }

    @Test
    void a_clean_rule_without_bite_evidence_is_no_bite_except_in_a_module_lane(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir);
        Evaluators.register(Kind.SPLIT_PACKAGE, (rule, ctx) -> Evaluation.of(Map.of("packages", 12L), List.of())
                .withBite(false));
        Lane lane = Evaluators.laneOf(r.rules().rule("one-owner").orElseThrow());
        LaneRun.Result res =
                LaneRun.run(lane, LaneRun.rulesFor(lane, r.rules(), ""), ctx(dir, lane, ""), Baseline.EMPTY);
        assertThat(res.reports()).singleElement().satisfies(rep -> {
            assertThat(rep.outcome()).isEqualTo(Outcome.NO_BITE);
            assertThat(rep.red()).isTrue();
            assertThat(rep.note()).contains("fired nowhere");
        });
        assertThat(GuardMessages.render(res.reports().get(0))).startsWith("GUARD one-owner  no-bite");

        Evaluators.register(Kind.FORBID, (rule, ctx) -> Evaluation.of(Map.of("classes", 3L), List.of())
                .withBite(false));
        LaneRun.Result module = LaneRun.run(
                Lane.MODULE,
                LaneRun.rulesFor(Lane.MODULE, r.rules(), "core"),
                ctx(dir, Lane.MODULE, "core"),
                Baseline.EMPTY);
        assertThat(module.reports().get(0).outcome())
                .as("one module is not the whole rule; the tree lane judges bite across lanes")
                .isEqualTo(Outcome.CLEAN);
        assertThat(module.reports().get(0).evaluation().bites()).isFalse();
    }

    @Test
    void a_lane_with_a_skipped_rule_is_not_red_and_not_complete(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), RULES);
        Rule rule = GuardRules.load(dir, GuardsConfig.ABSENT)
                .rules()
                .rule("one-owner")
                .orElseThrow();
        RuleReport skipped =
                new RuleReport(rule, Outcome.SKIPPED, Evaluation.skipped("shellcheck: not installed"), null, "");
        LaneRun.Result result = new LaneRun.Result(Lane.TREE, List.of(skipped), Baseline.EMPTY, 0);
        assertThat(result.red()).isFalse();
        assertThat(result.skippedReports()).containsExactly(skipped);
        assertThat(result.complete())
                .as("a verdict with a rule that did not run is not one to cache")
                .isFalse();
        assertThat(new LaneRun.Result(Lane.TREE, List.of(), Baseline.EMPTY, 0).complete())
                .isTrue();
    }

    @Test
    void an_unlanded_kind_is_unsupported_and_red_never_clean(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir);
        Evaluators.register(Kind.FORBID, (rule, ctx) -> Evaluation.unsupported("kind forbid has no evaluator yet"));
        LaneRun.Result res = LaneRun.run(
                Lane.MODULE,
                LaneRun.rulesFor(Lane.MODULE, r.rules(), "core"),
                ctx(dir, Lane.MODULE, "core"),
                Baseline.EMPTY);
        assertThat(res.reports()).singleElement().satisfies(rep -> {
            assertThat(rep.outcome()).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(rep.red()).isTrue();
        });
        assertThat(GuardMessages.render(res.reports().get(0)))
                .startsWith("GUARD no-sysout  unsupported")
                .contains("Instead:  a logger")
                .contains("Why:      stdout");
    }

    @Test
    void a_throwing_evaluator_is_scanner_failed_and_the_next_rule_still_runs(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir);
        Evaluators.register(Kind.SPLIT_PACKAGE, (rule, ctx) -> {
            throw new IllegalStateException("boom");
        });
        Evaluators.register(Kind.VOCABULARY, (rule, ctx) -> Evaluation.of(Map.of("literals", 3L), List.of()));
        List<Rule> rules = new ArrayList<>(LaneRun.rulesFor(Lane.WORKSPACE, r.rules(), ""));
        rules.addAll(LaneRun.rulesFor(Lane.TREE, r.rules(), ""));
        LaneRun.Result res = LaneRun.run(Lane.TREE, rules, ctx(dir, Lane.TREE, ""), Baseline.EMPTY);
        assertThat(res.reports())
                .extracting(RuleReport::outcome)
                .containsExactly(Outcome.SCANNER_FAILED, Outcome.CLEAN);
        assertThat(res.reports().get(0).note()).contains("IllegalStateException: boom");
        assertThat(res.red()).isTrue();
    }

    @Test
    void violations_reconcile_against_the_baseline_and_tighten(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir);
        Evaluators.register(
                Kind.SPLIT_PACKAGE,
                (rule, ctx) -> Evaluation.of(
                        Map.of("packages", 40L),
                        List.of(
                                Observation.site("a.b", "x/A.java", 3, "a.b in x and y"),
                                Observation.site("c.d", null, 0, "c.d in x and z"))));
        Baseline before = Baseline.EMPTY.with(
                "one-owner",
                RuleBaseline.of(
                        Map.of("packages", 40L),
                        List.of(new Entry.Site("a.b", "documented"), new Entry.Site("gone", "was split once"))));
        LaneRun.Result res = LaneRun.run(
                Lane.WORKSPACE, LaneRun.rulesFor(Lane.WORKSPACE, r.rules(), ""), ctx(dir, Lane.WORKSPACE, ""), before);
        RuleReport rep = res.reports().get(0);
        assertThat(rep.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(rep.fresh()).extracting(Observation::key).containsExactly("c.d");
        assertThat(rep.baselined()).extracting(Observation::key).containsExactly("a.b");
        assertThat(res.tightened()).isEqualTo(1);
        assertThat(res.baseline().of("one-owner").entries())
                .extracting(Entry::key)
                .containsExactly("a.b");
        String msg = GuardMessages.render(rep);
        assertThat(msg)
                .startsWith("GUARD one-owner  (1 new; 1 baselined)")
                .contains("c.d in x and z")
                .contains("Exempt:   ask the user")
                .doesNotContain("Instead:");
        assertThat(GuardMessages.summary(res, 1)).isEqualTo("1 rule broken (1 new) · 1 baseline entries tightened");

        Baseline frozen = res.baseline()
                .with("one-owner", Objects.requireNonNull(rep.reconciliation()).frozen("agreed"));
        LaneRun.Result again = LaneRun.run(
                Lane.WORKSPACE, LaneRun.rulesFor(Lane.WORKSPACE, r.rules(), ""), ctx(dir, Lane.WORKSPACE, ""), frozen);
        assertThat(again.red()).isFalse();
        assertThat(again.tightened()).isZero();
        assertThat(GuardMessages.summary(again, 1)).isEqualTo("1 rule · clean");
    }

    @Test
    void a_shrunk_population_is_scope_shrunk_and_blind_when_zero(@TempDir Path dir) throws IOException {
        LoadResult r = load(dir);
        Evaluators.register(Kind.SPLIT_PACKAGE, (rule, ctx) -> Evaluation.of(Map.of("packages", 10L), List.of()));
        Baseline before = Baseline.EMPTY.with("one-owner", RuleBaseline.of(Map.of("packages", 40L), List.of()));
        LaneRun.Result res = LaneRun.run(
                Lane.WORKSPACE, LaneRun.rulesFor(Lane.WORKSPACE, r.rules(), ""), ctx(dir, Lane.WORKSPACE, ""), before);
        assertThat(res.reports().get(0).outcome()).isEqualTo(Outcome.SCOPE_SHRUNK);
        assertThat(res.reports().get(0).note()).isEqualTo("scope-shrunk: packages: 40 → 10");
        assertThat(res.baseline()).as("never tightened on a shrink").isEqualTo(before);
        Evaluators.register(Kind.SPLIT_PACKAGE, (rule, ctx) -> Evaluation.of(Map.of("packages", 0L), List.of()));
        LaneRun.Result blind = LaneRun.run(
                Lane.WORKSPACE,
                LaneRun.rulesFor(Lane.WORKSPACE, r.rules(), ""),
                ctx(dir, Lane.WORKSPACE, ""),
                Baseline.EMPTY);
        assertThat(blind.reports().get(0).outcome()).isEqualTo(Outcome.BLIND);
    }

    @Test
    void the_classpath_jars_a_lane_opened_are_closed_when_its_rules_are_evaluated(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), """
                [guards.tier]
                kind = "classes"
                that = { named = "Sample" }
                should = { extend = "cc.jumpkick.guard.extract.fixture.Tier" }
                why = "w"
                """);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        ClassFacts sample = FactsExtractor.extract(FixtureBytes.of(Sample.class));
        FactsIndex facts = new FactsIndex(Map.of(sample.name(), sample), Map.of(), "");
        // a one-class jar on the compile classpath: the hierarchy opens every jar on its first lookup
        Path jar = dir.resolve("tier.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("cc/jumpkick/guard/extract/fixture/Tier.class"));
            out.write(FixtureBytes.of(Tier.class));
            out.closeEntry();
        }
        EvalContext ctx = new EvalContext(
                Lane.MODULE,
                dir,
                "m",
                dir.resolve("m"),
                List.of(dir.resolve("m")),
                () -> facts,
                () -> null,
                () -> List.of(jar));
        TypeHierarchy built = ctx.hierarchy();
        Map<String, Evaluation> out = LaneRun.evaluate(LaneRun.rulesFor(Lane.MODULE, load.rules(), "m"), ctx);
        assertThat(Objects.requireNonNull(out.get("tier")).outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(built.openJars())
                .as("the lane resolved through the jar and closed it")
                .isZero();
        assertThat(ctx.hierarchy()).as("a later use builds afresh").isNotSameAs(built);
        ctx.closeHierarchy();
    }
}
