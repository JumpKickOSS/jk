// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test-fixtures source set is not a test suite.
 *
 * <p>Discovery listed {@code src/} and excluded only {@code main} and {@code test}, so
 * {@code src/fixtures/java} came back as a suite. Fixtures have their own step
 * ({@code compile-test-fixtures}) and their own output, so folding them into the suite set means
 * every consumer of {@link TestSuites#discover} collects them as test sources.
 *
 * <p>What that cost is worth stating, because the symptom was nowhere near the cause: the ETA
 * forecast collects test sources per discovered suite while the live compile takes one source root,
 * so the two built different {@code compile-test} action keys. The forecast's key always missed,
 * Zinc reported every test source invalidated, {@code run-tests} was forecast to re-run for modules
 * the build finished in milliseconds, and a phantom suite became the estimate's long pole —
 * 31.2 s predicted against 17.5 s actual on this repo. A real suite must still be found, which is
 * why this pins both directions.
 */
class TestSuitesFixturesNotASuiteTest {

    @TempDir
    Path module;

    private void src(String suite, String file) throws Exception {
        Path dir = module.resolve("src").resolve(suite).resolve("java").resolve("cc");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(file), "package cc; class " + file.replace(".java", "") + " {}\n");
    }

    @Test
    void fixtures_are_not_discovered_as_a_suite() throws Exception {
        src("test", "SomeTest.java");
        src("fixtures", "FakeJdk.java");

        assertThat(TestSuites.discover(module, false))
                .as("fixtures are a source set with their own compile step, not a test suite")
                .containsExactly(TestSuites.DEFAULT);
        assertThat(TestSuites.collectJavaSources(module, false, TestSuites.discover(module, false)))
                .as("and so their sources must not reach a suite-based collection")
                .noneSatisfy(p -> assertThat(p.toString()).contains("fixtures"));
    }

    @Test
    void a_real_extra_suite_is_still_discovered() throws Exception {
        src("test", "SomeTest.java");
        src("fixtures", "FakeJdk.java");
        src("integration", "WireIT.java");

        assertThat(TestSuites.discover(module, false))
                .as("excluding fixtures must not cost us real suites")
                .containsExactly(TestSuites.DEFAULT, "integration");
    }

    @Test
    void fixtures_alone_means_no_suites() throws Exception {
        src("fixtures", "FakeJdk.java");

        assertThat(TestSuites.discover(module, false)).isEmpty();
    }
}
