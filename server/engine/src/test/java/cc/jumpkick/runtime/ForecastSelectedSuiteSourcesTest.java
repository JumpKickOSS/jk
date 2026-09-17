// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * compile-test's forecast key hashes the sources of the selected suites, the same list the build
 * compiles. {@link TestSelection#DEFAULT} is the {@code test} suite alone, so a forecast over every
 * discovered suite would hash a strictly larger list than the build in any module with a second
 * suite — a phantom compile-test on every plain {@code jk build}. The estimate side keeps counting
 * every suite; only the key-bearing list narrows.
 */
class ForecastSelectedSuiteSourcesTest {

    private static final JkBuild PROJECT = JkBuild.builder(
                    Project.builder("ex", "m", "1.0").jdkMajor(25).java(25).build())
            .build();

    @Test
    void theDefaultSelectionHashesTheTestSuiteAlone(@TempDir Path module) throws Exception {
        Path unit = write(module, "src/test/java/app/WidgetTest.java");
        Path integration = write(module, "src/integration/java/app/WidgetIT.java");

        assertThat(selected(module, TestSelection.DEFAULT))
                .as("the build compiles the test suite, so the forecast may not hash more")
                .containsExactly(unit);
        assertThat(TestSupport.collectAllSuiteTestSources(module, false))
                .as("the estimate side is unchanged — a count wants every suite on disk")
                .containsExactlyInAnyOrder(unit, integration);
    }

    @Test
    void aWidenedSelectionHashesEverySuiteItSelected(@TempDir Path module) throws Exception {
        Path unit = write(module, "src/test/java/app/WidgetTest.java");
        Path integration = write(module, "src/integration/java/app/WidgetIT.java");
        TestSelection all = TestSelection.of(List.of(), true, List.of(), List.of());

        assertThat(selected(module, all)).containsExactlyInAnyOrder(unit, integration);
        assertThat(selected(module, TestSelection.of(List.of("integration"), false, List.of(), List.of())))
                .containsExactly(integration);
    }

    /** An unresolvable selection is the build's error or skip, so the key falls back to the tree. */
    @Test
    void anUnresolvableSelectionFallsBackToEveryDiscoveredSuite(@TempDir Path module) throws Exception {
        Path unit = write(module, "src/test/java/app/WidgetTest.java");
        Path integration = write(module, "src/integration/java/app/WidgetIT.java");
        TestSelection missing = TestSelection.of(List.of("nope"), false, List.of(), List.of());

        assertThat(selected(module, missing)).containsExactlyInAnyOrder(unit, integration);
    }

    @Test
    void aModuleWithNoSuitesOnDiskStillResolvesToTheDefaultRoot(@TempDir Path module) throws Exception {
        assertThat(selected(module, TestSelection.DEFAULT)).isEmpty();
    }

    /** The forecast's source list: the selected suites through the factory the build uses. */
    private static List<Path> selected(Path module, TestSelection selection) throws Exception {
        return PlannerTest.TestSources.collect(
                        PROJECT,
                        module,
                        false,
                        TestSupport.selectedSuites(module, false, selection),
                        BuildLayout.of(module, PROJECT),
                        null)
                .all();
    }

    private static Path write(Path module, String rel) throws Exception {
        Path f = module.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\nclass T {}\n");
        return f;
    }
}
