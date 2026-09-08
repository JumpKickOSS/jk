// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.BuildService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**productive-step accounting for "built" vs "checked" UX. */
class BuildServiceDidWorkTest {

    @Test
    void pure_setup_and_cache_hits_are_not_work() {
        var r = result(
                step(TaskNames.PARSE_BUILD, TaskStatus.SUCCESS),
                step(TaskNames.RESOLVE_DEPS, TaskStatus.SUCCESS),
                step(TaskNames.COMPILE_JAVA, TaskStatus.SKIPPED),
                step(TaskNames.COPY_RESOURCES, TaskStatus.SUCCESS),
                step(TaskNames.COMPILE_TEST, TaskStatus.SKIPPED),
                step(TaskNames.RUN_TESTS, TaskStatus.SKIPPED),
                step(TaskNames.PACKAGE_JAR, TaskStatus.SKIPPED),
                step(TaskNames.WRITE_STAMP, TaskStatus.SUCCESS));
        assertThat(BuildService.moduleDidWork(r)).isFalse();
    }

    @Test
    void compile_success_counts_as_work() {
        var r = result(step(TaskNames.COMPILE_JAVA, TaskStatus.SUCCESS), step(TaskNames.RUN_TESTS, TaskStatus.SKIPPED));
        assertThat(BuildService.moduleDidWork(r)).isTrue();
    }

    @Test
    void run_tests_success_counts_as_work() {
        var r = result(step(TaskNames.RUN_TESTS, TaskStatus.SUCCESS));
        assertThat(BuildService.moduleDidWork(r)).isTrue();
    }

    @Test
    void isProductiveStep_names() {
        assertThat(BuildService.isProductiveStep("compile-java")).isTrue();
        assertThat(BuildService.isProductiveStep("run-tests")).isTrue();
        assertThat(BuildService.isProductiveStep("package-jar")).isTrue();
        assertThat(BuildService.isProductiveStep("parse-build")).isFalse();
        assertThat(BuildService.isProductiveStep("copy-resources")).isFalse();
        assertThat(BuildService.isProductiveStep("write-stamp")).isFalse();
    }

    private static BuildPlanResult result(BuildPlanResult.StepReport... steps) {
        return new BuildPlanResult("t", true, Duration.ZERO, List.of(steps), List.of(), List.of(), false);
    }

    private static BuildPlanResult.StepReport step(String name, TaskStatus status) {
        return new BuildPlanResult.StepReport(name, status, Duration.ZERO);
    }
}
