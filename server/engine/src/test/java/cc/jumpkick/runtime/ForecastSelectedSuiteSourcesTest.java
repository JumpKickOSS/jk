// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * compile-test's forecast key hashes a source list, and the build hashes the one it actually
 * compiled: the selected suites. {@link TestSelection#DEFAULT} is the {@code test} suite alone, so
 * a forecast over every discovered suite hashed a strictly larger list than the build in any module
 * with a second suite — a phantom compile-test on every plain {@code jk build}. The estimate side
 * keeps counting every suite; only the key-bearing list narrows.
 */
class ForecastSelectedSuiteSourcesTest {

    @Test
    void theDefaultSelectionHashesTheTestSuiteAlone(@TempDir Path module) throws Exception {
        Path unit = write(module, "src/test/java/app/WidgetTest.java");
        Path integration = write(module, "src/integration/java/app/WidgetIT.java");

        assertThat(TestSupport.collectSelectedSuiteTestSources(module, false, TestSelection.DEFAULT))
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

        assertThat(TestSupport.collectSelectedSuiteTestSources(module, false, all))
                .containsExactlyInAnyOrder(unit, integration);
        assertThat(TestSupport.collectSelectedSuiteTestSources(
                        module, false, TestSelection.of(List.of("integration"), false, List.of(), List.of())))
                .containsExactly(integration);
    }

    /** An unresolvable selection is the build's error or skip, so the key falls back to the tree. */
    @Test
    void anUnresolvableSelectionFallsBackToEveryDiscoveredSuite(@TempDir Path module) throws Exception {
        Path unit = write(module, "src/test/java/app/WidgetTest.java");
        Path integration = write(module, "src/integration/java/app/WidgetIT.java");
        TestSelection missing = TestSelection.of(List.of("nope"), false, List.of(), List.of());

        assertThat(TestSupport.collectSelectedSuiteTestSources(module, false, missing))
                .containsExactlyInAnyOrder(unit, integration);
    }

    @Test
    void aModuleWithNoSuitesOnDiskStillResolvesToTheDefaultRoot(@TempDir Path module) throws Exception {
        assertThat(TestSupport.collectSelectedSuiteTestSources(module, false, TestSelection.DEFAULT))
                .isEmpty();
    }

    private static Path write(Path module, String rel) throws Exception {
        Path f = module.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "package app;\nclass T {}\n");
        return f;
    }
}
