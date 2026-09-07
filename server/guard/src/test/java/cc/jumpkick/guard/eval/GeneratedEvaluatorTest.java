// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedEvaluatorTest {

    private static final String RULE = """
            [guards.tier-doc]
            kind     = "generated"
            source   = { enum-constants = "cc.jumpkick.guard.eval.SampleTier" }
            template = { table = ["name"] }
            into     = "tiers.md"
            markers  = "tiers"
            why      = "the table drifted by hand"
            """;

    private static Map<String, Evaluation> run(Path root, String rules) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        FactsIndex facts = ExtractorsTest.sampleFacts();
        EvalContext ctx = new EvalContext(Lane.TREE, root, "", null, List.of(), () -> facts, () -> null, List::of)
                .withRules(load.rules());
        return LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx);
    }

    private static Evaluation ev(Map<String, Evaluation> r, String id) {
        return Objects.requireNonNull(r.get(id), id);
    }

    @Test
    void a_table_rendered_from_an_enum_is_clean_when_the_file_carries_it(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("tiers.md"), """
                # Tiers

                <!-- tiers:start -->
                | name |
                |---|
                | UNIT |
                | INTEGRATION |
                | SLOW |
                <!-- tiers:end -->
                """);
        Evaluation e = ev(run(root, RULE), "tier-doc");
        assertThat(e.outcome()).as(e.note()).isEqualTo(Outcome.CLEAN);
        assertThat(e.population()).containsEntry("rows", 3L);
        assertThat(e.bites()).isTrue();
    }

    @Test
    void drift_fires_once_with_the_diff_in_the_detail(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("tiers.md"), """
                <!-- tiers:start -->
                | name |
                |---|
                | UNIT |
                | NIGHTLY |
                | SLOW |
                <!-- tiers:end -->
                """);
        Evaluation e = ev(run(root, RULE), "tier-doc");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).hasSize(1);
        var o = e.observations().get(0);
        assertThat(o.key()).isEqualTo("block:tiers");
        assertThat(o.file()).isEqualTo("tiers.md");
        assertThat(o.line()).isEqualTo(1);
        assertThat(o.detail())
                .contains("- | NIGHTLY |")
                .contains("+ | INTEGRATION |")
                .doesNotContain("- | UNIT |");
    }

    @Test
    void missing_markers_are_a_load_error_and_other_templates_render(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("tiers.md"), "# no markers here\n");
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULE);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).isTrue();
        assertThat(load.problems().toString()).contains("tiers.md has no `tiers:start` … `tiers:end` marker pair");

        Files.writeString(root.resolve("tiers.md"), """
                <!-- tiers:start -->
                <!-- tiers:end -->
                # tiers.toml:start
                # tiers.toml:end
                ```
                <!-- chain:start -->
                <!-- chain:end -->
                """);
        Map<String, Evaluation> r = run(root, """
                [guards.as-list]
                kind     = "generated"
                source   = { enum-constants = "cc.jumpkick.guard.eval.SampleTier" }
                template = { list = true }
                into     = "tiers.md"
                markers  = "tiers"
                why      = "w"
                [guards.as-toml]
                kind     = "generated"
                source   = { static-finals = "cc.jumpkick.guard.eval.SampleTier" }
                template = { toml-array = "constants" }
                into     = "tiers.md"
                markers  = "tiers.toml"
                why      = "w"
                [guards.chain]
                kind     = "generated"
                source   = { enum-constants = "cc.jumpkick.guard.eval.SampleTier" }
                template = { arrow-chain = true }
                into     = "tiers.md"
                markers  = "chain"
                why      = "w"
                """);
        assertThat(ev(r, "as-list").observations().get(0).detail())
                .contains("+ - INTEGRATION")
                .contains("+ - SLOW")
                .contains("+ - UNIT");
        assertThat(ev(r, "as-toml").observations().get(0).detail()).contains("+ constants = [\"KIND\", \"FLOOR\"]");
        assertThat(ev(r, "chain").observations().get(0).detail()).contains("+ UNIT -> INTEGRATION -> SLOW");
    }
}
