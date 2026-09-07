// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.baseline.Reconciliation;
import cc.jumpkick.guard.baseline.RuleBaseline;
import cc.jumpkick.guard.rules.Rule;
import cc.jumpkick.guard.rules.RuleSource;
import cc.jumpkick.guard.schema.Kind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

class GuardThrashTest {

    @BeforeEach
    @AfterEach
    void fresh() {
        GuardThrash.reset();
    }

    private static RuleReport report(String... fingerprints) {
        Rule rule = new Rule(
                "r",
                Kind.TEXT,
                "w",
                "i",
                List.of(),
                "main",
                List.of(),
                false,
                null,
                Toml.parse("kind = \"text\"\n"),
                new RuleSource(Path.of("jk-guards.toml"), 1, RuleSource.Layer.ROOT));
        List<Observation> fresh = new ArrayList<>();
        for (String f : fingerprints) fresh.add(Observation.site(f, "F.java", 1, "d"));
        Reconciliation rec =
                new Reconciliation("r", "", fresh, List.of(), List.of(), RuleBaseline.EMPTY, false, null, Map.of());
        Evaluation ev = Evaluation.of(Map.of("files", 1L), fresh);
        return new RuleReport(rule, Outcome.VIOLATIONS, ev, rec, "");
    }

    @Test
    void a_site_red_twice_in_a_row_reaches_the_threshold_and_a_gap_resets_it() {
        String lane = "guard@ws";
        assertThat(GuardThrash.record(lane, List.of(report("a")))).containsEntry(GuardThrash.key("r", "a"), 1);
        Map<String, Integer> second = GuardThrash.record(lane, List.of(report("a", "b")));
        assertThat(second).containsEntry(GuardThrash.key("r", "a"), 2).containsEntry(GuardThrash.key("r", "b"), 1);
        assertThat(second.get(GuardThrash.key("r", "a"))).isGreaterThanOrEqualTo(GuardThrash.THRESHOLD);
        // a run without `a` breaks the streak; the next appearance starts over
        assertThat(GuardThrash.record(lane, List.of(report("b"))))
                .containsEntry(GuardThrash.key("r", "b"), 2)
                .doesNotContainKey(GuardThrash.key("r", "a"));
        assertThat(GuardThrash.record(lane, List.of(report("a")))).containsEntry(GuardThrash.key("r", "a"), 1);
        // another lane keeps its own count
        assertThat(GuardThrash.record("guard@other", List.of(report("a")))).containsEntry(GuardThrash.key("r", "a"), 1);
        GuardThrash.reset();
        assertThat(GuardThrash.record(lane, List.of(report("a")))).containsEntry(GuardThrash.key("r", "a"), 1);
    }

    @Test
    void the_message_switches_from_exempt_to_thrash_at_the_threshold() {
        RuleReport r = report("a");
        String first = GuardMessages.site(r, r.fresh().get(0), 1);
        assertThat(first)
                .contains("Exempt:   ask the user to add [guards.r].allow")
                .doesNotContain("Thrash:");
        String second = GuardMessages.site(r, r.fresh().get(0), 2);
        assertThat(second)
                .contains("Thrash:   this site has failed on 2 consecutive builds — stop and ask the user")
                .doesNotContain("Exempt:")
                .endsWith("Explain:  jk guard explain r");
    }
}
