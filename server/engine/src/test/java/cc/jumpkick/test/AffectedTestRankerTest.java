// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AffectedTestRankerTest {

    @Test
    void body_only_name_match_ranks_foo_test_first() {
        var foo = new ClassAbi.Fingerprint("api", "body1");
        var fooNow = new ClassAbi.Fingerprint("api", "body2");
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
        var prev = new ClassAbi.Fingerprint("api1", "b");
        var now = new ClassAbi.Fingerprint("api2", "b");
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
        var foo = new ClassAbi.Fingerprint("a", "1");
        var fooNow = new ClassAbi.Fingerprint("a", "2");
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
    void cap_is_twenty() {
        var prev = new ClassAbi.Fingerprint("a", "1");
        var now = new ClassAbi.Fingerprint("a", "2");
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
