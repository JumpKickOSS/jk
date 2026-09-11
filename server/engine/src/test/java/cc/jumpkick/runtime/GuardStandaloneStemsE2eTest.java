// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk guard} on a standalone project, in-process: the build the guard rides on runs the
 * project's {@code after-resources} stem, and the guard's own anchor at the same directory accepts
 * that stem instead of refusing it as a workspace root's.
 */
@Tag("integration")
class GuardStandaloneStemsE2eTest {

    @Test
    void a_standalone_project_with_a_module_stem_passes_the_guard(@TempDir Path tmp) throws Exception {
        Path project = scaffold(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        BuildPlanResult r = guard(project, cache);

        assertThat(r.errors()).isEmpty();
        assertThat(r.success()).isTrue();
        assertThat(status(r, TaskNames.BUILD_LOGIC_GUARD))
                .as("the guard anchor ran over the module stem without a refusal")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(status(r, TaskNames.GUARD_TREE)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(project.resolve("target/classes/main/stamp.txt"))
                .as("the after-resources stem ran on the build the guard rides on")
                .hasContent("ok");
    }

    private static Path scaffold(Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "proj"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                """);
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(
                src.resolve("App.java"),
                "package demo;\n\npublic final class App {\n    public static void main(String[] a) {}\n}\n");
        Files.writeString(project.resolve(GuardsPresence.RULES_FILE), """
                [guards.no-banned]
                kind       = "forbid"
                signatures = ["demo.Banned"]
                instead    = "nothing"
                why        = "a test rule"
                """);
        Files.createDirectories(project.resolve(".jk"));
        BuildLogicFixtures.writeStampGroovy(project.resolve(".jk/after-resources.groovy"));
        return project;
    }

    /** What the {@code guard} verb sends: a build with tests skipped and the guard selection on. */
    private static BuildPlanResult guard(Path project, Path cache) throws Exception {
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();
        Session session = SessionContext.current()
                .withTestSelection(TestSelection.of(List.of(), false, List.of(), List.of(), false, true));
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                session);
        return SessionContext.where(session, () -> BuildPlanner.fullPlan(in).run());
    }

    private static TaskStatus status(BuildPlanResult r, String step) {
        return r.steps().stream()
                .filter(s -> s.name().equals(step))
                .findFirst()
                .orElseThrow(() -> new AssertionError(step + " not in the plan: "
                        + r.steps().stream().map(s -> s.name()).toList()))
                .status();
    }
}
