// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetricEvaluatorTest {

    private static void tree(Path root) throws IOException {
        Path a = Files.createDirectories(root.resolve("mod-a/src/main/java/a"));
        StringBuilder big = new StringBuilder("package a;\n/* one\n   two\n   three\n   four */\nclass Big {\n");
        for (int i = 0; i < 12; i++)
            big.append("    int f")
                    .append(i)
                    .append(" = java.util.Objects.hash(")
                    .append(i)
                    .append(");\n");
        big.append("    // a\n    // b\n    // c\n}\n");
        Files.writeString(a.resolve("Big.java"), big.toString());
        Files.writeString(a.resolve("Small.java"), "package a;\nclass Small { int x = 1; } // TODO fix later\n");
        Path b = Files.createDirectories(root.resolve("mod-b/src/main/kotlin/b"));
        Files.writeString(b.resolve("B.kt"), "package b\nclass B { val x = 1 }\nval y = 2\nval z = 3\n");
    }

    private static LoadResult load(Path root, String rules) throws IOException {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load;
    }

    private static EvalContext ctx(Path root, LoadResult load) {
        return new EvalContext(Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
    }

    private static Evaluation one(Path root, String rules) throws IOException {
        LoadResult load = load(root, rules);
        Map<String, Evaluation> r = LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx(root, load));
        return Objects.requireNonNull(r.get("m"), r.toString());
    }

    private static String rule(String body) {
        int allow = body.indexOf("[[guards.m.allow]]");
        String keys = allow < 0 ? body : body.substring(0, allow);
        String tail = allow < 0 ? "" : body.substring(allow);
        return "[guards.m]\nkind = \"metric\"\n" + keys + "why = \"w\"\n" + tail;
    }

    @Test
    void lines_over_a_scalar_cap_are_metric_observations(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(root, rule("measure = \"lines\"\ncap = 10\n"));
        assertThat(ev.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(ev.population()).containsEntry("units", 3L);
        assertThat(ev.observations()).hasSize(1);
        Observation o = ev.observations().get(0);
        assertThat(o.key()).isEqualTo("mod-a/src/main/java/a/Big.java");
        assertThat(o.isMetric()).isTrue();
        assertThat(o.value()).isEqualTo(14.0); // package + class + 12 fields + brace: comments are not code
        assertThat(o.detail()).contains("lines = 14").contains("cap 10");
    }

    @Test
    void per_language_table_measures_only_the_languages_it_names(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(root, rule("measure = \"lines\"\ncap = { kt = 2 }\n"));
        assertThat(ev.population()).containsEntry("units", 1L);
        assertThat(ev.observations()).extracting(Observation::key).containsExactly("mod-b/src/main/kotlin/b/B.kt");
        Evaluation both = one(root, rule("measure = \"lines\"\ncap = { java = 10, kt = 100 }\n"));
        assertThat(both.observations()).extracting(Observation::key).containsExactly("mod-a/src/main/java/a/Big.java");
    }

    @Test
    void per_module_sums_the_module(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(root, rule("measure = \"lines\"\nper = \"module\"\ncap = 14\n"));
        assertThat(ev.population()).containsEntry("units", 2L);
        assertThat(ev.observations()).hasSize(1);
        assertThat(ev.observations().get(0).key()).isEqualTo("mod-a");
        assertThat(ev.observations().get(0).value()).isEqualTo(15.0); // 14 + 1: package lines are not code
    }

    @Test
    void fqcn_counts_qualified_names_outside_comments_and_strings(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(root, rule("measure = \"fqcn\"\ncap = 0\n"));
        assertThat(ev.observations()).hasSize(1);
        assertThat(ev.observations().get(0).value()).isEqualTo(12.0);
    }

    @Test
    void comment_lines_measures_each_block_and_fingerprints_by_content(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(root, rule("measure = \"comment-lines\"\ncap = 3\n"));
        assertThat(ev.observations()).hasSize(1);
        Observation o = ev.observations().get(0);
        assertThat(o.value()).isEqualTo(4.0);
        assertThat(o.key()).startsWith("mod-a/src/main/java/a/Big.java | comment ");
        assertThat(o.detail()).contains("4-line comment block at line 2");
        // the three-line // run and the trailing one-liner are under the cap
        assertThat(ev.population()).containsEntry("units", 3L);
    }

    @Test
    void every_text_measure_of_the_lane_shares_one_read_of_each_file(@TempDir Path root) throws Exception {
        tree(root);
        String rules = """
                [guards.todo]
                kind = "text"
                pattern = "TODO"
                blank = "none"
                instead = "TODO(<owner>)"
                why = "w"

                [guards.lines]
                kind = "metric"
                measure = "lines"
                cap = 5
                why = "w"

                [guards.fqcn]
                kind = "metric"
                measure = "fqcn"
                cap = 1
                why = "w"

                [guards.comments]
                kind = "metric"
                measure = "comment-lines"
                cap = 2
                why = "w"

                [guards.todos]
                kind = "metric"
                measure = "matches:todo"
                per = "module"
                cap = 0
                why = "w"

                [guards.methods]
                kind = "metric"
                measure = "methods"
                cap = 1
                why = "w"
                """;
        LoadResult load = load(root, rules);
        long before = TextFiles.READS.sum();
        Map<String, Evaluation> r = LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx(root, load));
        assertThat(TextFiles.READS.sum() - before)
                .as("three files under src: one pass for the text rule, one for the four text measures together")
                .isEqualTo(6);
        assertThat(r).containsOnlyKeys("todo", "lines", "fqcn", "comments", "todos");
        assertThat(Objects.requireNonNull(r.get("lines")).observations())
                .extracting(Observation::key)
                .containsExactly("mod-a/src/main/java/a/Big.java");
        assertThat(Objects.requireNonNull(r.get("fqcn")).observations())
                .extracting(Observation::key)
                .containsExactly("mod-a/src/main/java/a/Big.java");
        assertThat(Objects.requireNonNull(r.get("comments")).observations()).hasSize(2);
        assertThat(Objects.requireNonNull(r.get("todos")).observations())
                .extracting(Observation::key)
                .containsExactly("mod-a");
        assertThat(Objects.requireNonNull(r.get("todos")).population()).containsEntry("units", 2L);
    }

    @Test
    void matches_counts_another_text_rules_hits_per_unit(@TempDir Path root) throws Exception {
        tree(root);
        String rules = """
                [guards.todo]
                kind = "text"
                pattern = "TODO"
                blank = "none"
                instead = "TODO(<owner>)"
                why = "w"
                """ + rule("measure = \"matches:todo\"\nper = \"module\"\ncap = 0\n");
        LoadResult load = load(root, rules);
        Map<String, Evaluation> r = LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx(root, load));
        Evaluation ev = Objects.requireNonNull(r.get("m"));
        assertThat(ev.observations()).hasSize(1);
        assertThat(ev.observations().get(0).key()).isEqualTo("mod-a");
        assertThat(ev.observations().get(0).value()).isEqualTo(1.0);
        Evaluation missing = one(root, rule("measure = \"matches:nope\"\ncap = 0\n"));
        assertThat(missing.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
    }

    @Test
    void min_is_a_floor(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(root, rule("measure = \"lines\"\nmin = 3\n"));
        assertThat(ev.observations()).extracting(Observation::key).containsExactly("mod-a/src/main/java/a/Small.java");
        assertThat(ev.observations().get(0).detail()).contains("floor 3");
    }

    @Test
    void allow_exempts_a_unit_and_rots_when_unused(@TempDir Path root) throws Exception {
        tree(root);
        Evaluation ev = one(
                root,
                rule("measure = \"lines\"\ncap = 10\n[[guards.m.allow]]\nin = \"mod-a/**\"\nreason = \"legacy\"\n"));
        assertThat(ev.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation rot = one(
                root,
                rule("measure = \"lines\"\ncap = 100\n[[guards.m.allow]]\nin = \"mod-z/**\"\nreason = \"legacy\"\n"));
        assertThat(rot.outcome()).isEqualTo(Outcome.STALE_ALLOW);
    }

    @Test
    void baseline_ratchets_a_metric_down_and_fires_on_growth(@TempDir Path root) throws Exception {
        tree(root);
        LoadResult load = load(root, rule("measure = \"lines\"\ncap = 10\nbaseline = true\n"));
        List<Rule> rules = LaneRun.rulesFor(Lane.TREE, load.rules(), "");
        String unit = "mod-a/src/main/java/a/Big.java";
        Baseline loose = Baseline.EMPTY.with(
                "m", RuleBaseline.of(Map.of("units", 3L), List.of(new Entry.Metric(unit, 20, "legacy"))));
        LaneRun.Result r = LaneRun.run(Lane.TREE, rules, ctx(root, load), loose);
        assertThat(r.reports().get(0).outcome()).isEqualTo(Outcome.VIOLATIONS);
        Reconciliation rec = Objects.requireNonNull(r.reports().get(0).reconciliation());
        assertThat(rec.fresh()).isEmpty();
        assertThat(rec.baselined()).hasSize(1);
        assertThat(r.tightened()).isEqualTo(1);
        Entry.Metric after = (Entry.Metric) r.baseline().of("m").entries().get(0);
        assertThat(after.value()).isEqualTo(14.0);

        Baseline tight = Baseline.EMPTY.with(
                "m", RuleBaseline.of(Map.of("units", 3L), List.of(new Entry.Metric(unit, 12, "legacy"))));
        LaneRun.Result grown = LaneRun.run(Lane.TREE, rules, ctx(root, load), tight);
        assertThat(Objects.requireNonNull(grown.reports().get(0).reconciliation())
                        .fresh())
                .hasSize(1);
        assertThat(grown.tightened()).isZero();
    }
}
