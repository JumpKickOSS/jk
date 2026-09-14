// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When a module may publish to its dependents: its classes tree for their compiles, its artifacts
 * for their package and test steps.
 *
 * <p>{@code compile-test} is readable across modules only through a {@code kind = "tests"} edge, so
 * it gates the publish for the modules some sibling selects that way and for no others. Getting
 * that predicate wrong is not loud: the consumer compiles against a {@code classes/test} that is
 * missing or mid-write and reports {@code cannot find symbol} in an unrelated module. Both sides of
 * it are pinned here.
 */
class WorkspacePublishGateTest {

    private static final Path WS = Path.of("/ws");

    @Test
    void aTestsKindEdgeMarksTheProducerConsumed() {
        var lib = unit("lib", deps(Map.of()));
        var app = unit("app", deps(Map.of(Scope.TEST, List.of(Dependency.workspace("lib", DependencyKind.TESTS)))));

        assertThat(WorkspaceRunPhase.testClassesConsumed(List.of(lib, app)))
                .as("lib's test classes are on app's test classpath, so lib still waits")
                .containsExactly(WS.resolve("lib"));
    }

    @Test
    void aPlainWorkspaceEdgeMarksNothingConsumed() {
        var lib = unit("lib", deps(Map.of()));
        var app = unit("app", deps(Map.of(Scope.MAIN, List.of(Dependency.workspace("lib")))));

        assertThat(WorkspaceRunPhase.testClassesConsumed(List.of(lib, app)))
                .as("a main edge reads lib's jar, never its test output")
                .isEmpty();
    }

    /** The engine reads merged manifests, where a placeholder may already be a real coord. */
    @Test
    void aTestsKindEdgeResolvesByCoordAsWellAsByPlaceholder() {
        var lib = unit("lib", deps(Map.of()));
        var app = unit(
                "app",
                deps(Map.of(
                        Scope.TEST,
                        List.of(Dependency.of("lib", "ex:lib", VersionSelector.parse("=1.0"))
                                .withKind(DependencyKind.TESTS)))));

        assertThat(WorkspaceRunPhase.testClassesConsumed(List.of(lib, app))).containsExactly(WS.resolve("lib"));
    }

    /** An external Maven test-jar selects no sibling, so it must not hold the whole workspace back. */
    @Test
    void anExternalTestsKindEdgeMarksNothingConsumed() {
        var lib = unit("lib", deps(Map.of()));
        var app = unit(
                "app",
                deps(Map.of(
                        Scope.TEST,
                        List.of(Dependency.of("guava", "com.google.guava:guava", VersionSelector.parse("=33.0"))
                                .withKind(DependencyKind.TESTS)))));

        assertThat(WorkspaceRunPhase.testClassesConsumed(List.of(lib, app))).isEmpty();
    }

    /** Dependents compile against the classes tree, so the admission publish lands on its last writer. */
    @Test
    void the_classes_publish_waits_for_every_compile_the_assembler_and_the_resource_copy() {
        BuildPlan plan = planWith(
                TaskNames.COMPILE_JAVA,
                TaskNames.COMPILE_KOTLIN,
                TaskNames.ASSEMBLE_CLASSES,
                TaskNames.COPY_RESOURCES,
                TaskNames.PACKAGE_JAR,
                TaskNames.COMPILE_TEST,
                TaskNames.RUN_TESTS);

        assertThat(WorkspaceRunPhase.classesWaitSet(plan))
                .containsExactlyInAnyOrder(
                        TaskNames.COMPILE_JAVA,
                        TaskNames.COMPILE_KOTLIN,
                        TaskNames.ASSEMBLE_CLASSES,
                        TaskNames.COPY_RESOURCES);
    }

    /** A plan whose only tree writer is its compiler publishes there; one that compiles nothing publishes on completion. */
    @Test
    void the_classes_publish_takes_what_the_plan_has() {
        assertThat(WorkspaceRunPhase.classesWaitSet(planWith(TaskNames.COMPILE_GROOVY, TaskNames.WRITE_STAMP_GROOVY)))
                .containsExactly(TaskNames.COMPILE_GROOVY);
        assertThat(WorkspaceRunPhase.classesWaitSet(
                        planWith(TaskNames.RESOLVE_DEPS, TaskNames.BUILD_LOGIC_AFTER_BUILD)))
                .isEmpty();
    }

