// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.runtime.base.TestClassWalls;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A standalone project's plan leaves the same per-class walls and step rates a workspace member's does. */
class StandalonePlanTimingsTest {

    @Test
    void a_successful_standalone_run_buffers_its_class_walls_and_learns_its_step_rates(@TempDir Path tmp) {
        Path dir = tmp.resolve("proj");
        Path cache = tmp.resolve("cache");
        TestSummary summary = new TestSummary(
                12, 12, 0, 0, 2, List.of(), Map.of("com.acme.FastTest", 40L, "com.acme.SlowTest", 900L), 1);
        BuildPlan plan = BuildPlan.builder("standalone")
                .stateKeys(BuildPlanner.TEST_RESULT)
                .addTask(Task.builder(TaskNames.RUN_TESTS)
                        .ticks(12)
                        .execute(ctx -> {
                            Thread.sleep(30);
                            ctx.put(BuildPlanner.TEST_RESULT, summary);
                        })
                        .build())
                .addTask(Task.builder(TaskNames.PACKAGE_JAR)
                        .ticks(1)
                        .execute(ctx -> Thread.sleep(30))
                        .build())
                .build();
        StandalonePlanTimings.attach(plan, dir, cache, Clock.SYSTEM);

        BuildPlanResult result = plan.run();

        assertThat(result.errors()).as("errors").isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(TestClassWalls.get(dir.toString()))
                .as("the walls the journal writes as the run's class tables")
                .containsEntry("com.acme.SlowTest", 900L)
                .containsEntry("com.acme.FastTest", 40L);
        assertThat(StepTimings.load(cache).perUnit(dir.toString(), TaskNames.PACKAGE_JAR))
                .as("a step rate folded into the ledger the next ETA reads")
                .isPresent();
        TestClassWalls.take(dir.toString());
    }
}
