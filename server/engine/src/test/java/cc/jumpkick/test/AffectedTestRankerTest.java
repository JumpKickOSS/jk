// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AffectedTestRankerTest {

    @Test
    void body_only_name_match_ranks_foo_test_first() {
        var foo = new ClassAbi.Fingerprint("api");
        var fooNow = new ClassAbi.Fingerprint("api");
        TestClassIndex.Entry test =
                new TestClassIndex.Entry("com.acme.FooTest", Set.of("com.acme.Foo"), Set.of(), "Foo");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/main/java/com/acme/Foo.java"),
                Map.of("com.acme.Foo", foo),
                Map.of("com.acme.Foo", fooNow),
                List.of(),
                List.of(test),
                Set.of("com.acme.Foo"),
                List.of()));
        assertThat(r.refused()).isFalse();
        assertThat(r.classNames()).containsExactly("com.acme.FooTest");
        assertThat(r.ranked().getFirst().score()).isEqualTo(100);
    }

    @Test
    void abi_import_outranks_unrelated() {
        var prev = new ClassAbi.Fingerprint("api1");
        var now = new ClassAbi.Fingerprint("api2");
        TestClassIndex.Entry importer =
                new TestClassIndex.Entry("com.acme.BarTest", Set.of("com.acme.Foo"), Set.of(), "Bar");
        TestClassIndex.Entry other =
                new TestClassIndex.Entry("com.acme.BazTest", Set.of("com.acme.Baz"), Set.of(), "Baz");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/main/java/com/acme/Foo.java"),
                Map.of("com.acme.Foo", prev),
                Map.of("com.acme.Foo", now),
                List.of(),
                List.of(importer, other),
                Set.of("com.acme.Foo", "com.acme.Baz"),
                List.of()));
        assertThat(r.classNames()).containsExactly("com.acme.BarTest");
        assertThat(r.ranked().getFirst().score()).isEqualTo(90);
    }

    @Test
    void exclude_tag_drops_class() {
        var foo = new ClassAbi.Fingerprint("a");
        var fooNow = new ClassAbi.Fingerprint("a");
        TestClassIndex.Entry slow =
                new TestClassIndex.Entry("com.acme.FooTest", Set.of("com.acme.Foo"), Set.of("slow"), "Foo");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.of(List.of(), false, List.of(), List.of("slow"), true),
                List.of("api/src/main/java/com/acme/Foo.java"),
                Map.of("com.acme.Foo", foo),
                Map.of("com.acme.Foo", fooNow),
                List.of(),
                List.of(slow),
                Set.of("com.acme.Foo"),
                List.of()));
        assertThat(r.ranked()).isEmpty();
    }

    @Test
    void dirty_integration_suite_refuses_outside_selection() {
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/integration/java/com/acme/FooIT.java"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                List.of()));
        assertThat(r.refused()).isTrue();
        assertThat(r.refuse().code()).isEqualTo("outside-selection");
    }

    @Test
    void production_package_named_test_is_not_a_test_source() {
        assertThat(AffectedTestRanker.isTestSource("src/main/java/cc/jumpkick/test/Foo.java"))
                .isFalse();
        assertThat(AffectedTestRanker.isMainSource("src/main/java/cc/jumpkick/test/Foo.java"))
                .isTrue();
        assertThat(AffectedTestRanker.isTestSource("src/test/java/cc/jumpkick/test/FooTest.java"))
                .isTrue();
    }

    @Test
    void dirty_main_in_test_package_name_matches_not_test_src() {
        TestClassIndex.Entry test = new TestClassIndex.Entry("cc.jumpkick.test.FooTest", Set.of(), Set.of(), "Foo");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/engine"),
                "cc.jumpkick:engine",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("engine/src/main/java/cc/jumpkick/test/Foo.java"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(test),
                Set.of(),
                List.of()));
        assertThat(r.refused()).isFalse();
        assertThat(r.classNames()).containsExactly("cc.jumpkick.test.FooTest");
        assertThat(r.ranked().getFirst().reason()).startsWith("name-");
        assertThat(r.ranked().getFirst().reason()).doesNotContain("test-src");
    }

    @Test
    void dirty_test_source_ranks_without_compiled_index() {
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/test/java/com/acme/FooTest.java"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                List.of()));
        assertThat(r.refused()).isFalse();
        assertThat(r.classNames()).containsExactly("com.acme.FooTest");
        assertThat(r.ranked().getFirst().reason()).isEqualTo("test-src");
        assertThat(r.ranked().getFirst().score()).isEqualTo(110);
    }

    @Test
    void dirty_main_name_matches_source_test_without_class_files() {
        TestClassIndex.Entry test = new TestClassIndex.Entry("com.acme.FooTest", Set.of(), Set.of(), "Foo");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/main/java/com/acme/Foo.java"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(test),
                Set.of(),
                List.of()));
        assertThat(r.refused()).isFalse();
        assertThat(r.classNames()).containsExactly("com.acme.FooTest");
    }

    @Test
    void foreign_abi_change_ranks_a_dependents_importer() {
        // Module B is a clean dependent: no local dirty paths, but its test imports a type
        // module A classified as ABI-changed (JK-2606).
        TestClassIndex.Entry importer =
                new TestClassIndex.Entry("com.acme.b.BarTest", Set.of("com.acme.a.Foo"), Set.of(), "Bar");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/b"),
                "com.acme:b",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(importer),
                Set.of("com.acme.a.Foo", "com.acme.b.Bar"),
                List.of(new AffectedTests.ModuleRow("b", "com.acme:b", "dependent")),
                Map.of("com.acme.a.Foo", ClassAbi.Kind.ABI)));
        assertThat(r.refused()).isFalse();
        assertThat(r.classNames()).containsExactly("com.acme.b.BarTest");
        assertThat(r.ranked().getFirst().score()).isEqualTo(90);
        assertThat(r.ranked().getFirst().reason()).isEqualTo("abi-import:com.acme.a.Foo");
        assertThat(r.modules().getFirst().why()).isEqualTo("dependent");
    }

    @Test
    void foreign_body_change_ranks_importer_at_body_import() {
        TestClassIndex.Entry importer =
                new TestClassIndex.Entry("com.acme.b.BarTest", Set.of("com.acme.a.Foo"), Set.of(), "Bar");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/b"),
                "com.acme:b",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(importer),
                Set.of("com.acme.a.Foo"),
                List.of(),
                Map.of("com.acme.a.Foo", ClassAbi.Kind.BODY)));
        assertThat(r.classNames()).containsExactly("com.acme.b.BarTest");
        assertThat(r.ranked().getFirst().score()).isEqualTo(80);
    }

    @Test
    void foreign_change_name_matches_a_dependents_test() {
        TestClassIndex.Entry named = new TestClassIndex.Entry("com.acme.b.FooTest", Set.of(), Set.of(), "Foo");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/b"),
                "com.acme:b",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(named),
                Set.of(),
                List.of(),
                Map.of("com.acme.a.Foo", ClassAbi.Kind.BODY)));
        assertThat(r.classNames()).containsExactly("com.acme.b.FooTest");
        assertThat(r.ranked().getFirst().reason()).isEqualTo("name-body:com.acme.a.Foo");
    }

    @Test
    void local_changed_outranks_the_same_foreign_type() {
        var prev = new ClassAbi.Fingerprint("api1");
        var now = new ClassAbi.Fingerprint("api1");
        TestClassIndex.Entry test =
                new TestClassIndex.Entry("com.acme.FooTest", Set.of("com.acme.Foo"), Set.of(), "Foo");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/main/java/com/acme/Foo.java"),
                Map.of("com.acme.Foo", prev),
                Map.of("com.acme.Foo", now),
                List.of(),
                List.of(test),
                Set.of("com.acme.Foo"),
                List.of(),
                Map.of("com.acme.Foo", ClassAbi.Kind.ABI)));
        // Local classification (BODY) wins over the stale foreign ABI vote for the same FQC.
        assertThat(r.ranked().getFirst().score()).isEqualTo(100);
        assertThat(r.ranked().getFirst().reason()).isEqualTo("name-body:com.acme.Foo");
    }

    @Test
    void compact_layout_derives_full_fqcs(@TempDir Path ws) throws Exception {
        // Compact (simple) layout: main under src/<pkg>, tests under test/src/<pkg>. The string
        // heuristics dropped the first package segment; root resolution must not (JK-2609).
        Path module = ws.resolve("api");
        Files.writeString(
                Files.createDirectories(module).resolve("jk.toml"),
                "group = \"com.acme\"\nname = \"api\"\nversion = \"0.1.0\"\nlayout = \"simple\"\n");
        Files.createDirectories(module.resolve("src/com/acme"));
        Files.createDirectories(module.resolve("test/src/com/acme"));

        TestClassIndex.Entry test =
                new TestClassIndex.Entry("com.acme.FooTest", Set.of("com.acme.Foo"), Set.of(), "Foo");
        var prev = new ClassAbi.Fingerprint("api");
        var now = new ClassAbi.Fingerprint("api");
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                module,
                "com.acme:api",
                ws,
                TestSelection.DEFAULT,
                List.of("api/src/com/acme/Foo.java", "api/test/src/com/acme/FooTest.java"),
                Map.of("com.acme.Foo", prev),
                Map.of("com.acme.Foo", now),
                List.of(),
                List.of(test),
                Set.of("com.acme.Foo"),
                List.of()));
        assertThat(r.refused()).isFalse();
        // The dirty test ranks under its real FQCN, and the body-only main edit name-matches it.
        assertThat(r.classNames()).containsExactly("com.acme.FooTest");
        assertThat(r.ranked().getFirst().reason()).isEqualTo("test-src");
    }

    @Test
    void compact_layout_dirty_integration_test_refuses_outside_selection(@TempDir Path ws) throws Exception {
        Path module = ws.resolve("api");
        Files.writeString(
                Files.createDirectories(module).resolve("jk.toml"),
                "group = \"com.acme\"\nname = \"api\"\nversion = \"0.1.0\"\nlayout = \"simple\"\n");
        Files.createDirectories(module.resolve("integration/src/com/acme"));

        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                module,
                "com.acme:api",
                ws,
                TestSelection.DEFAULT,
                List.of("api/integration/src/com/acme/FooIT.java"),
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                List.of()));
        assertThat(r.refused()).isTrue();
        assertThat(r.refuse().code()).isEqualTo("outside-selection");
    }

    @Test
    void cap_is_twenty() {
        var prev = new ClassAbi.Fingerprint("a");
        var now = new ClassAbi.Fingerprint("a");
        List<TestClassIndex.Entry> tests = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tests.add(new TestClassIndex.Entry("com.acme.T" + i + "Test", Set.of("com.acme.Foo"), Set.of(), "T" + i));
        }
        AffectedTests r = AffectedTestRanker.rank(new AffectedTestRanker.Inputs(
                Path.of("/ws/api"),
                "com.acme:api",
                Path.of("/ws"),
                TestSelection.DEFAULT,
                List.of("api/src/main/java/com/acme/Foo.java"),
                Map.of("com.acme.Foo", prev),
                Map.of("com.acme.Foo", now),
                List.of(),
                tests,
                Set.of("com.acme.Foo"),
                List.of()));
        assertThat(r.ranked()).hasSize(20);
        assertThat(r.candidateCount()).isEqualTo(25);
    }
}
