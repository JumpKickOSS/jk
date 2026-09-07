// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.rules.GuardRules;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TierPartitionTest {

    private static final String HEADER = """
            group = "t"
            name = "ws"
            version = "0.0.1"
            jdk = 25

            """;

    private static TierPartition.Table table(Path root, String body) throws IOException {
        Files.writeString(root.resolve("jk.toml"), HEADER + body);
        return TierPartition.table(root);
    }

    private static ClassFacts tagged(String simple, String[] classTags, Map<String, String[]> methodTags) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "p/" + simple, null, "java/lang/Object", null);
        for (String t : classTags) {
            AnnotationVisitor av = cw.visitAnnotation("Lorg/junit/jupiter/api/Tag;", true);
            av.visit("value", t);
            av.visitEnd();
        }
        for (Map.Entry<String, String[]> m : methodTags.entrySet()) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.getKey(), "()V", null, null);
            mv.visitAnnotation("Lorg/junit/jupiter/api/Test;", true).visitEnd();
            for (String t : m.getValue()) {
                AnnotationVisitor av = mv.visitAnnotation("Lorg/junit/jupiter/api/Tag;", true);
                av.visit("value", t);
                av.visitEnd();
            }
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "t");
    }

    @Test
    void a_well_formed_table_partitions_and_ci_is_the_fast_tier_under_another_name(@TempDir Path root)
            throws Exception {
        TierPartition.Table t = table(root, """
                [test]
                exclude-tags = ["slow", "network"]

                [profiles.ci]
                exclude-tags = ["slow", "network"]

                [profiles.slow]
                include-tags = ["slow"]
                exclude-tags = ["network"]

                [profiles.network]
                include-tags = ["network"]
                """);
        assertThat(t.tiers())
                .extracting(TierPartition.Tier::name)
                .containsExactly("jk test", "jk test --profile network", "jk test --profile slow");
        assertThat(t.vocabulary()).containsExactly("network", "slow");
        assertThat(TierPartition.partitionFaults(t)).isEmpty();
    }

    @Test
    void a_tag_excluded_by_test_and_included_by_no_profile_is_a_hole_named_by_subset(@TempDir Path root)
            throws Exception {
        TierPartition.Table t = table(root, """
                [test]
                exclude-tags = ["slow", "bench"]

                [profiles.slow]
                include-tags = ["slow"]
                """);
        List<Fault> faults = TierPartition.partitionFaults(t);
        assertThat(faults).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("tiers");
            assertThat(f.observed()).contains("@Tag[bench] — no tier runs it").doesNotContain("@Tag[bench, slow]");
            assertThat(f.instead()).contains("exactly one tier");
        });
    }

    @Test
    void two_profiles_including_the_same_tag_double_charge_it(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test]
                exclude-tags = ["slow"]

                [profiles.a]
                include-tags = ["slow"]

                [profiles.b]
                include-tags = ["slow"]
                """);
        assertThat(TierPartition.partitionFaults(t)).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("@Tag[slow] — run by 2 tiers: [jk test --profile a, jk test --profile b]"));
    }

    @Test
    void no_test_table_or_no_vocabulary_has_nothing_to_partition(@TempDir Path root) throws Exception {
        assertThat(TierPartition.table(root)).isEqualTo(TierPartition.Table.EMPTY);
        TierPartition.Table t = table(root, "[test]\nworkers = 2\n");
        assertThat(t.vocabulary()).isEmpty();
        assertThat(TierPartition.partitionFaults(t)).isEmpty();
        assertThat(TierPartition.tagFaults(t, index(tagged("T", new String[] {"whatever"}, Map.of())), "m"))
                .isEmpty();
    }

    @Test
    void compiled_tags_must_be_owned_and_run_by_one_tier_with_fixtures_allowed(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test]
                exclude-tags = ["slow", "network"]

                [profiles.slow]
                include-tags = ["slow"]
                exclude-tags = ["network"]

                [profiles.network]
                include-tags = ["network"]
                exclude-tags = ["slow"]
                """);
        ClassFacts fine = tagged("FineTest", new String[] {"slow"}, Map.of("a", new String[0]));
        ClassFacts typo = tagged("TypoTest", new String[] {"slwo"}, Map.of());
        ClassFacts method = tagged("MethodTest", new String[0], Map.of("b", new String[] {"nightly"}));
        ClassFacts fixture =
                tagged("LauncherPathTest", new String[0], Map.of("c", new String[] {"[slow]", "brackets"}));
        List<Fault> faults = TierPartition.tagFaults(t, index(fine, typo, method, fixture), "m");
        // an unowned tag still runs in the fast tier (nothing excludes it), so it is unowned, not orphaned
        assertThat(faults).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("[nightly, slwo]")
                .doesNotContain("FineTest")
                .doesNotContain("LauncherPathTest")
                .doesNotContain("[slow]")
                .doesNotContain("brackets"));
        // a class tag and a method tag route together, as JUnit filters them
        ClassFacts both = tagged("BothTest", new String[] {"slow"}, Map.of("d", new String[] {"network"}));
        assertThat(TierPartition.tagFaults(t, index(both), "")).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("p.BothTest#d @Tag[network, slow] — no tier runs it"));
    }

    @Test
    void a_rule_may_not_take_a_validation_code(@TempDir Path root) throws Exception {
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE),
                "[guards.tiers]\nkind = \"text\"\nmatch = \"x\"\nwhy = \"w\"\n");
        LoadResult load = GuardRules.load(root, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).isTrue();
        assertThat(load.problems().get(0).render())
                .contains("engine validation")
                .contains("jk guard explain tiers");
        assertThat(EngineValidations.render(new Fault("tiers", "o", "i")))
                .startsWith("GUARD tiers  engine validation\n  Observed: o\n  Instead:  i\n  Why:      ")
                .endsWith("Explain:  jk guard explain tiers");
    }
}
