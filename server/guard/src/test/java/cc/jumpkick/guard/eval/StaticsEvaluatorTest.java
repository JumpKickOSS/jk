// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Statics;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A static field holding mutable state is a managed holder, an allowed field, or a violation. */
class StaticsEvaluatorTest {

    private static final String STATICS = Statics.class.getName();

    private static Evaluation run(Path dir, String body) throws Exception {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE), "[guards.r]\nkind = \"statics\"\nwhy = \"w\"\n" + body);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule("r").orElseThrow();
        ClassFacts c = FactsExtractor.extract(FixtureBytes.of(Statics.class));
        FactsIndex idx = new FactsIndex(Map.of(c.name(), c), Map.of(), "");
        EvalContext ctx = new EvalContext(
                Lane.MODULE, dir, "m", dir.resolve("m"), List.of(dir.resolve("m")), () -> idx, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void a_mutable_collection_a_reference_array_and_a_lazily_filled_field_are_state(@TempDir Path dir)
            throws Exception {
        Evaluation e = run(dir, "");

        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .extracting(Observation::key)
                .as("an unmodifiable factory's value, a pattern and a constant are not state")
                .containsExactlyInAnyOrder(STATICS + "#MUTABLE", STATICS + "#ARRAY", STATICS + "#lazy");
    }

    @Test
    void a_managed_initializer_passes_and_an_allow_names_the_field(@TempDir Path dir) throws Exception {
        Evaluation e = run(dir, """
                managed = ["java.util.concurrent.ConcurrentHashMap#<init>"]

                [[guards.r.allow]]
                in     = "%1$s#ARRAY"
                reason = "content-keyed: never written"

                [[guards.r.allow]]
                in     = "%1$s#lazy"
                reason = "live registry: the one instance"
                """.formatted(STATICS));

        assertThat(e.outcome()).isEqualTo(Outcome.CLEAN);
    }

    @Test
    void an_allow_on_a_field_that_holds_no_state_is_stale(@TempDir Path dir) throws Exception {
        Evaluation e = run(dir, """
                managed = ["java.util.concurrent.ConcurrentHashMap#<init>"]
                holders = ["java.lang.Object"]

                [[guards.r.allow]]
                in     = "%1$s#FIXED"
                reason = "content-keyed: constant"

                [[guards.r.allow]]
                in     = "%1$s#ARRAY"
                reason = "content-keyed: never written"
                """.formatted(STATICS));

        assertThat(e.outcome()).isEqualTo(Outcome.STALE_ALLOW);
        assertThat(e.note()).contains(STATICS + "#FIXED");
        assertThat(e.observations()).extracting(Observation::key).containsExactly(STATICS + "#lazy");
    }

    @Test
    void an_allow_reason_outside_the_vocabulary_fails_the_rule(@TempDir Path dir) throws Exception {
        Evaluation e = run(dir, """
                reasons = ["live registry:", "content-keyed:"]

                [[guards.r.allow]]
                in     = "%1$s#ARRAY"
                reason = "it is fine"
                """.formatted(STATICS));

        assertThat(e.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(e.note()).contains("`live registry:`");
    }
}
