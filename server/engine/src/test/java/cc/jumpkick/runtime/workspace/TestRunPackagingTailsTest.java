// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestRunPackagingTailsTest {

    private static TaskForecast.Task run(String step) {
        return new TaskForecast.Task(step, TaskForecast.Status.RUN, "", null);
    }

    private static List<String> stepsOf(ExplainPlan plan) {
        return plan.modules().getFirst().steps().stream()
                .map(TaskForecast.Task::name)
                .toList();
    }

    private static ExplainPlan planOf(Path dir) {
        var module = new TaskForecast.Module(
                dir,
                "g:engine",
                List.of(
                        run(TaskNames.COMPILE_JAVA),
                        run(TaskNames.PACKAGE_JAR),
                        run(TaskNames.PACKAGE_ASSEMBLY),
                        run(TaskNames.NATIVE_IMAGE),
                        run(TaskNames.RUN_TESTS)),
                1,
                1,
                true,
                false);
        return new ExplainPlan(List.of(module), Map.of(dir, Set.of()), 1, List.of());
    }

    @Test
    void a_test_run_prices_no_packaging_tail_past_the_jar(@TempDir Path dir) {
        assertThat(stepsOf(WorkspaceResourcePhase.withoutPackagingTails(planOf(dir), Set.of())))
                .containsExactly(TaskNames.COMPILE_JAVA, TaskNames.PACKAGE_JAR, TaskNames.RUN_TESTS);
    }

    @Test
    void a_test_run_prices_the_fat_jar_a_siblings_suite_launches(@TempDir Path dir) {
        Set<Path> launched = Set.of(BuildGraph.canonicalPath(dir));
        assertThat(stepsOf(WorkspaceResourcePhase.withoutPackagingTails(planOf(dir), launched)))
                .containsExactly(
                        TaskNames.COMPILE_JAVA, TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_ASSEMBLY, TaskNames.RUN_TESTS);
    }
}
