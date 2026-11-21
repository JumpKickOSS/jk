// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.validate;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.FactsExtractor;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.TestClassFacts;
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
import org.jspecify.annotations.Nullable;
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
            jdk = { version = "25" }
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

    /**
     * A class {@code p/<simple>} extending {@code p/<superSimple>} (or Object when null), wearing the
     * annotation types {@code p/<annotation>} on itself and, when {@code method} is not null, on a
     * test method of that name.
     */
    private static ClassFacts composed(
            String simple,
            @Nullable String superSimple,
            String[] classAnnotations,
            @Nullable String method,
            String[] methodAnnotations) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                "p/" + simple,
                null,
                superSimple == null ? "java/lang/Object" : "p/" + superSimple,
                null);
        for (String a : classAnnotations)
            cw.visitAnnotation("Lp/" + a + ";", true).visitEnd();
        if (method != null) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()V", null, null);
            mv.visitAnnotation("Lorg/junit/jupiter/api/Test;", true).visitEnd();
            for (String a : methodAnnotations)
                mv.visitAnnotation("Lp/" + a + ";", true).visitEnd();
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    /** An annotation type {@code p/<simple>} meta-annotated {@code @Tag(tag)} and with each {@code p/<meta>}. */
    private static ClassFacts tagAnnotation(String simple, String tag, String... metas) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION,
                "p/" + simple,
                null,
                "java/lang/Object",
                new String[] {"java/lang/annotation/Annotation"});
        AnnotationVisitor av = cw.visitAnnotation("Lorg/junit/jupiter/api/Tag;", true);
        av.visit("value", tag);
        av.visitEnd();
        for (String meta : metas) cw.visitAnnotation("Lp/" + meta + ";", true).visitEnd();
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    private static List<Fault> faults(TierPartition.Table t, FactsIndex tests, String module) {
        return faults(t, tests, index(), module);
    }

    /** {@code beyond} the project's classes outside the tests, a main source set's or a sibling module's. */
    private static List<Fault> faults(TierPartition.Table t, FactsIndex tests, FactsIndex beyond, String module) {
        return faults(t, tests, beyond, index(), module);
    }

    /** {@code library} the jars the tests compile against, read only from outside the indexes. */
    private static List<Fault> faults(
            TierPartition.Table t, FactsIndex tests, FactsIndex beyond, FactsIndex library, String module) {
        List<FactsIndex> project = List.of(tests, beyond);
        return TierPartition.tagFaults(
                t, tests, new TestClassFacts(project, library::classNamed), new TestClassFacts(project), module);
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "t");
    }

    @Test
    void a_well_formed_table_partitions_with_the_default_suite_as_the_fast_tier(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.integration]
                exclude-tags = ["slow", "network"]

                [test.suites.slow]
                tags = ["slow"]
                exclude-tags = ["network"]

                [test.suites.network]
                tags = ["network"]
                """);
        assertThat(t.tiers())
                .extracting(TierPartition.Tier::name)
                .containsExactly("jk test", "jk test -s integration", "jk test -s slow", "jk test -s network");
        assertThat(t.vocabulary()).containsExactly("integration", "network", "slow");
        assertThat(TierPartition.partitionFaults(t)).isEmpty();
    }

    @Test
    void the_built_in_integration_suite_is_a_tier_once_any_table_routes_a_tag(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.slow]
                tags = ["slow"]
                """);
        assertThat(t.tiers())
                .extracting(TierPartition.Tier::name)
                .containsExactly("jk test", "jk test -s slow", "jk test -s integration");
        assertThat(TierPartition.partitionFaults(t)).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("@Tag[integration, slow] — run by 2 tiers: [jk test -s slow, jk test -s integration]"));
    }

    @Test
    void a_tag_every_claiming_suite_excludes_is_a_hole_named_by_subset(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.integration]
                exclude-tags = ["slow", "bench"]

                [test.suites.slow]
                tags = ["slow", "bench"]
                exclude-tags = ["bench"]
                """);
        List<Fault> faults = TierPartition.partitionFaults(t);
        assertThat(faults).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("tiers");
            assertThat(f.observed()).contains("@Tag[bench] — no tier runs it");
            assertThat(f.instead()).contains("exactly one suite");
        });
    }

    @Test
    void two_suites_claiming_the_same_tag_double_charge_it(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.integration]
                exclude-tags = ["slow"]

                [test.suites.a]
                tags = ["slow"]

                [test.suites.b]
                tags = ["slow"]
                """);
        assertThat(TierPartition.partitionFaults(t)).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("@Tag[slow] — run by 2 tiers: [jk test -s a, jk test -s b]"));
    }

    @Test
    void no_table_routing_a_tag_has_nothing_to_partition(@TempDir Path root) throws Exception {
        assertThat(TierPartition.table(root)).isEqualTo(TierPartition.Table.EMPTY);
        TierPartition.Table t =
                table(root, "[test]\nworkers = 2\n\n[test.suites.contract]\nclasses = [\"*Contract\"]\n");
        assertThat(t).isEqualTo(TierPartition.Table.EMPTY);
        assertThat(TierPartition.partitionFaults(t)).isEmpty();
        assertThat(faults(t, index(tagged("T", new String[] {"whatever"}, Map.of())), "m"))
                .isEmpty();
    }

    @Test
    void compiled_tags_must_be_owned_and_run_by_one_tier_with_fixtures_allowed(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.integration]
                exclude-tags = ["slow", "network"]

                [test.suites.slow]
                tags = ["slow"]
                exclude-tags = ["network"]

                [test.suites.network]
                tags = ["network"]
                exclude-tags = ["slow"]
                """);
        ClassFacts fine = tagged("FineTest", new String[] {"slow"}, Map.of("a", new String[0]));
        ClassFacts typo = tagged("TypoTest", new String[] {"slwo"}, Map.of());
        ClassFacts method = tagged("MethodTest", new String[0], Map.of("b", new String[] {"nightly"}));
        ClassFacts fixture =
                tagged("LauncherPathTest", new String[0], Map.of("c", new String[] {"[slow]", "brackets"}));
        List<Fault> faults = faults(t, index(fine, typo, method, fixture), "m");
        // an unowned tag still runs in the fast tier (no suite claims it), so it is unowned, not orphaned
        assertThat(faults).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("[nightly, slwo]")
                .doesNotContain("FineTest")
                .doesNotContain("LauncherPathTest")
                .doesNotContain("[slow]")
                .doesNotContain("brackets"));
        // a class tag and a method tag route together, as JUnit filters them
        ClassFacts both = tagged("BothTest", new String[] {"slow"}, Map.of("d", new String[] {"network"}));
        assertThat(faults(t, index(both), "")).singleElement().satisfies(f -> assertThat(f.observed())
                .contains("p.BothTest#d @Tag[network, slow] — no tier runs it"));
    }

    /** A tag reaches a test through a composed annotation and a superclass, as JUnit reads it. */
    @Test
    void composed_and_inherited_tags_are_read_as_junit_reads_them(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.integration]
                exclude-tags = ["slow", "network"]

                [test.suites.slow]
                tags = ["slow"]
                exclude-tags = ["network"]

                [test.suites.network]
                tags = ["network"]
                exclude-tags = ["slow"]
                """);
        FactsIndex annotations = index(tagAnnotation("Slow", "slow"), tagAnnotation("Nightly", "nightly"));

        ClassFacts composedTypo = composed("NightlyTest", null, new String[] {"Nightly"}, null, new String[0]);
        assertThat(faults(t, index(composedTypo), annotations, "m"))
                .as("a tag a composed annotation stands for must be owned")
                .singleElement()
                .satisfies(f -> assertThat(f.observed()).contains("[nightly]"));

        ClassFacts base = tagged("BaseTest", new String[] {"slow"}, Map.of());
        ClassFacts sub = composed("SubTest", "BaseTest", new String[0], null, new String[0]);
        ClassFacts network = tagged("NetworkTest", new String[] {"network"}, Map.of());
        ClassFacts subNetwork = composed("SubNetworkTest", "NetworkTest", new String[0], "x", new String[] {"Slow"});
        assertThat(faults(t, index(base, sub, network, subNetwork), annotations, "m"))
                .as("a superclass's tag and a composed method tag route together")
                .singleElement()
                .satisfies(f -> assertThat(f.observed())
                        .contains("p.SubNetworkTest#x @Tag[network, slow] — no tier runs it")
                        .doesNotContain("p.SubTest")
                        .doesNotContain("p.BaseTest"));
    }

    /**
     * A framework's test annotation may carry a {@code @Tag} of its own, as Quarkus's
     * {@code @QuarkusTest} does. That tag still routes, but it is not the project's word, so the
     * ownership arm does not judge it; a tag the project's own composed annotation carries still is.
     */
    @Test
    void a_tag_only_a_library_annotation_carries_is_not_judged(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test.suites.slow]
                tags = ["slow"]
                """);
        FactsIndex library = index(tagAnnotation("FrameworkTest", "p.FrameworkTest"));
        FactsIndex main = index(
                tagAnnotation("Nightly", "nightly", "FrameworkTest"), tagAnnotation("Slow", "slow", "FrameworkTest"));

        ClassFacts framework = composed("ResourceTest", null, new String[] {"FrameworkTest"}, null, new String[0]);
        ClassFacts slow =
                composed("SlowResourceTest", null, new String[] {"FrameworkTest"}, "x", new String[] {"Slow"});
        ClassFacts base = composed("BaseResourceTest", null, new String[] {"FrameworkTest"}, null, new String[0]);
        ClassFacts sub = composed("SubResourceTest", "BaseResourceTest", new String[0], null, new String[0]);
        ClassFacts composedSlow = composed("ComposedSlowTest", null, new String[] {"Slow"}, null, new String[0]);
        assertThat(faults(t, index(framework, slow, base, sub, composedSlow), main, library, "m"))
                .as("a library's tag, directly or through a project annotation, is not judged")
                .isEmpty();

        ClassFacts nightly = composed("NightlyResourceTest", null, new String[] {"Nightly"}, null, new String[0]);
        assertThat(faults(t, index(nightly), main, library, "m"))
                .as("the project's own composed tag is still judged, and only it")
                .singleElement()
                .satisfies(f -> assertThat(f.observed()).endsWith("[nightly]"));
    }

    /**
     * A {@code [test] serial-tags} entry says how a class is scheduled, not which tier runs it, so
     * the ownership arm lets it through — while a tag that is simply unknown still fails.
     */
    @Test
    void a_declared_scheduling_tag_is_not_a_routing_tag(@TempDir Path root) throws Exception {
        TierPartition.Table t = table(root, """
                [test]
                serial-tags = ["serial"]

                [test.suites.integration]
                exclude-tags = ["slow"]

                [test.suites.slow]
                tags = ["slow"]
                """);
        assertThat(t.scheduling()).containsExactly("serial");
        assertThat(t.vocabulary())
                .as("a scheduling tag is not part of the tier vocabulary")
                .containsExactly("integration", "slow");

        ClassFacts alone = tagged("HogTest", new String[] {"slow", "serial"}, Map.of());
        assertThat(faults(t, index(alone), "m"))
                .as("`serial` beside a tier tag routes on the tier tag")
                .isEmpty();

        ClassFacts fastAndSerial = tagged("FastHogTest", new String[] {"serial"}, Map.of());
        assertThat(faults(t, index(fastAndSerial), "m"))
                .as("scheduling alone routes as untagged does, into the fast tier")
                .isEmpty();

        ClassFacts undeclared = tagged("TypoTest", new String[] {"seriall"}, Map.of());
        assertThat(faults(t, index(undeclared), "m"))
                .as("a tag that is neither a tier nor a declared scheduling tag is still unowned")
                .singleElement()
                .satisfies(f -> assertThat(f.observed()).contains("[seriall]"));
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
