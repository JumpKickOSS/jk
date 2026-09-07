// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
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

class ParityEvaluatorTest {

    private static void files(Path root) throws IOException {
        Files.writeString(root.resolve("left.toml"), "[m]\na = 1\nb = 2\nonly-left = 3\n");
        Files.writeString(root.resolve("right.toml"), "[m]\na = 1\nb = 2\nonly-right = 3\n");
    }

    private static Map<String, Evaluation> run(Path root, String rules) throws Exception {
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        EvalContext ctx = new EvalContext(
                        Lane.TREE, root, "", null, List.of(), () -> FactsIndex.EMPTY, () -> null, List::of)
                .withRules(load.rules());
        return LaneRun.evaluate(LaneRun.rulesFor(Lane.TREE, load.rules(), ""), ctx);
    }

    private static Evaluation ev(Map<String, Evaluation> r, String id) {
        return Objects.requireNonNull(r.get(id), id);
    }

    @Test
    void both_directions_fire_and_left_in_right_ignores_the_other_side(@TempDir Path root) throws Exception {
        files(root);
        Map<String, Evaluation> r = run(root, """
                [guards.both]
                kind  = "parity"
                left  = { toml-keys = { file = "left.toml", table = "m" } }
                right = { toml-keys = { file = "right.toml", table = "m" } }
                why   = "w"
                [guards.one-way]
                kind      = "parity"
                left      = { toml-keys = { file = "left.toml", table = "m" } }
                right     = { toml-keys = { file = "right.toml", table = "m" } }
                direction = "left-in-right"
                why       = "w"
                """);
        Evaluation both = ev(r, "both");
        assertThat(both.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(both.observations().stream().map(Observation::key))
                .containsExactly("left-only:only-left", "right-only:only-right");
        Observation first = both.observations().get(0);
        assertThat(first.file()).isEqualTo("left.toml");
        assertThat(first.detail())
                .contains("`only-left` is in toml-keys(left.toml, m) and not in toml-keys(right.toml, m)");
        assertThat(both.population()).containsEntry("left", 3L).containsEntry("right", 3L);
        assertThat(both.bites()).isTrue();
        Evaluation oneWay = ev(r, "one-way");
        assertThat(oneWay.observations().stream().map(Observation::key)).containsExactly("left-only:only-left");
    }

    @Test
    void allow_with_a_reason_excuses_an_element_and_an_unused_allow_is_stale(@TempDir Path root) throws Exception {
        files(root);
        Map<String, Evaluation> r = run(root, """
                [guards.excused]
                kind  = "parity"
                left  = { toml-keys = { file = "left.toml", table = "m" } }
                right = { toml-keys = { file = "right.toml", table = "m" } }
                allow = [{ in = "only-*", reason = "one-sided by design" }]
                why   = "w"
                [guards.stale]
                kind  = "parity"
                left  = { toml-keys = { file = "left.toml", table = "m" } }
                right = { toml-keys = { file = "left.toml", table = "m" } }
                allow = [{ in = "nothing-here", reason = "gone" }]
                why   = "w"
                """);
        assertThat(ev(r, "excused").outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation stale = ev(r, "stale");
        assertThat(stale.outcome()).isEqualTo(Outcome.STALE_ALLOW);
        assertThat(stale.note()).contains("nothing-here");
    }

    @Test
    void an_empty_side_is_blind_and_an_unreadable_side_is_scanner_failed(@TempDir Path root) throws Exception {
        files(root);
        Files.writeString(root.resolve("empty.toml"), "[m]\n");
        Map<String, Evaluation> r = run(root, """
                [guards.blind]
                kind  = "parity"
                left  = { toml-keys = { file = "left.toml", table = "m" } }
                right = { toml-keys = { file = "empty.toml", table = "m" } }
                why   = "w"
                [guards.missing]
                kind  = "parity"
                left  = { toml-keys = { file = "left.toml", table = "m" } }
                right = { toml-keys = { file = "absent.toml", table = "m" } }
                why   = "w"
                """);
        assertThat(ev(r, "blind").outcome()).isEqualTo(Outcome.BLIND);
        Evaluation missing = ev(r, "missing");
        assertThat(missing.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(missing.note()).contains("right: absent.toml does not exist");
    }
}
