// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The goal a graph question submits for tests that never compiled, run for real: the main and
 * test classes are written, no test runs, and the classes are recorded as the sources' own, which
 * is what the graph reads their facts by.
 */
@Tag("integration")
class TestCompileGoalE2eTest {

    @Test
    void the_test_compile_goal_writes_the_test_classes_and_runs_no_test(@TempDir Path tmp) throws Exception {
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path project = Files.createDirectories(tmp.resolve("calc"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "demo"
                name = "calc"
                version = "1.0.0"
                java = 25
                """);
        Path main = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(main.resolve("Calc.java"), """
                package demo;

                public class Calc {
                    public int doubled(int n) {
                        return n * 2;
                    }
                }
                """);
        Path test = Files.createDirectories(project.resolve("src/test/java/demo"));
        Files.writeString(test.resolve("CalcTest.java"), """
                package demo;

                class CalcTest {
                    boolean doubledReturnsTwiceTheValue() {
                        return new Calc().doubled(2) == 4;
                    }
                }
                """);
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(LockPlans.lockBuildPlan(project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                        .run()
                        .errors())
                .isEmpty();

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(project, cache, null, 0, null, false, false, 0, null, false, false)
                        .withSpec(WorkspaceSpec.of(WorkspaceTarget.TEST_COMPILE)),
                new WorkspaceBuildListener() {});

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(project.resolve("target/classes/demo/Calc.class")).isRegularFile();
        assertThat(project.resolve("target/test-classes/demo/CalcTest.class")).isRegularFile();
        assertThat(project.resolve("target/surefire-reports")).as("no test ran").doesNotExist();
        assertThat(ModuleInputProvenance.staleSources(project, project, build))
                .as("the classes are vouched for as the sources' own")
                .isEmpty();
    }
}
