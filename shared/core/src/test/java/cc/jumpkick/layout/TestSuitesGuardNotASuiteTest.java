// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard suite ({@code src/guard/java}) is a source set with its own step ({@code compile-guard})
 * and its own runner (the guard lanes), not a test suite: discovery never returns it, so {@code jk
 * test}, {@code --all} and the gate never collect its sources, and asking for it by name says where
 * it runs.
 */
class TestSuitesGuardNotASuiteTest {

    @TempDir
    Path module;

    private void src(String suite, String file) throws Exception {
        Path dir = module.resolve("src").resolve(suite).resolve("java").resolve("cc");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), "package cc; class " + file.replace(".java", "") + " {}\n");
    }

    @Test
    void the_guard_suite_is_not_discovered_and_its_sources_are_its_own() throws Exception {
        src("test", "SomeTest.java");
        src("guard", "HouseRules.java");
        src("integration", "WireIT.java");
        assertThat(TestSuites.discover(module, false)).containsExactly(TestSuites.DEFAULT, "integration");
        assertThat(TestSuites.collectJavaSources(module, false, TestSuites.discover(module, false)))
                .noneSatisfy(p -> assertThat(module.relativize(p).toString()).contains("guard"));
        assertThat(TestSuites.hasGuardSuite(module, false)).isTrue();
        assertThat(TestSuites.guardSources(module, false))
                .singleElement()
                .satisfies(p -> assertThat(p.getFileName().toString()).isEqualTo("HouseRules.java"));
        assertThat(TestSuites.hasGuardSuite(module.resolve("elsewhere"), false)).isFalse();
    }

    @Test
    void asking_for_the_guard_suite_by_name_says_where_it_runs() throws Exception {
        src("test", "SomeTest.java");
        src("guard", "HouseRules.java");
        TestSelection.Resolved r = TestSelection.of(List.of(TestSuites.GUARD), false, List.of(), List.of())
                .resolve(TestSuites.discover(module, false));
        assertThat(r.ok()).isFalse();
        assertThat(r.missingMessage())
                .contains("guard lanes")
                .contains("jk guard")
                .doesNotContain("unknown test suite");
        TestSelection.Resolved all =
                TestSelection.of(List.of(), true, List.of(), List.of()).resolve(TestSuites.discover(module, false));
        assertThat(all.suites()).containsExactly(TestSuites.DEFAULT);
    }
}
