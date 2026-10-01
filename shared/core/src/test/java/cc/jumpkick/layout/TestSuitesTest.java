// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.ClassSuite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSuitesTest {

    @Test
    void discover_default_and_integration_simple(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("test/src"));
        Files.writeString(tmp.resolve("test/src/FooTest.java"), "class FooTest {}");
        Files.createDirectories(tmp.resolve("integration/src"));
        Files.writeString(tmp.resolve("integration/src/SlowIT.java"), "class SlowIT {}");
        assertThat(TestSuites.discover(tmp, true)).containsExactly("test", "integration");
    }

    /** A directory the root's {@code [workspace] modules} lists is a member, never a suite of the root. */
    @Test
    void a_workspace_member_with_sources_is_not_a_suite_of_the_root(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), "[workspace]\nmodules = [\"engine\"]\n");
        Files.createDirectories(tmp.resolve("test/src"));
        Files.writeString(tmp.resolve("test/src/FooTest.java"), "class FooTest {}");
        Files.createDirectories(tmp.resolve("engine/src"));
        Files.writeString(tmp.resolve("engine/jk.toml"), "name = \"engine\"\n");
        Files.writeString(tmp.resolve("engine/src/E.java"), "class E {}");
        Files.createDirectories(tmp.resolve("integration/src"));
        Files.writeString(tmp.resolve("integration/src/SlowIT.java"), "class SlowIT {}");

        assertThat(TestSuites.discover(tmp, true)).containsExactly("test", "integration");
    }

    @Test
    void collect_only_selected_suite(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("test/src"));
        Files.writeString(tmp.resolve("test/src/ATest.java"), "class ATest {}");
        Files.createDirectories(tmp.resolve("integration/src"));
        Files.writeString(tmp.resolve("integration/src/BTest.java"), "class BTest {}");
        var onlyInt = TestSuites.collectJavaSources(tmp, true, List.of("integration"));
        assertThat(onlyInt).hasSize(1);
        assertThat(onlyInt.getFirst().getFileName().toString()).isEqualTo("BTest.java");
    }

    @Test
    void selection_default_is_test_suite() {
        var r = TestSelection.DEFAULT.resolve(List.of("test", "integration"));
        assertThat(r.suites()).containsExactly("test");
        assertThat(r.ok()).isTrue();
    }

    @Test
    void selection_all_returns_discovered() {
        var r = TestSelection.of(List.of(), true, List.of(), List.of()).resolve(List.of("test", "integration"));
        assertThat(r.suites()).containsExactly("test", "integration");
    }

    @Test
    void selection_unknown_suite_reports_missing() {
        var r = TestSelection.of(List.of("nope"), false, List.of(), List.of()).resolve(List.of("test"));
        assertThat(r.ok()).isFalse();
        assertThat(r.missingMessage()).contains("nope").contains("test");
    }

    @Test
    void gate_includes_integration_when_discovered() {
        var r = TestSelection.of(TestSuites.GUARD_SUITES, false, List.of(), List.of(), true, true)
                .resolve(List.of("test", "integration"));
        assertThat(r.ok()).isTrue();
        assertThat(r.suites()).containsExactly("test", "integration");
    }

    @Test
    void gate_skips_missing_integration() {
        var r = TestSelection.of(TestSuites.GUARD_SUITES, false, List.of(), List.of(), true, true)
                .resolve(List.of("test"));
        assertThat(r.ok()).isTrue();
        assertThat(r.suites()).containsExactly("test");
    }

    @Test
    void gate_without_integration_dir_is_the_default_suite() {
        var r = TestSelection.of(TestSuites.GUARD_SUITES, false, List.of(), List.of(), true, true)
                .resolve(List.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.suites()).containsExactly("test");
    }

    @Test
    void explicit_integration_suite_still_errors_when_absent() {
        var r = TestSelection.of(List.of("integration"), false, List.of(), List.of())
                .resolve(List.of("test"));
        assertThat(r.ok()).isFalse();
        assertThat(r.missingMessage()).contains("integration");
    }

    @Test
    void gate_unknown_extra_suite_errors() {
        var r = TestSelection.of(List.of("test", "contract"), false, List.of(), List.of(), true, true)
                .resolve(List.of("test"));
        assertThat(r.ok()).isFalse();
        assertThat(r.missingMessage()).contains("contract").contains("test");
    }

    @Test
    void traditional_layout_discover(@TempDir Path tmp) throws Exception {
        Path testJava = tmp.resolve("src/test/java");
        Files.createDirectories(testJava);
        Files.writeString(testJava.resolve("UTest.java"), "class UTest {}");
        Path intJava = tmp.resolve("src/integration/java");
        Files.createDirectories(intJava);
        Files.writeString(intJava.resolve("ITest.java"), "class ITest {}");
        assertThat(TestSuites.discover(tmp, false)).containsExactly("test", "integration");
    }

    @Test
    void collect_scala_simple_and_traditional(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("test/src"));
        Files.writeString(tmp.resolve("test/src/HelloSpec.scala"), "class HelloSpec");
        assertThat(TestSuites.collectScalaSources(tmp, true, List.of("test")))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("HelloSpec.scala");

        Path trad = tmp.resolve("trad");
        Files.createDirectories(trad.resolve("src/test/scala"));
        Files.writeString(trad.resolve("src/test/scala/T.scala"), "class T");
        assertThat(TestSuites.collectScalaSources(trad, false, List.of("test")))
                .extracting(p -> p.getFileName().toString())
                .containsExactly("T.scala");
    }

    @Test
    void a_class_pattern_suite_is_available_and_compiles_the_default_suites_roots(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/test/java"));
        Files.writeString(tmp.resolve("src/test/java/FooTest.java"), "class FooTest {}");
        Map<String, ClassSuite> patterns = Map.of("integration", ClassSuite.of(List.of("*IT")));

        assertThat(TestSuites.available(tmp, false, patterns)).containsExactly("test", "integration");
        assertThat(TestSuites.available(tmp, false, Map.of())).containsExactly("test");
        assertThat(TestSuites.compiled(tmp, false, List.of("integration"), patterns))
                .as("no directory of its own: the default suite's roots alone, the key a plain run compiles")
                .containsExactly("test");
        assertThat(TestSuites.compiled(tmp, false, List.of("test", "integration"), patterns))
                .containsExactly("test");
        assertThat(TestSuites.compiled(tmp, false, List.of("integration"), Map.of()))
                .as("a directory suite compiles alone, as it always did")
                .containsExactly("integration");

        Files.createDirectories(tmp.resolve("src/integration/java"));
        Files.writeString(tmp.resolve("src/integration/java/StackIT.java"), "class StackIT {}");
        assertThat(TestSuites.available(tmp, false, patterns)).containsExactly("test", "integration");
        assertThat(TestSuites.compiled(tmp, false, List.of("integration"), patterns))
                .containsExactly("test", "integration");
    }

    /** The stamp identity names the suites the selection runs, so a guard run never replays a plain one. */
    @Test
    void a_guard_selection_with_no_named_suites_has_the_guard_suites_identity() {
        TestSelection guard = TestSelection.of(List.of(), false, List.of(), List.of(), false, true);
        TestSelection explicit =
                TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);

        assertThat(guard.identityToken()).isEqualTo(explicit.identityToken()).startsWith("suites=test,integration;");
        assertThat(guard.identityToken()).isNotEqualTo(TestSelection.DEFAULT.identityToken());
        assertThat(TestSelection.DEFAULT.identityToken()).startsWith("suites=test;");
    }
}
