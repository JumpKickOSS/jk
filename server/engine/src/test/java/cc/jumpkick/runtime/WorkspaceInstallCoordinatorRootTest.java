// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk install} on a workspace whose root carries no sources of its own. Such a root is still
 * a build unit — it runs the workspace's build logic after every member — but it compiles and
 * packages nothing, so its plan has no {@code package-jar} for a {@code cache-install} terminal to
 * require. Appending one anyway threw out of {@code BuildPlan.build()} before a single module
 * started, and a workspace install that fails during plan assembly has no module to blame.
 */
class WorkspaceInstallCoordinatorRootTest {

    @TempDir
    Path tmp;

    @Test
    void coordinator_root_install_plan_assembles_and_has_no_cache_install() throws Exception {
        Path lib = member("lib");
        Path root = coordinatorRoot("lib");
        var rootUnit = unit(root, "ex:ws");

        BuildPlan plan = assertThatInstallPlanBuilds(rootUnit, Set.of(root, lib));
        assertThat(stepNames(plan))
                .as("a coordinator root packages nothing, so it publishes nothing")
                .doesNotContain(TaskNames.CACHE_INSTALL)
                .doesNotContain(TaskNames.PACKAGE_JAR);
    }

    @Test
    void member_install_plan_still_ends_at_cache_install() throws Exception {
        Path lib = member("lib");
        Path root = coordinatorRoot("lib");
        var libUnit = unit(lib, "ex:lib");

        BuildPlan plan = assertThatInstallPlanBuilds(libUnit, Set.of(root, lib));
        assertThat(stepNames(plan)).contains(TaskNames.PACKAGE_JAR, TaskNames.CACHE_INSTALL);
    }

    @Test
    void install_terminals_skip_the_coordinator_root() throws Exception {
        Path lib = member("lib");
        Path root = coordinatorRoot("lib");

        Set<Path> terminals = WorkspacePreflightPhase.terminalTargetDirs(
                List.of(unit(root, "ex:ws"), unit(lib, "ex:lib")), installRequest());
        assertThat(terminals)
                .as("the forecast must expect a terminal exactly where assemblePlan plans one")
                .containsExactly(lib);
    }

    private BuildPlan assertThatInstallPlanBuilds(BuildGraph.BuildUnit u, Set<Path> moduleDirs) {
        assertThatCode(() -> WorkspacePreparePhase.assemblePlan(u, installRequest(), moduleDirs, false))
                .doesNotThrowAnyException();
        return WorkspacePreparePhase.assemblePlan(u, installRequest(), moduleDirs, false);
    }

    private WorkspaceRequest installRequest() {
        return new WorkspaceRequest(tmp, tmp.resolve("cache"), null, 0, null, true, false, 0, null, true, true)
                .withSpec(WorkspaceSpec.install(Set.of(), Map.of(), null));
    }

    private static Set<String> stepNames(BuildPlan plan) {
        return plan.steps().stream().map(Task::name).collect(Collectors.toSet());
    }

    private static BuildGraph.BuildUnit unit(Path dir, String coord) throws Exception {
        JkBuild build = JkBuildParser.parse(dir.resolve("jk.toml"));
        return new BuildGraph.BuildUnit(dir, build, coord, BuildGraph.Origin.MODULE);
    }

    /** The workspace root itself: a manifest, a lock, and deliberately no {@code src/}. */
    private Path coordinatorRoot(String... modules) throws Exception {
        String list = List.of(modules).stream().map(m -> "\"" + m + "\"").collect(Collectors.joining(", "));
        Files.writeString(tmp.resolve("jk.toml"), """
                group = "ex"
                name = "ws"
                version = "1.0"
                java = 25

                [workspace]
                modules = [%s]
                """.formatted(list));
        Files.writeString(tmp.resolve("jk-lock.toml"), "schema = 1\n");
        return tmp;
    }

    private Path member(String name) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        Files.writeString(
                dir.resolve("src/main/java/ex/Main.java"),
                "package ex; public class Main { public static void main(String[] a) {} }\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0"
                java = 25
                """.formatted(name));
        return dir;
    }
}
