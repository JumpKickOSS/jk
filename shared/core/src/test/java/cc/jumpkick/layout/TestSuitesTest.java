// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void traditional_layout_discover(@TempDir Path tmp) throws Exception {
        Path testJava = tmp.resolve("src/test/java");
        Files.createDirectories(testJava);
        Files.writeString(testJava.resolve("UTest.java"), "class UTest {}");
        Path intJava = tmp.resolve("src/integration/java");
        Files.createDirectories(intJava);
        Files.writeString(intJava.resolve("ITest.java"), "class ITest {}");
        assertThat(TestSuites.discover(tmp, false)).containsExactly("test", "integration");
    }
}
