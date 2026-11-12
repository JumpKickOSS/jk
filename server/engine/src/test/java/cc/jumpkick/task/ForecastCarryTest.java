// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.JkThreads;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A forecast's lanes on the cpu pool read through the same projection, entry memo and plan-worker limit as the forecast. */
class ForecastCarryTest {

    @Test
    void a_lane_answers_from_the_forecasts_projection(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("classes");
        ProjectedFiles projection = new ProjectedFiles(sha -> dir.resolve("cas").resolve(sha));
        projection.project(root, Map.of("p/C.class", "abc"));

        Map<String, String> onLane = ProjectedFiles.with(
                projection,
                () -> JkThreads.cpu().submit(() -> ProjectedFiles.at(root)).get());

        assertThat(onLane).containsExactly(Map.entry("p/C.class", "abc"));
        assertThat(JkThreads.cpu().submit(() -> ProjectedFiles.at(root)).get()).isNull();
    }

    @Test
    void a_lane_shares_the_forecasts_entry_memo(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("lib.jar");

        String onLane = ClasspathFingerprint.withEntryMemo(() -> {
            String first = ClasspathFingerprint.entry(jar);
            Files.writeString(jar, "now there");
            assertThat(first).startsWith("missing:");
            return JkThreads.cpu().submit(() -> ClasspathFingerprint.entry(jar)).get();
        });

        assertThat(onLane).as("the lane reads the memo, not the disk").startsWith("missing:");
        assertThat(ClasspathFingerprint.entry(jar)).startsWith("file:");
    }

    @Test
    void a_lane_plans_on_the_workers_the_forecast_allows() throws Exception {
        JavaCompile.PlanWorkers onLane = ScopedValue.where(JavaCompile.PLAN_WORKERS, JavaCompile.PlanWorkers.NONE)
                .call(() -> JkThreads.cpu()
                        .submit(() -> ContextPropagator.get(JavaCompile.PLAN_WORKERS))
                        .get());

        assertThat(onLane).isEqualTo(JavaCompile.PlanWorkers.NONE);
    }
}
