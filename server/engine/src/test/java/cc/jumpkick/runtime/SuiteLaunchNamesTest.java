// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.DeclaredSuite;
import cc.jumpkick.model.SuiteSettings;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.test.JUnitLauncher;
import cc.jumpkick.test.SuiteFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forecast reads a class's records by the launch they were made in, so the launches it names
 * for a selection are the ones the run starts, by the run's own names: one launch is named alone,
 * a suite with settings of its own launches apart, and directory suites run apart each name theirs.
 */
class SuiteLaunchNamesTest {

    @Test
    void the_forecast_names_each_launch_the_run_starts(@TempDir Path m) throws Exception {
        source(m, "test", "ATest");
        Map<String, DeclaredSuite> declared = new LinkedHashMap<>();
        declared.put("tree", new DeclaredSuite(List.of(), List.of("tree"), List.of(), List.of()));
        declared.put(
                "integration",
                new DeclaredSuite(
                        List.of(),
                        List.of("integration"),
                        List.of(),
                        List.of(),
                        new SuiteSettings(Map.of("A", "1"), List.of(), Map.of(), null),
                        false));

        assertThat(namesAgree(m, List.of(TestSuites.DEFAULT), declared)).containsExactly("");
        assertThat(namesAgree(m, List.of(TestSuites.DEFAULT, "tree", "integration"), declared))
                .containsExactly("test:integration", "test:test", "test:tree");
        assertThat(SuiteResources.launchNames(
                        m,
                        false,
                        TestSelection.of(
                                List.of(TestSuites.DEFAULT, "tree", "integration"), false, List.of(), List.of()),
                        declared))
                .as("as the forecast resolves the selection")
                .containsExactly("test:integration", "test:test", "test:tree");
    }

    @Test
    void directory_suites_run_apart_name_a_launch_each(@TempDir Path m) throws Exception {
        source(m, "test", "ATest");
        source(m, "integration", "AIT");

        assertThat(namesAgree(m, List.of(TestSuites.DEFAULT, "integration"), Map.of()))
                .containsExactly("integration:integration,test", "test:integration,test");
    }

    /** The names the forecast gives a run of {@code suites}, checked against the names the run's launches take. */
    private static Set<String> namesAgree(Path m, List<String> suites, Map<String, DeclaredSuite> declared)
            throws Exception {
        List<String> compiled = TestSuites.compiled(m, false, suites, declared);
        SuiteResources resources = SuiteResources.of(m, false, compiled, List.of());
        SuiteFilter filter = SuiteFilter.of(m, false, suites, declared);
        Set<String> started = new TreeSet<>();
        resources.launch(
                m.resolve("target/test-classes"),
                filter,
                List.of(),
                List.of(),
                null,
                (name, settings) -> {
                    started.add(name);
                    return new JUnitLauncher();
                },
                name -> true,
                List.of(),
                (one, cp, launch) -> new TestSummary(0, 0, 0, 0, List.of()));
        Set<String> named = resources.launchNames(filter);
        assertThat(named).as("the launches the run started").isEqualTo(started);
        return named;
    }

    private static void source(Path m, String suite, String cls) throws Exception {
        Path dir = Files.createDirectories(m.resolve("src/" + suite + "/java/demo"));
        Files.writeString(dir.resolve(cls + ".java"), "package demo; class " + cls + " {}\n");
    }
}
