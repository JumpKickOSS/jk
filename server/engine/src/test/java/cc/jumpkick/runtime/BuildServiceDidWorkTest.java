// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepNames;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** JK-1296: productive-step accounting for "built" vs "checked" UX. */
class BuildServiceDidWorkTest {

    @Test
    void pure_setup_and_cache_hits_are_not_work() {
        var r = result(
                step(StepNames.PARSE_BUILD, StepStatus.SUCCESS),
                step(StepNames.RESOLVE_DEPS, StepStatus.SUCCESS),
                step(StepNames.COMPILE_JAVA, StepStatus.SKIPPED),
                step(StepNames.COPY_RESOURCES, StepStatus.SUCCESS),
                step(StepNames.COMPILE_TEST, StepStatus.SKIPPED),
                step(StepNames.RUN_TESTS, StepStatus.SKIPPED),
                step(StepNames.PACKAGE_JAR, StepStatus.SKIPPED),
                step(StepNames.WRITE_STAMP, StepStatus.SUCCESS));
        assertThat(BuildService.moduleDidWork(r)).isFalse();
    }

    @Test
    void compile_success_counts_as_work() {
        var r = result(
                step(StepNames.COMPILE_JAVA, StepStatus.SUCCESS),
                step(StepNames.RUN_TESTS, StepStatus.SKIPPED));
        assertThat(BuildService.moduleDidWork(r)).isTrue();
    }

    @Test
    void run_tests_success_counts_as_work() {
        var r = result(step(StepNames.RUN_TESTS, StepStatus.SUCCESS));
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

    private static PipelineResult result(PipelineResult.StepReport... steps) {
        return new PipelineResult("t", true, Duration.ZERO, List.of(steps), List.of(), List.of(), false);
    }

    private static PipelineResult.StepReport step(String name, StepStatus status) {
        return new PipelineResult.StepReport(name, status, Duration.ZERO);
    }
}
