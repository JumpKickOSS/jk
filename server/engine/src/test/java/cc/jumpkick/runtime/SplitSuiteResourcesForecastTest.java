// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.layout.TestSuites;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run that compiles two directory suites mirrors no suite's resources into target/test-classes
 * ({@link SuiteResources#mirror}): each suite runs with its own roots. compile-test's forecast asks
 * the mirror's own question, so after such a build it does not predict "test resources changed" for
 * a step the build never re-runs for that reason, and a run of one directory suite is checked
 * against that suite's resources, not the default suite's.
 */
class SplitSuiteResourcesForecastTest {

    @Test
    void the_forecast_checks_only_the_resources_the_build_mirrors(@TempDir Path dir) throws Exception {
        Path res = Files.createDirectories(ModuleLayout.testResourcesDir(dir, false));
        Files.writeString(res.resolve("a.txt"), "a");
        Path testClasses = Files.createDirectories(dir.resolve("target/test-classes"));

        assertThat(SuiteResources.mirroredResourcesOutOfSync(
                        dir, false, List.of(TestSuites.DEFAULT, TestSuites.INTEGRATION), testClasses))
                .as("two directory suites run apart: the build copies neither's resources into test-classes")
                .isFalse();
        assertThat(SuiteResources.mirroredResourcesOutOfSync(dir, false, List.of(TestSuites.INTEGRATION), testClasses))
                .as("a run of the integration suite alone mirrors its resources, not src/test/resources")
                .isFalse();
        assertThat(SuiteResources.mirroredResourcesOutOfSync(dir, false, List.of(TestSuites.DEFAULT), testClasses))
                .as("a run of the default suite alone mirrors src/test/resources, and a.txt is not there")
                .isTrue();
    }
}