    /** Observed as the scheduler observes it: dependents are admitted before this module packages. */
    @Test
    void the_classes_publish_lands_after_the_resource_copy_and_before_the_jar() {
        List<String> log = new ArrayList<>();
        BuildPlan.Builder b = BuildPlan.builder("module");
        b.addTask(Task.builder(TaskNames.COMPILE_JAVA)
                .stage(BuildStage.COMPILE)
                .ticks(1)
                .execute(ctx -> log.add(TaskNames.COMPILE_JAVA))
                .build());
        b.addTask(Task.builder(TaskNames.COPY_RESOURCES)
                .stage(BuildStage.COMPILE)
                .requires(TaskNames.COMPILE_JAVA)
                .ticks(1)
                .execute(ctx -> log.add(TaskNames.COPY_RESOURCES))
                .build());
        b.addTask(Task.builder(TaskNames.PACKAGE_JAR)
                .stage(BuildStage.PACKAGE)
                .requires(TaskNames.COPY_RESOURCES)
                .ticks(1)
                .execute(ctx -> log.add(TaskNames.PACKAGE_JAR))
                .build());
        BuildPlan plan = b.build();

        WorkspaceRunPhase.watchClassesSteps(plan, () -> log.add("PUBLISH"));
        assertThat(plan.run().success()).isTrue();

        assertThat(log)
                .containsExactly(TaskNames.COMPILE_JAVA, TaskNames.COPY_RESOURCES, "PUBLISH", TaskNames.PACKAGE_JAR);
    }

    @Test
    void anUnconsumedModuleDoesNotWaitForItsTestCompile() {
        BuildPlan plan = planWith(
                TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST, TaskNames.COMPILE_TEST_FIXTURES, TaskNames.RUN_TESTS);

        assertThat(WorkspaceRunPhase.artifactWaitSet(plan, false))
                .containsExactlyInAnyOrder(TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST_FIXTURES);
    }

    @Test
    void aConsumedModuleStillWaitsForItsTestCompile() {
        BuildPlan plan = planWith(
                TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST, TaskNames.COMPILE_TEST_FIXTURES, TaskNames.RUN_TESTS);

        assertThat(WorkspaceRunPhase.artifactWaitSet(plan, true))
                .containsExactlyInAnyOrder(
                        TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST, TaskNames.COMPILE_TEST_FIXTURES);
    }

    /** Fixtures are sibling-visible with no edge to declare, so they gate either way. */
    @Test
    void testFixturesGateThePublishUnconditionally() {
        BuildPlan plan = planWith(TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST_FIXTURES);

        assertThat(WorkspaceRunPhase.artifactWaitSet(plan, false)).contains(TaskNames.COMPILE_TEST_FIXTURES);
        assertThat(WorkspaceRunPhase.artifactWaitSet(plan, true)).contains(TaskNames.COMPILE_TEST_FIXTURES);
    }

    /**
     * With nothing else to publish on, the scheduler's fallback is publish-on-completion, which sits
     * behind {@code run-tests}. The test compile is earlier, so it stays the gate.
     */
    @Test
    void aModuleWithNoPackagingStillPublishesAtItsTestCompile() {
        BuildPlan plan = planWith(TaskNames.COMPILE_TEST, TaskNames.RUN_TESTS);

        assertThat(WorkspaceRunPhase.artifactWaitSet(plan, false)).containsExactly(TaskNames.COMPILE_TEST);
    }

    @Test
    void aPlanWithNoArtifactStepsWaitsForNothing() {
        assertThat(WorkspaceRunPhase.artifactWaitSet(planWith(TaskNames.COMPILE_JAVA), false))
                .isEmpty();
    }

