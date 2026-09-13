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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VocabularyEvaluatorTest {

    private static final String OWNER = Sample.class.getName();

    private static Path module(Path root) throws IOException {
        Path m = root.resolve("mod");
        Path src = Files.createDirectories(m.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Uses.java"), """
                package x;
                class Uses {
                    String a = "compile-main";      // the owner's STEP, re-typed
                    String b = "hidden";            // NOT_PUBLIC, re-typed
                    String c = "run-tests";         // hyphenated, no owner
                    String d = "just words";
                    String e = "sha256";            // a homonym
                }
                """);
        Files.writeString(src.resolve("Other.java"), "package x;\nclass Other { String f = \"compile-main\"; }\n");
        Files.createDirectories(m.resolve("src/test/java/x"));
        Files.writeString(
                m.resolve("src/test/java/x/T.java"), "package x;\nclass T { String g = \"compile-main\"; }\n");
        // The owner's own source is exempt.
        Path ownerSrc = Files.createDirectories(m.resolve("src/main/java/cc/jumpkick/guard/extract/fixture"));
        Files.writeString(
                ownerSrc.resolve("Sample.java"),
                "package cc.jumpkick.guard.extract.fixture;\nclass Sample { static final String STEP = \"compile-main\"; }\n");
        return m;
    }

    private static Evaluation run(Path root, String body) throws Exception {
        Path m = module(root);
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.v]\nkind = \"vocabulary\"\nwhy = \"w\"\ninstead = \"Owner.X\"\n" + body);
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule("v").orElseThrow();
        ClassFacts s = FactsExtractor.extract(FixtureBytes.of(Sample.class));
        ClassFacts tier = FactsExtractor.extract(FixtureBytes.of(Tier.class));
        FactsIndex idx = new FactsIndex(Map.of(s.name(), s, tier.name(), tier), Map.of(), "");
        EvalContext ctx = new EvalContext(Lane.MODULE, root, "mod", m, List.of(m), () -> idx, () -> null, List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void owner_constants_are_banned_as_literals_outside_the_owner(@TempDir Path root) throws Exception {
        Evaluation e = run(root, "owner = \"" + OWNER + "\"\n");
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations())
                .extracting(Observation::file)
                .containsExactlyInAnyOrder(
                        "src/main/java/x/Uses.java", "src/main/java/x/Uses.java", "src/main/java/x/Other.java");
        assertThat(e.observations())
                .extracting(Observation::detail)
                .anySatisfy(d -> assertThat(d).contains("Sample.STEP"))
                .anySatisfy(d -> assertThat(d).contains("Sample.NOT_PUBLIC"));
        assertThat(e.population()).containsEntry("constants", 2L);
    }

    @Test
    void hyphenated_shape_with_the_inverse_arm_reports_unowned_names(@TempDir Path root) throws Exception {
        Evaluation e = run(
                root, "owner = \"" + OWNER + "\"\nshape = \"hyphenated\"\ninverse = true\nhomonyms = [\"sha256\"]\n");
        assertThat(e.observations())
                .extracting(Observation::key)
                .contains("unowned | run-tests")
                .noneMatch(k -> k.contains("hidden"));
        assertThat(e.observations())
                .filteredOn(o -> o.key().startsWith("unowned"))
                .hasSize(1);
        Evaluation all = run(root, "owner = \"" + OWNER + "\"\nshape = \"hyphenated\"\nsource-set = \"all\"\n");
        assertThat(all.observations()).extracting(Observation::file).contains("src/test/java/x/T.java");
    }

    @Test
    void enum_constructor_literals_are_the_vocabulary_of_an_enum_owner(@TempDir Path root) throws Exception {
        ClassFacts tier = FactsExtractor.extract(FixtureBytes.of(Tier.class));
        assertThat(VocabularyEvaluator.constants(tier))
                .containsKeys("sha256", "action-cache")
                .containsValue("<enum constant>");
    }

    @Test
    void a_missing_or_empty_owner_is_owner_missing_and_allow_exempts(@TempDir Path root) throws Exception {
        assertThat(run(root, "owner = \"x.Nope\"\n").outcome()).isEqualTo(Outcome.OWNER_MISSING);
        assertThat(run(root, "owner = \"" + OWNER + "\"\nshape = \"regex:zzz\"\n")
                        .outcome())
                .isEqualTo(Outcome.OWNER_MISSING);
        Evaluation allowed = run(
                root, "owner = \"" + OWNER + "\"\nallow = [{ in = \"src/main/java/x/**\", reason = \"fixture\" }]\n");
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
    }

    @Test
    void a_tree_context_spells_an_out_of_root_member_by_its_workspace_relative_path(@TempDir Path dir)
            throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Path m = module(root);
        Path sib = Files.createDirectories(dir.resolve("sib/src/main/java/x"));
        Files.writeString(sib.resolve("S.java"), "package x;\nclass S { String a = \"compile-main\"; }\n");
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.v]\nkind = \"vocabulary\"\nwhy = \"w\"\ninstead = \"Owner.X\"\nowner = \"" + OWNER + "\"\n");
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule("v").orElseThrow();
        ClassFacts s = FactsExtractor.extract(FixtureBytes.of(Sample.class));
        FactsIndex idx = new FactsIndex(Map.of(s.name(), s), Map.of(), "");
        List<Path> modules = List.of(m, dir.resolve("sib"));
        EvalContext ctx = new EvalContext(Lane.TREE, root, "", null, modules, () -> idx, () -> null, List::of);
        Evaluation e = Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
        assertThat(e.observations())
                .extracting(Observation::file)
                .contains("../sib/src/main/java/x/S.java", "mod/src/main/java/x/Uses.java");
    }
}
