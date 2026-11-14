// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.TestSuites;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A request that names no tags (an MCP or HTTP build, a BSP run) under a profile whose {@code
 * all-suites = true} is stamped as every suite ({@link TestStampInputs#effectiveSelection}). The
 * suites the plan compiles and runs must be the same ones, or the run of the default suite alone
 * stores a green marker the CLI's {@code jk test -p ci} then replays.
 */
class ProfileAllSuitesPlanTest {

    @Test
    void the_plan_runs_the_suites_the_stamp_names(@TempDir Path dir) throws Exception {
        Path manifest = Files.writeString(dir.resolve("jk.toml"), """
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [profiles.ci]
                all-suites = true

                [test.suites.integration]
                tags = ["integration"]
                """);
        Path src = dir.resolve("src/test/java/app/CartTest.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app;\nclass CartTest {}\n");
        JkBuild project = JkBuildParser.parseLocal(manifest);

        TestSelection stamped = TestStampInputs.effectiveSelection(TestSelection.DEFAULT, dir, "ci");
        assertThat(stamped.identityToken())
                .as("the stamp's sel: token is the one jk test -p ci stamps")
                .startsWith("suites=*");

        BuildPlanner.Inputs in =
                TestInputs.of(dir, dir.resolve("cache"), Session.defaults()).withProfileName("ci");
        List<String> planned = PlannerTest.resolveSuites(in, false, project).suites();
        List<String> wanted = TestSuites.resolve(
                        stamped, dir, false, project.build().testSuites())
                .suites();
        assertThat(wanted).containsExactly(TestSuites.DEFAULT, "integration");
        assertThat(planned)
                .as("compile-test and the suite filter must resolve what the stamp says ran")
                .isEqualTo(wanted);
    }
}
