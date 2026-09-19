// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputMeasuresTest {

    private static final String JAR_CAP = """
            [guards.jar-cap]
            kind     = "metric"
            measure  = "jar-size"
            cap      = 10
            baseline = true
            why      = "a jar that grows unnoticed ships something nobody meant to"
            """;

    private static String jacoco(int lineMissed, int lineCovered) {
        return "<?xml version=\"1.0\"?><report name=\"core\"><package name=\"a\"/>"
                + "<counter type=\"LINE\" missed=\"" + lineMissed + "\" covered=\"" + lineCovered + "\"/>"
                + "<counter type=\"BRANCH\" missed=\"5\" covered=\"5\"/></report>";
    }

    @Test
    void jar_size_growth_past_the_baseline_fires_and_shrink_tightens(@TempDir Path root) throws Exception {
        OutputArtifacts.Module m = OutputEvaluatorTest.scaffold(root);
        OutputEvaluatorTest.jar(m.jar(), Map.of(), "a/A.class");
        long size = Files.size(m.jar());
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), JAR_CAP);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.OUTPUT,
                        root,
                        "",
                        null,
                        List.of(root.resolve("core")),
                        () -> FactsIndex.EMPTY,
                        () -> null,
                        List::of)
                .withRules(load.rules());
        List<Rule> rules = LaneRun.rulesFor(Lane.OUTPUT, load.rules(), "");
        // the baseline tolerates a bigger jar than the one on disk: baselined, and the entry lowers
        Baseline loose = Baseline.EMPTY.with(
                "jar-cap",
                RuleBaseline.of(Map.of("units", 1L), List.of(new Entry.Metric("core", size + 500, "agreed"))));
        LaneRun.Result shrink = LaneRun.run(Lane.OUTPUT, rules, ctx, loose);
        assertThat(shrink.red()).as(shrink.reports().toString()).isFalse();
        assertThat(shrink.tightened()).isGreaterThan(0);
        // the baseline tolerates less than the jar measures: growth past the ratchet is red
        Baseline tight = Baseline.EMPTY.with(
                "jar-cap", RuleBaseline.of(Map.of("units", 1L), List.of(new Entry.Metric("core", size - 1, "agreed"))));
        LaneRun.Result growth = LaneRun.run(Lane.OUTPUT, rules, ctx, tight);
        assertThat(growth.red()).isTrue();
        assertThat(growth.redReports().get(0).fresh().get(0).detail())
                .contains("jar-size = " + size + " bytes in core");
        // no jar at all: not evaluated, naming the path looked for
        Files.delete(m.jar());
        Evaluation none = Objects.requireNonNull(LaneRun.evaluate(rules, ctx).get("jar-cap"));
        assertThat(none.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(none.note()).contains("looked for " + m.jar());
    }

    private static final String CHECKSTYLE_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <checkstyle version="14.1.0">
              <file name="/w/core/src/main/java/a/A.java">
                <error line="3" column="1" severity="error" message="m" source="c.MagicNumberCheck"/>
                <error line="4" column="1" severity="warning" message="m" source="c.NeedBracesCheck"/>
                <error line="5" column="1" severity="ignore" message="m" source="c.FinalClassCheck"/>
              </file>
              <file name="/w/core/src/main/java/a/B.java">
                <error line="9" column="1" severity="error" message="m" source="c.MagicNumberCheck"/>
              </file>
            </checkstyle>
            """;

    private static final String PMD_REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pmd xmlns="http://pmd.sourceforge.net/report/2.0.0" version="7.27.0">
              <file name="/w/core/src/main/java/a/A.java">
                <violation beginline="3" endline="3" begincolumn="1" endcolumn="2" rule="UnusedLocalVariable" ruleset="Best Practices" priority="3">t</violation>
                <violation beginline="7" endline="7" begincolumn="1" endcolumn="2" rule="EmptyCatchBlock" ruleset="Error Prone" priority="3">t</violation>
              </file>
              <error filename="/w/core/src/main/java/a/C.java" msg="PMDException: Error while parsing"/>
            </pmd>
            """;

    /** The lint reports the build left are one finding count per module, whole or per tool, capped and ratcheted like a jar. */
    @Test
    void lint_findings_are_counted_from_the_reports_per_module_and_a_missing_report_is_not_evaluated(@TempDir Path root)
            throws Exception {
        OutputArtifacts.Module m = OutputEvaluatorTest.scaffold(root);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.lint-cap]
                kind     = "metric"
                measure  = "lint.findings"
                cap      = 4
                why      = "lint findings only go down"

                [guards.checkstyle-cap]
                kind     = "metric"
                measure  = "lint.checkstyle"
                cap      = 3
                why      = "checkstyle findings only go down"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.OUTPUT,
                        root,
                        "",
                        null,
                        List.of(root.resolve("core")),
                        () -> FactsIndex.EMPTY,
                        () -> null,
                        List::of)
                .withRules(load.rules());
        List<Rule> rules = LaneRun.rulesFor(Lane.OUTPUT, load.rules(), "");

        Evaluation none = Objects.requireNonNull(LaneRun.evaluate(rules, ctx).get("lint-cap"));
        assertThat(none.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(none.note())
                .contains("no lint report this build")
                .contains(m.lintReport("checkstyle").toString());

        Files.createDirectories(
                Objects.requireNonNull(m.lintReport("checkstyle").getParent()));
        Files.writeString(m.lintReport("checkstyle"), CHECKSTYLE_REPORT);
        Files.createDirectories(Objects.requireNonNull(m.lintReport("pmd").getParent()));
        Files.writeString(m.lintReport("pmd"), PMD_REPORT);
        Map<String, Evaluation> evaluated = LaneRun.evaluate(rules, ctx);
        Evaluation all = Objects.requireNonNull(evaluated.get("lint-cap"));
        assertThat(all.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(all.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("core");
            assertThat(o.detail()).contains("lint.findings = 6 in core (cap 4)");
        });
        Evaluation checkstyle = Objects.requireNonNull(evaluated.get("checkstyle-cap"));
        assertThat(checkstyle.outcome()).as(checkstyle.note()).isEqualTo(Outcome.CLEAN);
        assertThat(checkstyle.population()).containsEntry("units", 1L);
    }

    /** A {@code [lint.<name>]} entry's report counts under {@code lint.checkstyle} and {@code lint.findings} beside the table's own. */
    @Test
    void every_checkstyle_runs_report_is_counted(@TempDir Path root) throws Exception {
        OutputArtifacts.Module m = OutputEvaluatorTest.scaffold(root);
        Files.writeString(root.resolve("core/jk.toml"), """
                group = "com.acme"
                name = "core"
                version = "1.0.0"
                jdk = 25

                [lint]
                checkstyle = "config/checkstyle.xml"

                [lint.nohttp]
                checkstyle = "config/nohttp-checkstyle.xml"
                """);
        m = OutputArtifacts.of(root, List.of(root.resolve("core")), null).get(0);
        assertThat(m.checkstyleRuns()).containsExactly("nohttp");
        Path nohttp = m.lintReport("checkstyle", "nohttp");
        assertThat(nohttp.endsWith(Path.of("lint-checkstyle-nohttp", "lint", "checkstyle-nohttp", "checkstyle.xml")))
                .isTrue();
        Files.createDirectories(
                Objects.requireNonNull(m.lintReport("checkstyle").getParent()));
        Files.writeString(m.lintReport("checkstyle"), CHECKSTYLE_REPORT);
        Files.createDirectories(Objects.requireNonNull(nohttp.getParent()));
        Files.writeString(nohttp, CHECKSTYLE_REPORT);
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), """
                [guards.checkstyle-cap]
                kind     = "metric"
                measure  = "lint.checkstyle"
                cap      = 5
                why      = "checkstyle findings only go down"
                """);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.OUTPUT,
                        root,
                        "",
                        null,
                        List.of(root.resolve("core")),
                        () -> FactsIndex.EMPTY,
                        () -> null,
                        List::of)
                .withRules(load.rules());
        List<Rule> rules = LaneRun.rulesFor(Lane.OUTPUT, load.rules(), "");

        Evaluation checkstyle =
                Objects.requireNonNull(LaneRun.evaluate(rules, ctx).get("checkstyle-cap"));

        assertThat(checkstyle.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(checkstyle.observations()).singleElement().satisfies(o -> assertThat(o.detail())
                .contains("lint.checkstyle = 6 in core (cap 5)"));
    }

    @Test
    void coverage_below_the_floor_fires_per_module_with_allow_and_a_missing_report_is_not_evaluated(@TempDir Path root)
            throws Exception {
        OutputArtifacts.Module m = OutputEvaluatorTest.scaffold(root);
        Files.createDirectories(root.resolve("generated-x"));
        Files.writeString(
                root.resolve("generated-x/jk.toml"),
                "group = \"com.acme\"\nname = \"gen\"\nversion = \"1.0.0\"\njdk = 25\n");
        String rules = """
                [guards.coverage-floor]
                kind    = "metric"
                measure = "coverage.line"
                min     = 80
                allow   = [{ in = "generated-*", reason = "codegen modules" }]
                why     = "a floor under the tests"
                """;
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        List<Path> modules = List.of(root.resolve("core"), root.resolve("generated-x"));
        EvalContext ctx = new EvalContext(
                        Lane.OUTPUT, root, "", null, modules, () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
        List<Rule> rs = LaneRun.rulesFor(Lane.OUTPUT, load.rules(), "");
        Evaluation none = Objects.requireNonNull(LaneRun.evaluate(rs, ctx).get("coverage-floor"));
        assertThat(none.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(none.note()).contains("no coverage report this build").contains("jacoco.xml");

        Files.createDirectories(Objects.requireNonNull(m.coverage().getParent()));
        Files.writeString(m.coverage(), jacoco(50, 50));
        List<OutputArtifacts.Module> all = OutputArtifacts.of(root, modules, null);
        Path genReport = all.get(1).coverage();
        Files.createDirectories(Objects.requireNonNull(genReport.getParent()));
        Files.writeString(genReport, jacoco(90, 10));
        Evaluation e = Objects.requireNonNull(LaneRun.evaluate(rs, ctx).get("coverage-floor"));
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).hasSize(1);
        assertThat(e.observations().get(0).key()).isEqualTo("core");
        assertThat(e.observations().get(0).detail()).contains("coverage.line = 50% in core (floor 80)");
        assertThat(e.population()).containsEntry("units", 2L);

        // a configured report path is one report for the workspace
        Files.writeString(root.resolve("cov.xml"), jacoco(10, 90));
        LoadResult configured = GuardRules.load(root, new GuardsConfig(true, "cov.xml", true));
        EvalContext cctx = new EvalContext(
                        Lane.OUTPUT, root, "", null, modules, () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(configured.rules());
        Evaluation c =
                Objects.requireNonNull(LaneRun.evaluate(LaneRun.rulesFor(Lane.OUTPUT, configured.rules(), ""), cctx)
                        .get("coverage-floor"));
        assertThat(c.outcome()).as(c.note()).isEqualTo(Outcome.CLEAN);
    }
}
