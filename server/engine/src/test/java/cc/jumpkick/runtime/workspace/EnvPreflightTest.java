// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module whose {@code [env] require} names are not all set fails before it compiles, naming each
 * variable and the manifest line requiring it, on every goal that runs its tests or launches it.
 */
class EnvPreflightTest {

    private static final String NAME = "JK_PREFLIGHT_PROBE_TOKEN";

    @TempDir
    Path tmp;

    private BuildGraph.BuildUnit unit;

    @BeforeEach
    void module() throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["core"]
                """);
        Path dir = Files.createDirectories(tmp.resolve("core"));
        Path src = Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(src.resolve("Core.java"), "package ex; public class Core {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "core"
                version = "1.0.0"
                java = 25

                [env]
                optional = ["FEATURE_X"]
                require = ["%s"]
                """.formatted(NAME));
        unit = new BuildGraph.BuildUnit(
                dir, JkBuildParser.parse(dir.resolve("jk.toml")), "ex:core", BuildGraph.Origin.MODULE);
    }

    @Test
    void an_unset_required_name_fails_the_module_before_it_compiles_naming_the_manifest_line() throws Exception {
        BuildPlan plan = planIn(Map.of("PATH", "/bin"), WorkspaceTarget.TEST, false);

        assertThat(plan.steps()).extracting(Task::name).containsExactly(TaskNames.ENV_CHECK);
        BuildPlanResult result = plan.run();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(EnvPreflight.CODE);
            assertThat(d.key()).isEqualTo(NAME);
            assertThat(d.message()).isEqualTo(NAME + " is not set; core/jk.toml:8 requires it");
        });
    }

    @Test
    void a_set_required_name_plans_the_build() throws Exception {
        BuildPlan plan = planIn(Map.of("PATH", "/bin", NAME, "s3cr3t-value"), WorkspaceTarget.TEST, false);

        assertThat(plan.steps()).extracting(Task::name).doesNotContain(TaskNames.ENV_CHECK);
        assertThat(plan.steps()).extracting(Task::name).contains(TaskNames.RUN_TESTS);
    }

    @Test
    void a_goal_that_runs_no_test_and_launches_nothing_does_not_check() throws Exception {
        Map<String, String> unset = Map.of("PATH", "/bin");
        for (BuildPlan plan :
                List.of(planIn(unset, WorkspaceTarget.COMPILE, false), planIn(unset, WorkspaceTarget.PACKAGE, true))) {
            assertThat(plan.steps()).extracting(Task::name).doesNotContain(TaskNames.ENV_CHECK);
        }
        assertThat(planIn(unset, WorkspaceTarget.PACKAGE, false).steps())
                .extracting(Task::name)
                .containsExactly(TaskNames.ENV_CHECK);
        assertThat(planIn(unset, WorkspaceTarget.RUN, true).steps())
                .as("a launch checks too")
                .extracting(Task::name)
                .containsExactly(TaskNames.ENV_CHECK);
    }

    private BuildPlan planIn(Map<String, String> shell, WorkspaceTarget target, boolean skipTests) throws Exception {
        WorkspaceRequest request = new WorkspaceRequest(
                        tmp, tmp.resolve("cache"), null, 0, null, skipTests, false, 0, null, false, false)
                .withSpec(WorkspaceSpec.of(target));
        return RequestShell.where(shell, () -> ModulePipeline.planned(
                        unit, request, Set.of(tmp, unit.dir()), false, Set.of(), Set.of(), SiblingArtifacts.NONE)
                .plan());
    }
}