    /**
     * The gate observed as the scheduler observes it: when {@code artifactsReady} actually fires.
     *
     * <p>{@code artifactWaitSet} says which steps count; these two say that the publish lands on the
     * last of them and not before. That is the ordering dependents are admitted on, and it is the
     * half a wait-set assertion cannot reach — a workspace build is no use for it, because an
     * early publish only loses the race when the consumer is quick enough to reach its own test
     * compile first, which a two-module fixture is not.
     */
    @Test
    void aConsumedModulePublishesOnlyAfterItsTestCompile() {
        List<String> log = new ArrayList<>();
        BuildPlan plan = orderedPlan(log);

        WorkspaceRunPhase.watchArtifactSteps(plan, true, () -> log.add("PUBLISH"));
        assertThat(plan.run().success()).isTrue();

        assertThat(log)
                .as("the test classes a sibling reads must exist before anything is admitted on them")
                .containsExactly(TaskNames.PACKAGE_JAR, TaskNames.COMPILE_TEST, "PUBLISH", TaskNames.RUN_TESTS);
    }

    @Test
    void anUnconsumedModulePublishesBeforeItsTestCompileRuns() {
        List<String> log = new ArrayList<>();
        BuildPlan plan = orderedPlan(log);

        WorkspaceRunPhase.watchArtifactSteps(plan, false, () -> log.add("PUBLISH"));
        assertThat(plan.run().success()).isTrue();

        assertThat(log)
                .as("nothing can read this module's test classes, so dependents wait on the jar alone")
                .containsExactly(TaskNames.PACKAGE_JAR, "PUBLISH", TaskNames.COMPILE_TEST, TaskNames.RUN_TESTS);
    }

    /**
     * A failed test compile leaves {@code classes/test} half-written. The consumer must then fail
     * on its sibling not being built, which it can only do if the publish never fires.
     */
    @Test
    void aConsumedModuleNeverPublishesWhenItsTestCompileFails() {
        List<String> log = new ArrayList<>();
        BuildPlan.Builder b = BuildPlan.builder("module");
        b.addTask(recording(TaskNames.PACKAGE_JAR, log));
        b.addTask(Task.builder(TaskNames.COMPILE_TEST)
                .stage(BuildStage.PACKAGE)
                .requires(TaskNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> {
                    throw new RuntimeException("test compile failed");
                })
                .build());
        BuildPlan plan = b.build();

        WorkspaceRunPhase.watchArtifactSteps(plan, true, () -> log.add("PUBLISH"));
        assertThat(plan.run().success()).isFalse();

        assertThat(log)
                .as("a half-written classes/test is never published to a sibling that selects it")
                .containsExactly(TaskNames.PACKAGE_JAR);
    }

    /** package-jar → compile-test → run-tests, each recording itself as it executes. */
    private static BuildPlan orderedPlan(List<String> log) {
        BuildPlan.Builder b = BuildPlan.builder("module");
        b.addTask(recording(TaskNames.PACKAGE_JAR, log));
        b.addTask(Task.builder(TaskNames.COMPILE_TEST)
                .stage(BuildStage.PACKAGE)
                .requires(TaskNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> log.add(TaskNames.COMPILE_TEST))
                .build());
        b.addTask(Task.builder(TaskNames.RUN_TESTS)
                .stage(BuildStage.PACKAGE)
                .requires(TaskNames.COMPILE_TEST)
                .ticks(1)
                .execute(ctx -> log.add(TaskNames.RUN_TESTS))
                .build());
        return b.build();
    }

    private static Task recording(String name, List<String> log) {
        return Task.builder(name)
                .stage(BuildStage.PACKAGE)
                .ticks(1)
                .execute(ctx -> log.add(name))
                .build();
    }

    private static BuildPlan planWith(String... steps) {
        BuildPlan.Builder b = BuildPlan.builder("module");
        for (String step : steps) {
            b.addTask(Task.builder(step)
                    .stage(BuildStage.PACKAGE)
                    .ticks(1)
                    .execute(ctx -> {})
                    .build());
        }
        return b.build();
    }

    private static JkBuild.Dependencies deps(Map<Scope, List<Dependency>> byScope) {
        return new JkBuild.Dependencies(byScope);
    }

    private static BuildGraph.BuildUnit unit(String name, JkBuild.Dependencies dependencies) {
        JkBuild manifest = JkBuild.builder(
                        Project.builder("ex", name, "1.0").jdkMajor(25).java(25).build())
                .dependencies(dependencies)
                .build();
        return new BuildGraph.BuildUnit(WS.resolve(name), manifest, "ex:" + name, BuildGraph.Origin.MODULE);
    }
}
