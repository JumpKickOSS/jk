// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.extract;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.extract.fixture.FixtureBytes;
import cc.jumpkick.guard.extract.fixture.Sample;
import cc.jumpkick.guard.facts.CallSite;
import cc.jumpkick.guard.facts.ClassFacts;
import cc.jumpkick.guard.facts.FactsFormat;
import cc.jumpkick.guard.facts.FactsIndex;
import cc.jumpkick.guard.facts.Fingerprints;
import cc.jumpkick.guard.facts.MethodFacts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

class FactsExtractorTest {

    static byte[] classBytes(Class<?> c) throws IOException {
        return FixtureBytes.of(c);
    }

    static ClassFacts sample() throws IOException {
        return FactsExtractor.extract(classBytes(Sample.class));
    }

    @Test
    void class_table_supers_interfaces_and_source() throws IOException {
        ClassFacts c = sample();
        assertThat(c.name()).isEqualTo("cc/jumpkick/guard/extract/fixture/Sample");
        assertThat(c.binaryName()).isEqualTo(Sample.class.getName());
        assertThat(c.packageName()).isEqualTo("cc.jumpkick.guard.extract.fixture");
        assertThat(c.superName()).isEqualTo("java/lang/Object");
        assertThat(c.interfaces()).containsExactly("java/util/function/Supplier");
        assertThat(c.sourceFile()).isEqualTo("Sample.java");
        assertThat(c.hasFlag(Opcodes.ACC_FINAL)).isTrue();
    }

    @Test
    void constants_are_the_owner_vocabulary_and_non_constants_are_not() throws IOException {
        FactsIndex idx = new FactsIndex(Map.of(sample().name(), sample()), Map.of(), "");
        assertThat(idx.constants(Sample.class.getName()))
                .containsEntry("STEP", "compile-main")
                .containsEntry("LIMIT", "3")
                .containsEntry("NOT_PUBLIC", "hidden")
                .doesNotContainKey("field");
    }

    @Test
    void calls_carry_origin_line_and_the_literal_loaded_before_the_invoke() throws IOException {
        ClassFacts c = sample();
        MethodFacts get = c.methods().stream()
                .filter(m -> m.name().equals("get"))
                .findFirst()
                .orElseThrow();
        CallSite prop = get.calls().stream()
                .filter(s -> s.name().equals("getProperty"))
                .findFirst()
                .orElseThrow();
        assertThat(prop.owner()).isEqualTo("java/lang/System");
        assertThat(prop.count()).as("two getProperty calls dedupe to one site").isEqualTo(2);
        assertThat(prop.literalBefore()).isEqualTo("os.name");
        assertThat(prop.line()).isGreaterThan(0);
        CallSite lower = get.calls().stream()
                .filter(s -> s.name().equals("toLowerCase"))
                .findFirst()
                .orElseThrow();
        assertThat(lower.desc()).isEqualTo("(Ljava/util/Locale;)Ljava/lang/String;");
        assertThat(lower.literalBefore())
                .as("a field ref between the literal and the invoke clears the peephole")
                .isNull();
        MethodFacts digest = c.methods().stream()
                .filter(m -> m.name().equals("digest"))
                .findFirst()
                .orElseThrow();
        assertThat(digest.calls()).singleElement().satisfies(s -> {
            assertThat(s.target())
                    .isEqualTo(
                            "java.security.MessageDigest#getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;");
            assertThat(s.literalBefore()).isEqualTo("SHA-256");
        });
    }

    @Test
    void branches_count_conditions_switch_arms_and_handlers() throws IOException {
        MethodFacts m = sample().methods().stream()
                .filter(x -> x.name().equals("branches"))
                .findFirst()
                .orElseThrow();
        // if (1) + switch arms (2 cases + default, table or lookup) + catch (1)
        assertThat(m.branches()).isBetween(4, 6);
        assertThat(m.parameterCount()).isEqualTo(1);
    }

    @Test
    void annotations_are_recorded_where_the_class_file_keeps_them() throws IOException {
        ClassFacts c = sample();
        // @SuppressWarnings is SOURCE retention: invisible by construction.
        assertThat(c.annotations()).isEmpty();
        assertThat(c.fields().stream()
                        .filter(f -> f.name().equals("field"))
                        .findFirst()
                        .orElseThrow()
                        .annotations())
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.typeName()).isEqualTo("java.lang.Deprecated");
                    assertThat(a.runtimeVisible()).isTrue();
                });
        MethodFacts ctor = c.methods().stream()
                .filter(m -> m.name().equals("<init>"))
                .findFirst()
                .orElseThrow();
        assertThat(ctor.parameterAnnotations()).hasSize(1);
        assertThat(ctor.parameterAnnotations().get(0))
                .singleElement()
                .extracting(a -> a.typeName())
                .isEqualTo(Sample.Marked.class.getName());
    }

    @Test
    void method_references_inside_lambdas_are_calls_and_type_refs_feed_package_edges() throws IOException {
        ClassFacts c = sample();
        MethodFacts lambda = c.methods().stream()
                .filter(m -> m.name().startsWith("lambda$lambda$"))
                .findFirst()
                .orElseThrow();
        assertThat(lambda.calls()).anySatisfy(s -> assertThat(s.target()).startsWith("java.io.PrintStream#println"));
        assertThat(c.typeRefs())
                .contains(
                        "java/util/List",
                        "java/util/Locale",
                        "java/security/MessageDigest",
                        "java/lang/NumberFormatException");
        assertThat(Fingerprints.normalise("a.B#lambda$run$3()V")).isEqualTo("a.B#lambda$run$()V");
        assertThat(Fingerprints.normalise("a.B$1#run()V")).isEqualTo("a.B$#run()V");
    }

    @Test
    void the_binary_format_round_trips_and_its_digest_is_content_derived(@TempDir Path dir) throws IOException {
        ClassFacts s = sample();
        ClassFacts inner = FactsExtractor.extract(classBytes(Sample.Inner.class));
        FactsIndex idx = new FactsIndex(Map.of(s.name(), s, inner.name(), inner), Map.of("x.class", "1:2"), "");
        String digest = FactsFormat.digestOf(idx);
        FactsFormat.write(dir.resolve("g.idx"), idx.withStamps(idx.stamps(), digest));
        FactsIndex back = FactsFormat.read(dir.resolve("g.idx"));
        assertThat(back.classes()).isEqualTo(idx.classes());
        assertThat(back.stamps()).containsEntry("x.class", "1:2");
        assertThat(back.bodyDigest()).isEqualTo(digest);
        assertThat(FactsFormat.readHeader(dir.resolve("g.idx")))
                .get()
                .extracting(FactsFormat.Header::bodyDigest)
                .isEqualTo(digest);
        // Stamps are local bookkeeping; the digest is about the classes only.
        assertThat(FactsFormat.digestOf(idx.withStamps(Map.of("y.class", "9:9"), "")))
                .isEqualTo(digest);
        assertThat(back.packageEdges()).containsKey("cc.jumpkick.guard.extract.fixture");
    }
}
