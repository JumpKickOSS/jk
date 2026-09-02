// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The install plan must build every artifact the module declares, because the artifact it installs
 * is chosen from what is on disk — native binary, then minified jar, then fat jar, then the thin
 * jar with a launcher. A tail that never runs is a rung the ladder silently falls through.
 *
 * <p>{@code cache-install} takes the terminal, and {@link BuildPlan.Builder#build()} keeps only the
 * terminal's <em>upstream</em> closure. Requiring package-jar alone therefore pruned the assembly
 * and minified tails that {@code appendDeclaredTails} had just re-rooted the plan onto — so
 * {@code jk install} on a project declaring {@code assembly = true} built no fat jar and installed
 * a thin-jar launcher.
 */
class InstallPlanArtifactLadderTest {

    /** A plain library: no native, no assembly — the manifest is not what this test varies. */
    private static JkBuild library() {
        return JkBuild.builder(Project.builder("ex", "demo", "1.0")
                        .jdkMajor(25)
                        .java(25)
                        .build())
                .build();
    }

    /** A tail of the shape appendDeclaredTails adds: downstream of package-jar, re-rooting the plan. */
    private static BuildPlan.Builder planWithTail(String tailName) {
        BuildPlan.Builder b = BuildPlan.builder("install");
        b.addTask(Task.builder(TaskNames.PACKAGE_JAR)
                .stage(BuildStage.PACKAGE)
                .ticks(1)
                .execute(ctx -> {})
                .build());
        b.addTask(Task.builder(tailName)
                .stage(BuildStage.PACKAGE)
                .requires(TaskNames.PACKAGE_JAR)
                .ticks(1)
                .execute(ctx -> {})
                .build());
        return b.terminal(tailName);
    }

    private static Set<String> stepNames(BuildPlan plan) {
        return plan.steps().stream().map(Task::name).collect(Collectors.toSet());
    }

    @Test
    void cache_install_keeps_the_tail_it_displaces() {
        BuildPlan.Builder b = planWithTail(TaskNames.PACKAGE_ASSEMBLY);
        InstallPlans.appendCacheInstall(b, library(), Path.of("cache"), null);

        assertThat(stepNames(b.build()))
                .as("the fat jar must exist by the time the ladder picks an artifact")
                .contains(TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_JAR, TaskNames.CACHE_INSTALL);
    }

    @Test
    void install_without_skip_tests_keeps_the_suite_in_the_plan() {
        // appendDeclaredTails folds run-tests into the deliver join it re-roots the plan onto, and
        // cache-install requires that join — so `jk install` without --skip-tests runs the suite
        // before publishing, the same way `jk build` does. The request's skipTests flag is the one
        // opt-out; this pins the semantics so a prune change cannot drop the suite silently.
        BuildPlan.Builder b = BuildPlan.builder("install");
        b.addTask(Task.builder(TaskNames.PACKAGE_JAR)
                .stage(BuildStage.PACKAGE)
                .ticks(1)
                .execute(ctx -> {})
                .build());
        b.addTask(Task.builder(TaskNames.RUN_TESTS)
                .stage(BuildStage.TEST)
                .ticks(1)
                .execute(ctx -> {})
                .build());
        b.addTask(Task.builder("deliver-join")
                .stage(BuildStage.PACKAGE)
                .requires(TaskNames.PACKAGE_JAR, TaskNames.RUN_TESTS)
                .ticks(0)
                .execute(ctx -> {})
                .build());
        b.terminal("deliver-join");
        InstallPlans.appendCacheInstall(b, library(), Path.of("cache"), null);

        assertThat(stepNames(b.build())).contains(TaskNames.RUN_TESTS, TaskNames.PACKAGE_JAR, TaskNames.CACHE_INSTALL);
    }

    @Test
    void a_plan_with_no_tail_is_unchanged() {
        BuildPlan.Builder b = BuildPlan.builder("install");
        b.addTask(Task.builder(TaskNames.PACKAGE_JAR)
                .stage(BuildStage.PACKAGE)
                .ticks(1)
                .execute(ctx -> {})
                .build());
        b.terminal(TaskNames.PACKAGE_JAR);
        InstallPlans.appendCacheInstall(b, library(), Path.of("cache"), null);

        assertThat(stepNames(b.build())).containsExactlyInAnyOrder(TaskNames.PACKAGE_JAR, TaskNames.CACHE_INSTALL);
    }

    @Test
    void the_minified_tail_survives_too() {
        // Same shape, higher rung: minified beats fat, so pruning it would install the fat jar
        // where the module asked for the minified one.
        BuildPlan.Builder b = planWithTail(TaskNames.PACKAGE_MINIFIED);
        InstallPlans.appendCacheInstall(b, library(), Path.of("cache"), null);

        assertThat(stepNames(b.build())).contains(TaskNames.PACKAGE_MINIFIED, TaskNames.CACHE_INSTALL);
    }

    @Test
    void the_builder_reports_the_terminal_a_tail_would_displace() {
        assertThat(BuildPlan.builder("x").currentTerminal()).isNull();
        assertThat(BuildPlan.builder("x").terminal("package-jar").currentTerminal())
                .isEqualTo("package-jar");
    }
}
