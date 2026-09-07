// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Sample;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForbidEvaluatorTest {

    private static FactsIndex facts() throws IOException {
        ClassFacts s = FactsExtractor.extract(FixtureBytes.of(Sample.class));
        ClassFacts inner = FactsExtractor.extract(FixtureBytes.of(Sample.Inner.class));
        return new FactsIndex(Map.of(s.name(), s, inner.name(), inner), Map.of(), "");
    }

    private static Evaluation run(Path dir, String ruleBody) throws Exception {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE),
                "[guards.r]\nkind = \"forbid\"\nwhy = \"w\"\ninstead = \"i\"\n" + ruleBody);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule("r").orElseThrow();
        FactsIndex idx = facts();
        EvalContext ctx = new EvalContext(
                Lane.MODULE, dir, "m", dir.resolve("m"), List.of(dir.resolve("m")), () -> idx, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void a_method_ban_matches_by_descriptor_and_names_the_site(@TempDir Path dir) throws Exception {
        Evaluation e = run(dir, "signatures = [\"java.security.MessageDigest#getInstance(java.lang.String)\"]\n");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key())
                    .isEqualTo(
                            "cc.jumpkick.guard.extract.fixture.Sample#digest()Ljava/security/MessageDigest; -> java.security.MessageDigest#getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;");
            assertThat(o.file()).isEqualTo("m/src/main/java/cc/jumpkick/guard/extract/fixture/Sample.java");
            assertThat(o.line()).isGreaterThan(0);
            assertThat(o.detail()).contains("MessageDigest.getInstance(\"SHA-256\")");
        });
        assertThat(e.population()).containsEntry("classes", 2L);
    }

    @Test
    void a_class_is_never_outside_itself(@TempDir Path dir) throws Exception {
        // Banning the fixture type: Sample's own members and Inner's reads of Sample are not sites.
        Evaluation self = run(dir, "signatures = [\"cc.jumpkick.guard.extract.fixture.Sample\"]\n");
        assertThat(self.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(self.observations()).isEmpty();
    }

    @Test
    void bite_evidence_is_an_owner_site_or_a_current_site(@TempDir Path dir) throws Exception {
        Evaluation none = run(dir, "signatures = [\"java.util.UUID#randomUUID()\"]\n");
        assertThat(none.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(none.bites())
                .as("resolves, examined 2 classes, matched nothing: no evidence")
                .isFalse();
        Evaluation site = run(dir, "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\n");
        assertThat(site.bites()).isTrue();
        Evaluation owner = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nowner = \"cc.jumpkick.guard.extract.fixture.Sample\"\n");
        assertThat(owner.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(owner.bites()).as("the owner itself uses the primitive").isTrue();
    }

    @Test
    void any_overload_types_and_package_trees(@TempDir Path dir) throws Exception {
        assertThat(run(dir, "signatures = [\"java.lang.String#toLowerCase(**)\"]\n")
                        .observations())
                .hasSize(1);
        assertThat(run(dir, "signatures = [\"java.lang.String#toLowerCase()\"]\n")
                        .observations())
                .as("no-arg overload is not called")
                .isEmpty();
        Evaluation type = run(dir, "signatures = [\"java.util.function.Supplier\"]\n");
        assertThat(type.observations()).anySatisfy(o -> assertThat(o.key())
                .isEqualTo("cc.jumpkick.guard.extract.fixture.Sample -> java.util.function.Supplier"));
        assertThat(run(dir, "signatures = [\"java.security.**\"]\n").observations())
                .isNotEmpty();
    }

    @Test
    void the_args_peephole_fires_only_on_the_named_literal(@TempDir Path dir) throws Exception {
        assertThat(run(dir, "signatures = [\"java.lang.System#getProperty(java.lang.String)\"]\nargs = [\"os.name\"]\n")
                        .observations())
                .hasSize(1);
        assertThat(run(
                                dir,
                                "signatures = [\"java.lang.System#getProperty(java.lang.String)\"]\nargs = [\"user.home\"]\n")
                        .outcome())
                .isEqualTo(Outcome.CLEAN);
        assertThat(run(dir, "signatures = [\"java.lang.System#getProperty(java.lang.String)\"]\nargs = [\"os.*\"]\n")
                        .observations())
                .hasSize(1);
    }

    @Test
    void the_owner_is_exempt_and_probed(@TempDir Path dir) throws Exception {
        Evaluation inOwner = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nowner = \"cc.jumpkick.guard.extract.fixture.Sample\"\n");
        assertThat(inOwner.outcome()).as("the only site is inside the owner").isEqualTo(Outcome.CLEAN);
        Evaluation probe = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nowner = \"cc.jumpkick.guard.extract.fixture.Sample$Inner\"\n");
        assertThat(probe.outcome()).isEqualTo(Outcome.OWNER_MISSING);
        assertThat(probe.note()).contains("no longer uses");
        Evaluation elsewhere = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nowner = \"cc.jumpkick.guard.extract.fixture.Missing\"\n");
        assertThat(elsewhere.outcome())
                .as("an owner named in this module's package but absent")
                .isEqualTo(Outcome.OWNER_MISSING);
    }

    @Test
    void allow_exempts_and_a_stale_allow_is_red(@TempDir Path dir) throws Exception {
        Evaluation allowed = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nallow = [{ in = \"cc.jumpkick.guard.extract.fixture.**\", reason = \"fixture\" }]\n");
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation stale = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nallow = [{ in = \"cc.jumpkick.guard.extract.fixture.Sample$Inner\", reason = \"nothing here\" }]\n");
        assertThat(stale.outcome())
                .as("names a class of this module that has no site")
                .isEqualTo(Outcome.STALE_ALLOW);
        Evaluation elsewhere = run(
                dir,
                "signatures = [\"java.security.MessageDigest#getInstance(**)\"]\nallow = [{ in = \"other/module\", reason = \"not here\" }]\n");
        assertThat(elsewhere.outcome())
                .as("an allow for another module is judged in that module, not stale here")
                .isEqualTo(Outcome.VIOLATIONS);
        assertThat(stale.observations())
                .as("the violation is still reported alongside")
                .hasSize(1);
    }

    @Test
    void a_typo_is_red_and_bundled_sets_resolve(@TempDir Path dir) throws Exception {
        // A type this module cannot see is nothing this module can reference: clean here, with no
        // bite evidence and the reason in the note — the tree lane reports the typo once no module bit.
        Evaluation typo = run(dir, "signatures = [\"java.security.MesageDigest#getInstance(**)\"]\n");
        assertThat(typo.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(typo.bites()).isFalse();
        assertThat(typo.note()).contains("does not resolve");
        Evaluation sysout = run(dir, "signatures = [\"@jdk-system-out\"]\n");
        assertThat(sysout.observations()).anySatisfy(o -> assertThat(o.key()).contains("java.lang.System#out"));
        Evaluation unsafe = run(dir, "signatures = [\"@jdk-unsafe\"]\n");
        assertThat(unsafe.observations())
                .as("toLowerCase(Locale) is the safe overload")
                .noneMatch(o -> o.key().contains("toLowerCase"));
        // JUnit 4 is not on this classpath: its type signatures drop out, its package trees still
        // evaluate (a package ban needs no class to resolve), and nothing here references them.
        Evaluation framework = run(dir, "signatures = [\"@junit4\"]\n");
        assertThat(framework.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation unknown = run(dir, "signatures = [\"@nope\"]\n");
        assertThat(unknown.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
    }

    @Test
    void except_annotated_exempts_the_origin(@TempDir Path dir) throws Exception {
        Evaluation e = run(
                dir, "signatures = [\"java.lang.String#length()\"]\nexcept-annotated = [\"java.lang.Deprecated\"]\n");
        assertThat(e.observations())
                .extracting(Observation::key)
                .allMatch(k -> k.startsWith("cc.jumpkick.guard.extract.fixture.Sample$Inner#read"));
        assertThat(e.observations()).isNotEmpty();
    }
}
