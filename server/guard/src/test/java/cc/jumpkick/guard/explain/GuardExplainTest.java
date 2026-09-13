// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.explain;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.baseline.BaselineFile;
import cc.jumpkick.guard.baseline.Entry;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.eval.EvalContext;
import cc.jumpkick.guard.eval.LaneRun;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardExplainTest {

    private static final String RULES = """
            [guards.no-todo]
            kind    = "text"
            pattern = "TODO"
            blank   = "none"
            instead = "TODO(<owner>)"
            why     = "an unowned TODO is a comment nobody reads again"

            [guards.digest-owner]
            kind       = "forbid"
            signatures = ["java.security.MessageDigest#getInstance(**)"]
            scope      = ["shared/host"]
            instead    = "Hashing.newSha256"
            why        = "one digest surface"
            """;

    private static Path tree(Path root) throws IOException {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        Path a = Files.createDirectories(root.resolve("mod-a/src/main/java/a"));
        Files.writeString(a.resolve("A.java"), "package a;\nclass A { } // TODO later\n");
        return root;
    }

    private static void runTreeLane(Path root) throws IOException {
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
        LaneRun.Result result =
                LaneRun.run(Lane.TREE, LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx, Baseline.EMPTY);
        RuleSummaries.write(root, "guard-tree", result);
    }

    @Test
    void card_before_and_after_a_lane_ran(@TempDir Path root) throws Exception {
        tree(root);
        GuardExplain.Result before = GuardExplain.explain(root, GuardsConfig.ABSENT, "no-todo");
        assertThat(before.error()).isNull();
        assertThat(before.text())
                .contains("no-todo  (text)")
                .contains("Why:        an unowned TODO")
                .contains("Instead:    TODO(<owner>)")
                .contains("Scope:      every module")
                .contains("Source:     jk-guards.toml:1")
                .contains("Baseline:   no entries")
                .contains("Last:       never evaluated")
                .contains("jk-guards.toml  sha256 ")
                .contains("jk-guards-baseline.toml  sha256 absent");
        runTreeLane(root);
        GuardExplain.Result after = GuardExplain.explain(root, GuardsConfig.ABSENT, "no-todo");
        assertThat(after.text()).contains("Last:       red · 1 new site").contains("Population: files=1");
        assertThat(Jsonl.str(after.json(), "rulesSha")).hasSize(64);
        assertThat(after.json()).contains("\"lastOutcome\":\"red · 1 new site\"");
    }

    @Test
    void baseline_entries_and_scope_show_on_the_card(@TempDir Path root) throws Exception {
        tree(root);
        Baseline b = Baseline.EMPTY.with(
                "digest-owner",
                RuleBaseline.of(
                        Map.of("classes", 3L),
                        List.of(new Entry.Site("a.A#f()V -> x", "legacy"), new Entry.Site("a.B#g()V -> x", "legacy"))));
        BaselineFile.write(GuardsPresence.baselineFile(root), b);
        GuardExplain.Result r = GuardExplain.explain(root, GuardsConfig.ABSENT, "digest-owner");
        assertThat(r.text())
                .contains("Scope:      shared/host")
                .contains("Baseline:   2 entries")
                .doesNotContain("sha256 absent");
    }

    @Test
    void catalog_lists_every_rule_and_names_the_nearest_id_on_a_miss(@TempDir Path root) throws Exception {
        tree(root);
        GuardExplain.Result all = GuardExplain.explain(root, GuardsConfig.ABSENT, null);
        assertThat(all.error()).isNull();
        assertThat(all.text()).contains("digest-owner").contains("no-todo").contains("2 rules");
        assertThat(all.json()).contains("\"rules\":[{\"id\":\"digest-owner\"");
        GuardExplain.Result miss = GuardExplain.explain(root, GuardsConfig.ABSENT, "no-tod");
        assertThat(miss.error()).contains("no rule `no-tod`").contains("nearest: no-todo, digest-owner");
    }

    @Test
    void no_guards_here_is_an_error_that_points_at_the_schema(@TempDir Path root) throws Exception {
        GuardExplain.Result r = GuardExplain.explain(root, GuardsConfig.ABSENT, null);
        assertThat(r.error()).contains("no guards here").contains("--schema <kind>");
    }

    @Test
    void schema_prints_a_kind_or_the_guard_test_skeleton() {
        GuardExplain.Result forbid = GuardExplain.schema("forbid");
        assertThat(forbid.error()).isNull();
        assertThat(forbid.text()).startsWith("kind = \"forbid\"").contains("example:");
        assertThat(Jsonl.str(forbid.json(), "kind")).isEqualTo("forbid");
        GuardExplain.Result skeleton = GuardExplain.schema("guard-test");
        assertThat(skeleton.text())
                .contains("@GuardSuite(")
                .contains("@Guard(id = ")
                .contains("Violations v");
        GuardExplain.Result unknown = GuardExplain.schema("forbidden");
        assertThat(unknown.error())
                .contains("unknown kind `forbidden`")
                .contains("forbid")
                .contains("guard-test");
    }

    @Test
    void engine_validations_are_listed_and_explained_but_are_not_rules(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.no-todo]\nkind = \"text\"\npattern = \"TODO\"\ninstead = \"a ticket\"\nwhy = \"w\"\n");
        GuardExplain.Result catalog = GuardExplain.explain(root, GuardsConfig.ABSENT, null);
        assertThat(catalog.error()).isNull();
        assertThat(catalog.text())
                .contains("engine validations")
                .contains("tiers")
                .contains("1 rule");
        GuardExplain.Result tiers = GuardExplain.explain(root, GuardsConfig.ABSENT, "tiers");
        assertThat(tiers.error()).isNull();
        assertThat(tiers.text())
                .startsWith("GUARD tiers  engine validation")
                .contains("model, module")
                .contains("not a rule");
        assertThat(tiers.json()).contains("\"validations\"").contains("\"code\":\"tiers\"");
        GuardExplain.Result miss = GuardExplain.explain(root, GuardsConfig.ABSENT, "tier");
        assertThat(miss.error()).contains("no rule `tier`");
    }
}
