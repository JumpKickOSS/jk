// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Baseline;
import cc.jumpkick.guard.facts.ClassFacts;
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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A first red on a baseline rule tells the reader how today's sites are accepted. */
class GuardMessagesAcceptTest {

    private static Rule rule(Path dir, String body) throws Exception {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE),
                "[guards.r]\nkind = \"forbid\"\nwhy = \"w\"\ninstead = \"a bounded cache\"\n" + body);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    private static RuleReport report(Path dir, Rule rule) {
        ClassFacts user = new ClassFacts(
                "p/User",
                1,
                "java/lang/Object",
                List.of(),
                "User.java",
                List.of(),
                List.of(),
                List.of(),
                Set.of("java/lang/ref/SoftReference"));
        FactsIndex idx = new FactsIndex(Map.of(user.name(), user), Map.of(), "");
        EvalContext ctx = new EvalContext(
                Lane.MODULE, dir, "m", dir.resolve("m"), List.of(dir.resolve("m")), () -> idx, () -> null, List::of);
        return LaneRun.run(Lane.MODULE, List.of(rule), ctx, Baseline.EMPTY)
                .reports()
                .getFirst();
    }

    @Test
    void a_baseline_rule_s_first_red_names_the_freeze(@TempDir Path dir) throws Exception {
        RuleReport r = report(dir, rule(dir, "signatures = [\"java.lang.ref.SoftReference\"]\nbaseline = true\n"));
        assertThat(r.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(GuardMessages.render(r)).contains("Accept:   jk guard freeze r --reason");
    }

    @Test
    void a_rule_without_a_baseline_offers_only_the_exemption(@TempDir Path dir) throws Exception {
        RuleReport r = report(dir, rule(dir, "signatures = [\"java.lang.ref.SoftReference\"]\n"));
        assertThat(r.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(GuardMessages.render(r)).doesNotContain("Accept:").contains("Exempt:");
    }
}
