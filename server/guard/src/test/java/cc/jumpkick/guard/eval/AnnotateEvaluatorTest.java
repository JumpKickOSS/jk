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
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class AnnotateEvaluatorTest {

    private static final String FIXTURE = "cc.jumpkick.guard.extract.fixture";

    /** The fixture package (with its {@code @NullMarked} package-info) plus this test's package (without). */
    private static FactsIndex facts(Class<?>... classes) throws IOException {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        List<Class<?>> all = new ArrayList<>(List.of(classes));
        all.addAll(List.of(Sample.Hidden.class, Sample.Gone.class, Sample.Tagged.class, Sample.Marked.class));
        for (Class<?> c : all) {
            ClassFacts f = FactsExtractor.extract(FixtureBytes.of(c));
            m.put(f.name(), f);
        }
        String res = FIXTURE.replace('.', '/') + "/package-info.class";
        try (var in = Objects.requireNonNull(Sample.class.getClassLoader().getResourceAsStream(res), res)) {
            ClassFacts info = FactsExtractor.extract(in.readAllBytes());
            m.put(info.name(), info);
        }
        return new FactsIndex(m, Map.of(), "");
    }

    private static Evaluation run(Path dir, String body, FactsIndex idx, FactsIndex... testIdx) throws Exception {
        return evaluate(dir, "[guards.r]\nkind = \"annotate\"\nwhy = \"w\"\n" + body, "r", idx, testIdx);
    }

    /** Rule {@code id} of the rules file {@code rules}, evaluated over {@code idx}. */
    private static Evaluation evaluate(Path dir, String rules, String id, FactsIndex idx, FactsIndex... testIdx)
            throws Exception {
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), rules);
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.hasErrors()).as(load.problems().toString()).isFalse();
        Rule rule = load.rules().rule(id).orElseThrow();
        FactsIndex tests = testIdx.length == 0 ? null : testIdx[0];
        Path classpath = nullMarkedOnClasspath(dir);
        EvalContext ctx = new EvalContext(
                Lane.MODULE,
                dir,
                "m",
                dir.resolve("m"),
                List.of(dir.resolve("m")),
                () -> idx,
                () -> tests,
                () -> List.of(classpath));
        return Evaluators.forKind(rule.kind()).evaluate(rule, ctx);
    }

    /**
     * jspecify is compile-only here, so the annotation type is written as a class file the way a
     * library on the module's classpath would carry it: {@code @Retention(RUNTIME)}.
     */
    private static Path nullMarkedOnClasspath(Path dir) throws IOException {
        Path cp = dir.resolve("cp");
        Path file = cp.resolve("org/jspecify/annotations/NullMarked.class");
        if (Files.exists(file)) return cp;
        Files.createDirectories(file.getParent());
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "org/jspecify/annotations/NullMarked",
                null,
                "java/lang/Object",
                new String[] {"java/lang/annotation/Annotation"});
        AnnotationVisitor av = cw.visitAnnotation("Ljava/lang/annotation/Retention;", true);
        av.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "RUNTIME");
        av.visitEnd();
        cw.visitEnd();
        Files.write(file, cw.toByteArray());
        return cp;
    }

    @Test
    void forbid_on_field_sees_runtime_and_class_retention(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class);
        Evaluation deprecated = run(dir, "forbid = \"java.lang.Deprecated\"\non = \"field\"\n", idx);
        assertThat(deprecated.outcome()).isEqualTo(Outcome.VIOLATIONS);
        assertThat(deprecated.observations()).extracting(Observation::key).containsExactly(FIXTURE + ".Sample.field");
        assertThat(deprecated.observations().get(0).file())
                .isEqualTo("m/src/main/java/cc/jumpkick/guard/extract/fixture/Sample.java");
        Evaluation hidden = run(dir, "forbid = \"" + FIXTURE + ".Sample$Hidden\"\non = \"field\"\n", idx);
        assertThat(hidden.observations()).extracting(Observation::key).containsExactly(FIXTURE + ".Sample.count");
        assertThat(hidden.population()).containsEntry("elements", 5L);
    }

    @Test
    void a_source_retention_annotation_is_scanner_failed_not_clean(@TempDir Path dir) throws Exception {
        Evaluation e = run(dir, "forbid = \"" + FIXTURE + ".Sample$Gone\"\non = \"field\"\n", facts(Sample.class));
        assertThat(e.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(e.note()).contains("retention SOURCE");
        Evaluation unknown = run(dir, "forbid = \"com.acme.Nope\"\non = \"field\"\n", facts(Sample.class));
        assertThat(unknown.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(unknown.note()).contains("does not resolve");
    }

    @Test
    void require_on_method_and_parameter(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class);
        Evaluation methods = run(dir, "require = \"" + FIXTURE + ".Sample$Hidden\"\non = \"method\"\n", idx);
        assertThat(methods.observations()).extracting(Observation::key).doesNotContain(FIXTURE + ".Sample#hidden()V");
        assertThat(methods.observations())
                .extracting(Observation::key)
                .contains(FIXTURE + ".Sample#get()Ljava/lang/String;");
        assertThat(methods.observations())
                .extracting(Observation::key)
                .noneMatch(k -> k.contains("<init>") || k.contains("lambda$"));
        Evaluation params = run(dir, "require = \"" + FIXTURE + ".Sample$Marked\"\non = \"parameter\"\n", idx);
        // the constructor's seed carries @Marked; branches(int x) does not
        assertThat(params.observations()).extracting(Observation::key).contains(FIXTURE + ".Sample#branches(I)I[0]");
        assertThat(params.observations())
                .extracting(Observation::key)
                .doesNotContain(FIXTURE + ".Sample#<init>(Ljava/lang/String;)V[0]");
    }

    @Test
    void require_on_package_reads_package_info_and_fires_for_a_package_without_one(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class, Tier.class, AnnotateEvaluatorTest.class);
        Evaluation e = run(dir, "require = \"org.jspecify.annotations.NullMarked\"\non = \"package\"\n", idx);
        assertThat(e.population()).containsEntry("elements", 2L);
        assertThat(e.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("cc.jumpkick.guard.eval");
            assertThat(o.detail()).contains("has no package-info.java");
        });
        Evaluation narrowed = run(
                dir,
                "require = \"org.jspecify.annotations.NullMarked\"\non = \"package\"\nmatching = { reside-in = \"..fixture..\" }\n",
                idx);
        assertThat(narrowed.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(narrowed.population()).containsEntry("elements", 1L);
    }

    @Test
    void a_require_whose_predicate_selects_nothing_passes_without_resolving_the_annotation(@TempDir Path dir)
            throws Exception {
        FactsIndex idx = facts(Sample.class, Tier.class);
        // The annotation is on no classpath here; with nothing in scope there is nothing to judge.
        Evaluation e = run(
                dir,
                "require = \"com.acme.Absent\"\non = \"package\"\nmatching = { reside-in = \"no.such.pkg..\" }\n",
                idx);
        assertThat(e.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(e.population()).containsEntry("elements", 0L);

        Evaluation inScope = run(dir, "require = \"com.acme.Absent\"\non = \"package\"\n", idx);
        assertThat(inScope.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
    }

    @Test
    void matching_with_value_and_allow(@TempDir Path dir) throws Exception {
        FactsIndex idx = facts(Sample.class, Sample.Inner.class, Tier.class);
        String tagged = "require = \"" + FIXTURE + ".Sample$Tagged\"\non = \"class\"\n";
        Evaluation cls = run(
                dir, tagged + "with-value = \"tier=gold\"\nmatching = { are = \"nested\", named = \"Inner\" }\n", idx);
        assertThat(cls.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(cls.population()).containsEntry("elements", 1L);
        Evaluation wrongValue = run(dir, tagged + "with-value = \"outer\"\nmatching = { named = \"Inner\" }\n", idx);
        assertThat(wrongValue.observations()).hasSize(1);
        assertThat(wrongValue.observations().get(0).detail()).contains("lacks @Tagged with outer");
        Evaluation source = run(dir, "require = \"java.lang.SuppressWarnings\"\non = \"class\"\n", idx);
        assertThat(source.outcome()).as("SuppressWarnings is SOURCE retention").isEqualTo(Outcome.SCANNER_FAILED);
        Evaluation allowed = run(
                dir,
                tagged + "matching = { are = [\"top-level\", \"!annotation\"] }\n[[guards.r.allow]]\nin = \"" + FIXTURE
                        + ".*\"\nreason = \"fixtures\"\n",
                idx);
        assertThat(allowed.outcome()).isEqualTo(Outcome.CLEAN);
        Evaluation stale = run(
                dir,
                "forbid = \"java.lang.Deprecated\"\non = \"class\"\n[[guards.r.allow]]\nin = \"" + FIXTURE
                        + ".Tier\"\nreason = \"gone\"\n",
                idx);
        assertThat(stale.outcome())
                .as("Tier is here and carries no @Deprecated")
                .isEqualTo(Outcome.STALE_ALLOW);
        Evaluation elsewhere = run(
                dir,
                "forbid = \"java.lang.Deprecated\"\non = \"class\"\n[[guards.r.allow]]\nin = \"com.acme.**\"\nreason = \"gone\"\n",
                idx);
        assertThat(elsewhere.outcome())
                .as("another module's exemption is not stale here")
                .isEqualTo(Outcome.CLEAN);
        Evaluation bad = run(dir, tagged + "matching = { colour = \"red\" }\n", idx);
        assertThat(bad.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(bad.note()).contains("unknown predicate `colour`");
    }

    @Test
    void test_class_reads_the_test_index(@TempDir Path dir) throws Exception {
        FactsIndex main = facts(Sample.class);
        FactsIndex tests = facts(AnnotateEvaluatorTest.class, Sample.class);
        Evaluation none = run(dir, "require = \"java.lang.Deprecated\"\non = \"test-class\"\n", main);
        assertThat(none.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(none.bites()).isFalse();
        Evaluation e = run(dir, "require = \"java.lang.Deprecated\"\non = \"test-class\"\n", main, tests);
        assertThat(e.population()).containsEntry("elements", 1L);
        assertThat(e.observations())
                .extracting(Observation::key)
                .containsExactly("cc.jumpkick.guard.eval.AnnotateEvaluatorTest");
    }

    @Test
    void require_derives_its_instead(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve(GuardsPresence.RULES_FILE),
                "[guards.r]\nkind = \"annotate\"\nwhy = \"w\"\nrequire = \"org.jspecify.annotations.NullMarked\"\non = \"package\"\n");
        LoadResult load = GuardRules.load(dir, GuardsConfig.ABSENT);
        assertThat(load.rules().rule("r").orElseThrow().instead())
                .isEqualTo("annotate the package-info.java with @NullMarked");
    }

    /** A class {@code internal} as its compiler leaves it: the {@code SourceFile} attribute, and Kotlin's metadata. */
    private static ClassFacts compiledFrom(String internal, String sourceFile) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
        cw.visitSource(sourceFile, null);
        if (sourceFile.endsWith(".kt")) {
            cw.visitAnnotation("Lkotlin/Metadata;", true).visitEnd();
        }
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    /** {@code pkg}'s package-info, compiled from Java and carrying {@code @NullMarked}. */
    private static ClassFacts nullMarkedPackageInfo(String pkg) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_SYNTHETIC,
                pkg + "/package-info",
                null,
                "java/lang/Object",
                null);
        cw.visitSource("package-info.java", null);
        cw.visitAnnotation("Lorg/jspecify/annotations/NullMarked;", true).visitEnd();
        cw.visitEnd();
        return FactsExtractor.extract(cw.toByteArray());
    }

    private static FactsIndex index(ClassFacts... classes) {
        Map<String, ClassFacts> m = new LinkedHashMap<>();
        for (ClassFacts c : classes) m.put(c.name(), c);
        return new FactsIndex(m, Map.of(), "");
    }

    @Test
    void source_language_reads_the_source_file_attribute(@TempDir Path dir) throws Exception {
        FactsIndex idx = index(
                compiledFrom("a/J", "J.java"),
                compiledFrom("a/K", "K.kt"),
                compiledFrom("a/S", "S.scala"),
                compiledFrom("a/G", "G.groovy"));
        String rule = "forbid = \"java.lang.Deprecated\"\non = \"class\"\n";
        for (String language : List.of("java", "kotlin", "scala", "groovy")) {
            Evaluation e = run(dir, rule + "matching = { source-language = \"" + language + "\" }\n", idx);
            assertThat(e.population()).as(language).containsEntry("elements", 1L);
        }
        Evaluation notJava = run(dir, rule + "matching = { source-language = \"!java\" }\n", idx);
        assertThat(notJava.population()).containsEntry("elements", 3L);
        Evaluation unknown = run(dir, rule + "matching = { source-language = \"cobol\" }\n", idx);
        assertThat(unknown.outcome()).isEqualTo(Outcome.SCANNER_FAILED);
        assertThat(unknown.note()).contains("source-language = \"cobol\"").contains("java, kotlin, scala, groovy");
    }

    @Test
    void the_library_packs_null_marked_rule_judges_only_packages_with_a_java_class(@TempDir Path dir) throws Exception {
        String pack = Files.readString(RepoRoot.file(
                AnnotateEvaluatorTest.class, "server/guard/packs/library/src/main/resources/jk-guards.toml"));
        String id = "null-marked-packages";

        FactsIndex otherLanguages = index(
                compiledFrom("com/acme/kotlin/Greeter", "Greeter.kt"),
                compiledFrom("com/acme/kotlin/GreeterKt", "Greeter.kt"),
                compiledFrom("com/acme/scala/Greeter", "Greeter.scala"),
                compiledFrom("com/acme/scala/Greeter$", "Greeter.scala"),
                compiledFrom("com/acme/groovy/Greeter", "Greeter.groovy"));
        Evaluation alone = evaluate(dir, pack, id, otherLanguages);
        assertThat(alone.outcome())
                .as("Kotlin, Scala and Groovy packages need no @NullMarked")
                .isEqualTo(Outcome.CLEAN);
        assertThat(alone.population()).containsEntry("elements", 0L);

        Evaluation java = evaluate(dir, pack, id, index(compiledFrom("com/acme/java/Greeter", "Greeter.java")));
        assertThat(java.observations()).singleElement().satisfies(o -> {
            assertThat(o.key()).isEqualTo("com.acme.java");
            assertThat(o.detail()).contains("has no package-info.java");
        });

        ClassFacts scala = compiledFrom("com/acme/mixed/Greeter", "Greeter.scala");
        ClassFacts javaBeside = compiledFrom("com/acme/mixed/Names", "Names.java");
        Evaluation mixed = evaluate(dir, pack, id, index(scala, javaBeside));
        assertThat(mixed.observations()).extracting(Observation::key).containsExactly("com.acme.mixed");
        Evaluation mixedMarked =
                evaluate(dir, pack, id, index(scala, javaBeside, nullMarkedPackageInfo("com/acme/mixed")));
        assertThat(mixedMarked.outcome()).isEqualTo(Outcome.CLEAN);
        assertThat(mixedMarked.population()).containsEntry("elements", 1L);
    }

    @Test
    void package_patterns() {
        assertThat(ClassPredicates.packageMatches("..fixture..", "cc.jumpkick.guard.extract.fixture"))
                .isTrue();
        assertThat(ClassPredicates.packageMatches("..fixture..", "cc.jumpkick.guard.extract.fixture.deep"))
                .isTrue();
        assertThat(ClassPredicates.packageMatches("..fixture", "cc.jumpkick.guard.extract.fixture.deep"))
                .isFalse();
        assertThat(ClassPredicates.packageMatches("cc.jumpkick..", "cc.jumpkick"))
                .isTrue();
        assertThat(ClassPredicates.packageMatches("cc.jumpkick..", "org.cc.jumpkick"))
                .isFalse();
        assertThat(ClassPredicates.packageMatches("cc.jumpkick.guard.*", "cc.jumpkick.guard.eval"))
                .isTrue();
        assertThat(ClassPredicates.packageMatches("cc.jumpkick.guard", "cc.jumpkick.guard.eval"))
                .isFalse();
    }
}
