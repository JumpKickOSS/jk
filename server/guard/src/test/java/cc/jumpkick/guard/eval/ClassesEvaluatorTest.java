// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
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
import cc.jumpkick.guard.schema.Lane;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassesEvaluatorTest {

    private static final String FIXTURE = "cc.jumpkick.guard.extract.fixture";

    private static FactsIndex facts(Class<?>... classes) throws IOException {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (Class<?> c : classes) {
            ClassFacts f = FactsExtractor.extract(FixtureBytes.of(c));
            m.put(f.name(), f);
        }
        return new FactsIndex(m, Map.of(), "");
    }

    private static Rule rule(Path dir, String body) throws IOException {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE), "[guards.r]\nkind = \"classes\"\nwhy = \"w\"\n" + body);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    private static Evaluation run(Path dir, String body, FactsIndex idx) throws Exception {
        Rule rule = rule(dir, body);
        EvalContext ctx = new EvalContext(
                Lane.MODULE, dir, "m", dir.resolve("m"), List.of(dir.resolve("m")), () -> idx, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void that_selects_and_should_asserts_each_predicate_per_class(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class, Sample.Inner.class, Tier.class);
        Evaluation ok = run(
                dir,
                "that = { named = \"Sample\" }\nshould = { implement = \"java.util.function.Supplier\", be = [\"final\", \"!abstract\", \"top-level\"], reside-in = \"..fixture..\" }\n",
                idx);
        assertThat(ok.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(ok.population()).containsEntry("classes", 1L);

        Evaluation bad = run(
                dir,
                "that = { named = \"Sample\" }\nshould = { have-only-final-fields = true, have-only-private-constructors = true, have-simple-name-ending-with = \"Impl\" }\n",
                idx);
        assertThat(bad.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(bad.observations())
                .extracting(Observation::key)
                .containsExactlyInAnyOrder(
                        FIXTURE + ".Sample | have-only-final-fields = \"true\"",
                        FIXTURE + ".Sample | have-only-private-constructors = \"true\"",
                        FIXTURE + ".Sample | have-simple-name-ending-with = \"Impl\"");
        assertThat(bad.observations())
                .extracting(Observation::detail)
                .anyMatch(d -> d.contains("field field is not final"));
        assertThat(bad.observations().get(0).file())
                .isEqualTo("m/src/main/java/cc/jumpkick/guard/extract/fixture/Sample.java");

        Evaluation negated =
                run(dir, "that = { named = \"Sample\" }\nshould = { have-only-final-fields = false }\n", idx);
        assertThat(negated.outcome()).isEqualTo(Outcome.CLEAN);
    }

    @Test
    void dependencies_and_access(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class, Sample.Inner.class, Tier.class, ClassesEvaluatorTest.class);
        Evaluation depends =
                run(dir, "that = { named = \"Sample\" }\nshould = { not-depend-on = \"java.security..\" }\n", idx);
        assertThat(depends.observations()).singleElement().satisfies(o -> assertThat(o.detail())
                .contains("depends on java.security.MessageDigest"));
        Evaluation depOk =
                run(dir, "that = { named = \"Sample\" }\nshould = { not-depend-on = \"javax.swing..\" }\n", idx);
        assertThat(depOk.outcome()).isEqualTo(Outcome.CLEAN);

        // This test class references Sample from another package: an outsider.
        Evaluation access = run(
                dir, "that = { named = \"Sample\" }\nshould = { only-be-accessed-by = \"" + FIXTURE + ".**\" }\n", idx);
        assertThat(access.observations()).singleElement().satisfies(o -> assertThat(o.detail())
                .contains("accessed by cc.jumpkick.guard.eval.ClassesEvaluatorTest"));
        Evaluation accessOk = run(
                dir,
                "that = { named = \"Sample\" }\nshould = { only-be-accessed-by = \"cc.jumpkick.guard.eval.*\" }\n",
                idx);
        assertThat(accessOk.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(Evaluators.laneOf(
                        rule(dir, "that = { named = \"Sample\" }\nshould = { only-be-accessed-by = \"x.*\" }\n")))
                .as("who accesses a class is a workspace question")
                .isEqualTo(Lane.WORKSPACE);
        assertThat(Evaluators.laneOf(rule(dir, "that = { named = \"Sample\" }\nshould = { be = \"final\" }\n")))
                .isEqualTo(Lane.MODULE);
    }

    @Test
    void a_misspelt_shape_is_the_rules_error_not_a_violation_per_class(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class, Sample.Inner.class, Tier.class);
        Evaluation typo = run(dir, "that = { named = \"*\" }\nshould = { be = \"finall\" }\n", idx);
        assertThat(typo.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(typo.note()).contains("be = \"finall\"").contains("final");
        assertThat(typo.observations()).isEmpty();
        Evaluation negated = run(dir, "that = { named = \"*\" }\nshould = { be = \"!finall\" }\n", idx);
        assertThat(negated.outcome()).as("a negated typo is not a pass").isEqualTo(Outcome.SCANNER_FAILED);
        Evaluation modifier = run(dir, "that = { named = \"*\" }\nshould = { have-modifier = \"sealed\" }\n", idx);
        assertThat(modifier.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(modifier.note()).contains("have-modifier = \"sealed\"");
    }

    @Test
    void the_closed_sets_are_closed_and_an_empty_that_is_blind(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class, Tier.class);
        Evaluation unknown = run(dir, "that = { named = \"Sample\" }\nshould = { colour = \"red\" }\n", idx);
        assertThat(unknown.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(unknown.note()).contains("unknown predicate `colour`").contains("not-depend-on");
        Evaluation thatUnknown = run(dir, "that = { smells = \"bad\" }\nshould = { be = \"final\" }\n", idx);
        assertThat(thatUnknown.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        Evaluation blind = run(dir, "that = { named = \"Nothing*\" }\nshould = { be = \"final\" }\n", idx);
        assertThat(blind.outcome()).as("the allowEmptyShould(false) lesson").isEqualTo(Outcome.BLIND);
        Evaluation regex = run(
                dir,
                "that = { name-matching = \".*\\\\.Tier\", are = \"enum\" }\nshould = { be = \"enum\", simple-name-ending-with = \"er\" }\n",
                idx);
        assertThat(regex.outcome()).isEqualTo(Outcome.SCANNER_FAILED); // simple-name-ending-with is a `that` predicate
        Evaluation regexOk = run(
                dir,
                "that = { name-matching = \".*\\\\.Tier\", are = \"enum\" }\nshould = { be = \"enum\", have-simple-name-ending-with = \"er\" }\n",
                idx);
        assertThat(regexOk.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(rule(dir, "that = { are = \"enum\" }\nshould = { be = \"final\" }\n")
                        .instead())
                .isEqualTo("make the class satisfy: be = final");
    }
}
