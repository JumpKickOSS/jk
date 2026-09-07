// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.baseline.Observation;
import cc.jumpkick.guard.extract.FactsExtractor;
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
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TiersEvaluatorTest {

    private static final String MANIFEST = """
            group = "t"
            name = "ws"
            version = "0.0.1"
            jdk = 25

            [test]
            exclude-tags = ["slow", "integration"]

            [profiles.e2e]
            include-tags = ["e2e"]
            """;

    /** A JUnit test class in package {@code p} whose one field has type {@code fieldType}, tagged as given. */
    private static ClassFacts testClass(String simple, String fieldType, String... tags) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "p/" + simple, null, "java/lang/Object", null);
        cw.visitSource(simple + ".java", null);
        for (String tag : tags) {
            AnnotationVisitor av = cw.visitAnnotation("Lorg/junit/jupiter/api/Tag;", true);
            av.visit("value", tag);
            av.visitEnd();
        }
        if (fieldType != null)
            cw.visitField(Opcodes.ACC_PRIVATE, "c", "L" + fieldType + ";", null, null)
                    .visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "runs", "()V", null, null);
        mv.visitAnnotation("Lorg/junit/jupiter/api/Test;", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "t");
    }

    private static void source(Path root, String suite, String simple) throws IOException {
        Path f = root.resolve("m/src/" + suite + "/java/p/" + simple + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package p; class " + simple + " {}\n");
    }

    private static LoadResult load(Path root, String body) throws IOException {
        Files.writeString(root.resolve("jk.toml"), MANIFEST);
        Files.writeString(
                root.resolve(GuardsPresence.RULES_FILE), "[guards.r]\nkind = \"tiers\"\nwhy  = \"w\"\n" + body);
        return GuardRules.load(root, GuardsConfig.ABSENT);
    }

    private static Rule rule(Path root, String body) throws IOException {
        LoadResult load = load(root, body);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        return load.rules().rule("r").orElseThrow();
    }

    private static Evaluation eval(Path root, Rule rule, FactsIndex tests) throws Exception {
        EvalContext ctx = new EvalContext(
                Lane.MODULE,
                root,
                "m",
                root.resolve("m"),
                List.of(root.resolve("m")),
                () -> FactsIndex.EMPTY,
                () -> tests,
                List::of);
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    @Test
    void a_container_test_in_the_unit_suite_fires_and_the_same_class_under_integration_passes(@TempDir Path root)
            throws Exception {
        ClassFacts container = testClass("ContainerTest", "org/testcontainers/containers/GenericContainer");
        ClassFacts plain = testClass("PlainTest", "java/util/List");
        source(root, "test", "ContainerTest");
        source(root, "test", "PlainTest");
        Rule r = rule(root, "uses  = [\"org.testcontainers.**\"]\nsuite = \"integration\"\n");
        assertThat(Evaluators.laneOf(r)).isEqualTo(Lane.MODULE);
        assertThat(r.instead()).isEqualTo("move the class to src/integration/java");
        Evaluation e = eval(root, r, index(container, plain));
        assertThat(e.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("p.ContainerTest");
            assertThat(o.file()).isEqualTo("m/src/test/java/p/ContainerTest.java");
            assertThat(o.detail())
                    .contains("uses org.testcontainers.containers.GenericContainer")
                    .contains("src/test")
                    .contains("src/integration");
        });
        assertThat(e.population()).containsEntry("test-classes", 2L);

        Files.delete(root.resolve("m/src/test/java/p/ContainerTest.java"));
        source(root, "integration", "ContainerTest");
        Evaluation moved = eval(root, r, index(container, plain));
        assertThat(moved.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(moved.population()).containsEntry("test-classes", 2L);
    }

    @Test
    void a_slow_tagged_unit_test_must_live_in_the_named_suite_and_a_used_type_can_demand_a_tag(@TempDir Path root)
            throws Exception {
        ClassFacts slow = testClass("SlowTest", "java/util/List", "slow");
        ClassFacts quick = testClass("QuickTest", "java/util/List");
        source(root, "test", "SlowTest");
        source(root, "test", "QuickTest");
        Evaluation e = eval(root, rule(root, "tagged = [\"slow\"]\nsuite  = \"e2e\"\n"), index(slow, quick));
        assertThat(e.observations()).extracting(Observation::key).containsExactly("p.SlowTest");
        assertThat(e.observations().get(0).detail())
                .contains("is tagged \"slow\"")
                .contains("not src/e2e");

        ClassFacts spring = testClass("BootTest", "org/springframework/boot/test/context/SpringBootTest");
        ClassFacts tagged = testClass("TaggedBootTest", "org/springframework/boot/test/context/SpringBootTest", "slow");
        source(root, "test", "BootTest");
        source(root, "test", "TaggedBootTest");
        Rule byTag = rule(root, "uses = [\"org.springframework.boot.test.**\"]\ntag  = \"slow\"\n");
        assertThat(byTag.instead()).isEqualTo("add @Tag(\"slow\") to the class");
        Evaluation t = eval(root, byTag, index(spring, tagged, quick));
        assertThat(t.observations()).extracting(Observation::key).containsExactly("p.BootTest");
        assertThat(t.observations().get(0).detail()).contains("carries no @Tag(\"slow\")");
    }

    @Test
    void no_test_classes_is_not_evaluated_and_an_unknown_tag_is_a_load_error(@TempDir Path root) throws Exception {
        Rule r = rule(root, "uses  = [\"org.testcontainers.**\"]\nsuite = \"integration\"\n");
        EvalContext ctx = new EvalContext(
                Lane.MODULE,
                root,
                "m",
                root.resolve("m"),
                List.of(root.resolve("m")),
                () -> FactsIndex.EMPTY,
                () -> null,
                List::of);
        Evaluation none = Evaluators.forKind(r.kind()).evaluate(r, ctx);
        assertThat(none.outcome()).isEqualTo(Outcome.NOT_EVALUATED);

        LoadResult bad = load(root, "tagged = [\"slow\", \"nightly\"]\nsuite  = \"e2e\"\n");
        assertThat(bad.hasErrors()).isTrue();
        assertThat(bad.problems().get(0).render())
                .contains("nightly")
                .contains("vocabulary")
                .contains("slow");
        LoadResult badTag = load(root, "uses = [\"org.testcontainers.**\"]\ntag  = \"docker\"\n");
        assertThat(badTag.hasErrors()).isTrue();
        assertThat(badTag.problems().get(0).render()).contains("`docker`");
        // a profile's tags are vocabulary too
        assertThat(load(root, "uses = [\"org.testcontainers.**\"]\ntag  = \"e2e\"\n")
                        .hasErrors())
                .isFalse();
    }

    @Test
    void type_globs_match_whole_packages_or_one_segment() {
        assertThat(TiersEvaluator.typeGlob("org.testcontainers.**")
                        .matcher("org.testcontainers.containers.GenericContainer")
                        .matches())
                .isTrue();
        assertThat(TiersEvaluator.typeGlob("org.testcontainers.*")
                        .matcher("org.testcontainers.containers.GenericContainer")
                        .matches())
                .isFalse();
        assertThat(TiersEvaluator.typeGlob("org.testcontainers.*")
                        .matcher("org.testcontainers.Testcontainers")
                        .matches())
                .isTrue();
        assertThat(TiersEvaluator.typeGlob("*.SpringBootTest")
                        .matcher("org.springframework.SpringBootTest")
                        .matches())
                .isFalse();
        assertThat(TiersEvaluator.typeGlob("**.SpringBootTest")
                        .matcher("org.springframework.SpringBootTest")
                        .matches())
                .isTrue();
    }
}
