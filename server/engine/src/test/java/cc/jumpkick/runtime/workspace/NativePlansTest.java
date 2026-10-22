// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.TestClassMatch;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk native} plans: the build through PACKAGE, tests included, then native-image for a
 * selected module the client resolved a GraalVM home for; any other module stays at its package.
 */
class NativePlansTest {

    @TempDir
    Path tmp;

    @Test
    void module_with_graal_home_runs_its_tests_then_native_image() throws Exception {
        Path dir = module("app", true);
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        BuildPlan plan = nativePlan(dir, Set.of(dir), Map.of(dir, graal), false);
        Set<String> names = names(plan);
        assertThat(names).contains(TaskNames.PACKAGE_JAR, TaskNames.RUN_TESTS, TaskNames.NATIVE_IMAGE);
        assertThat(plan.steps().getLast().name())
                .as("native-image is the terminal, and the suite stays upstream of it")
                .isEqualTo(TaskNames.NATIVE_IMAGE);
    }

    @Test
    void skip_tests_drops_only_the_suite() throws Exception {
        Path dir = module("app", true);
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        Set<String> names = names(nativePlan(dir, Set.of(dir), Map.of(dir, graal), true));
        assertThat(names).contains(TaskNames.PACKAGE_JAR, TaskNames.NATIVE_IMAGE);
        assertThat(names).doesNotContain(TaskNames.RUN_TESTS);
    }

    @Test
    void a_module_the_request_does_not_select_gets_no_native_image() throws Exception {
        Path dir = module("lib", false);
        Path graal = Files.createDirectories(tmp.resolve("graal2"));
        Set<String> names = names(nativePlan(dir, Set.of(tmp.resolve("other")), Map.of(dir, graal), true));
        assertThat(names).contains(TaskNames.PACKAGE_JAR);
        assertThat(names).doesNotContain(TaskNames.NATIVE_IMAGE);
    }

    @Test
    void library_without_graal_home_does_not_demand_native_image() throws Exception {
        Path dir = module("core", false);
        Set<String> names = names(nativePlan(dir, Set.of(dir), Map.of(), true));
        assertThat(names).contains(TaskNames.PACKAGE_JAR);
        assertThat(names).doesNotContain(TaskNames.NATIVE_IMAGE);
    }

    private BuildPlan nativePlan(Path dir, Set<Path> selected, Map<Path, Path> graal, boolean skipTests)
            throws Exception {
        WorkspaceRequest request = new WorkspaceRequest(
                        dir, cache(), null, 0, null, skipTests, false, 0, null, false, false)
                .withSpec(WorkspaceSpec.nativeImage(selected, graal, null, List.of()));
        return ModulePipeline.plan(
                new BuildGraph.BuildUnit(dir, parse(dir), "ex:" + dir.getFileName(), BuildGraph.Origin.MODULE),
                request,
                Set.of(dir),
                false);
    }

    private static Set<String> names(BuildPlan plan) {
        return plan.steps().stream().map(s -> s.name()).collect(Collectors.toSet());
    }

    private Path cache() throws Exception {
        Path c = tmp.resolve("cache-" + System.nanoTime());
        Files.createDirectories(c);
        return c;
    }

    private static JkBuild parse(Path dir) throws Exception {
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }

    private Path module(String name, boolean withMain) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir.resolve("src/main/java/ex"));
        if (withMain) {
            Files.writeString(
                    dir.resolve("src/main/java/ex/Main.java"),
                    "package ex; public class Main { public static void main(String[] a) {} }\n");
        } else {
            Files.writeString(dir.resolve("src/main/java/ex/Lib.java"), "package ex; public class Lib {}\n");
        }
        String app = withMain ? """

                [application]
                main = "ex.Main"
                """ : "";
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "%s"
                version = "1.0"
                java = 25
                %s
                """.formatted(name, app));
        Files.writeString(dir.resolve("jk-lock.toml"), """
                schema = 1
                """);
        return dir;
    }

    @Test
    void failure_exit_code_maps_native_main_misconfig_to_usage() {
        var plan =
                BuildPlan.builder("native").stateKeys(BuildPlanner.TEST_RESULT).build();
        var usage = new BuildPlanResult(
                "native",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("native-image", "native", "main class no.Such.Class not found")),
                false);
        assertThat(NativePlans.failureExitCode(plan, usage)).isEqualTo(Exit.USAGE);
        var plain = new BuildPlanResult("native", false, Duration.ZERO, List.of(), List.of(), List.of(), false);
        assertThat(NativePlans.failureExitCode(plan, plain)).isEqualTo(1);
    }

    @Test
    void failure_exit_code_maps_image_no_main_to_usage() {
        var plan = BuildPlan.builder("image").build();
        var noMain = new BuildPlanResult(
                "image",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("image-plan", "no-main", "no main class - pass --main")),
                false);
        assertThat(NativePlans.failureExitCode(plan, noMain)).isEqualTo(Exit.USAGE);
    }

    @Test
    void failure_exit_code_maps_a_class_selection_that_matched_nothing_to_usage() {
        var plan = BuildPlan.builder("test").stateKeys(BuildPlanner.TEST_RESULT).build();
        var noMatch = new BuildPlanResult(
                "test",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic(
                        "run-tests", TestClassMatch.NO_TESTS_MATCHED, "no test classes matched --class Nope")),
                false);
        assertThat(NativePlans.failureExitCode(plan, noMatch)).isEqualTo(Exit.USAGE);
    }

    /** Red tests outrank a class selection that dropped one: the run failed its tests. */
    @Test
    void red_tests_beside_a_dropped_class_still_exit_as_test_failures() {
        TestSummary red =
                new TestSummary(1, 0, 1, 0, List.of(new TestFailureInfo("m", "a.FooTest", "x", "x()", "", "boom", "")));
        var plan = BuildPlan.builder("test")
                .stateKeys(BuildPlanner.TEST_RESULT)
                .addTask(Task.builder("run-tests")
                        .execute(ctx -> ctx.put(BuildPlanner.TEST_RESULT, red))
                        .build())
                .build();
        plan.run();
        var noMatch = new BuildPlanResult(
                "test",
                false,
                Duration.ZERO,
                List.of(),
                List.of(),
                List.of(new BuildPlanResult.Diagnostic("run-tests", TestClassMatch.NO_TESTS_MATCHED, "why")),
                false);
        assertThat(NativePlans.failureExitCode(plan, noMatch)).isEqualTo(Exit.TESTS_FAILED);
    }
}
