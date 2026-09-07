// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The output lane end to end: it runs after packaging, a clean jar is a green step, an unchanged
 * jar is a verdict hit, and a repackage re-evaluates.
 */
class GuardOutputLaneE2eTest {

    @Test
    void output_lane_runs_after_packaging_caches_and_reruns_on_a_repackage(@TempDir Path tmp) throws Exception {
        Path project = scaffold(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));

        BuildPlanResult first = build(project, cache);
        assertThat(first.success()).as("errors: " + first.errors()).isTrue();
        assertThat(status(first, TaskNames.GUARD_OUTPUT)).isEqualTo(TaskStatus.SUCCESS);
        try (var files = Files.walk(project.resolve("target"))) {
            assertThat(files.filter(f -> f.getFileName().toString().equals("proj-1.0.0.jar"))
                            .findFirst())
                    .as("the jar the lane read")
                    .isPresent();
        }

        BuildPlanResult second = build(project, cache);
        assertThat(second.success()).isTrue();
        assertThat(status(second, TaskNames.GUARD_OUTPUT))
                .as("unchanged artefacts, rules and baseline: a verdict hit")
                .isEqualTo(TaskStatus.SKIPPED);

        Files.writeString(
                project.resolve("src/main/java/demo/More.java"), "package demo;\n\npublic final class More {}\n");
        BuildPlanResult third = build(project, cache);
        assertThat(third.success()).as("errors: " + third.errors()).isTrue();
        assertThat(status(third, TaskNames.GUARD_OUTPUT))
                .as("a repackaged jar re-runs the lane")
                .isEqualTo(TaskStatus.SUCCESS);
    }

    private static TaskStatus status(BuildPlanResult r, String step) {
        return r.steps().stream()
                .filter(s -> s.name().equals(step))
                .findFirst()
                .orElseThrow(() -> new AssertionError(step + " not in "
                        + r.steps().stream().map(s -> s.name()).toList()))
                .status();
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
                [guards.jar-shape]
                kind = "output"
                jar  = { require-entries = ["META-INF/MANIFEST.MF", "demo/App.class"], forbid-entries = ["**/*.kt"] }
                why  = "a jar is a contract"
                [guards.published-pom]
                kind = "output"
                pom  = { no-unspecified = true, groups = ["com.example"] }
                why  = "a POM with an unspecified coordinate is an artifact nobody can depend on"
                """);
        return project;
    }

    private static BuildPlanResult build(Path project, Path cache) throws Exception {
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();
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
                SessionContext.current());
        return BuildPlanner.fullPlan(in).run();
    }
}
