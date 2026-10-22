// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ModulePipeline;
import cc.jumpkick.runtime.workspace.TrainPlans;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainPlansTest {

    @Test
    void train_extends_the_build_and_runs_no_tests(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.example"
                name = "t"
                version = "0.1.0"
                java = 25

                [application]
                main = "com.example.Main"

                [train]
                """);
        Files.writeString(dir.resolve("jk-lock.toml"), "schema = 1\n");
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        WorkspaceRequest request = new WorkspaceRequest(
                        dir, dir.resolve("cache"), null, 0, null, false, false, 0, null, false, false)
                .withSpec(WorkspaceSpec.of(WorkspaceTarget.TRAIN));
        BuildPlan plan = ModulePipeline.standalone(
                request, TrainPlans.tail(build, null, Path.of(System.getProperty("java.home")), null, false));
        assertThat(plan.steps().stream().map(s -> s.name())).contains(TaskNames.TRAIN, TaskNames.PACKAGE_JAR);
        assertThat(plan.steps().stream().map(s -> s.name()))
                .as("train runs no tests unless asked: its goal's policy, not a different plan")
                .doesNotContain(TaskNames.NATIVE_IMAGE, TaskNames.RUN_TESTS);
        assertThat(plan.steps().getLast().name()).isEqualTo(TaskNames.TRAIN);
    }
}
