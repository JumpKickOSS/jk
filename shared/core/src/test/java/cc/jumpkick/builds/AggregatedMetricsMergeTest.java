// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stale project identities for the same absolute module path must not overwrite well-sampled
 * walls with a one-off outlier (dogfood: 126s engine run-tests vs 33s × 5 samples).
 */
class AggregatedMetricsMergeTest {

    @Test
    void mergePreferHigherCount_keeps_well_sampled_mean() {
        Map<String, Double> mean = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        Map<String, Long> count = new LinkedHashMap<>();
        String key = "module./Users/me/jk/server/engine.task.run-tests.wall-ms";

        AggregatedMetrics.mergePreferHigherCount(
                mean,
                last,
                count,
                Map.of(key, 33_460.0),
                Map.of(key, 33_525.0),
                Map.of(key, 5L));
        AggregatedMetrics.mergePreferHigherCount(
                mean,
                last,
                count,
                Map.of(key, 126_019.0),
                Map.of(key, 126_019.0),
                Map.of(key, 1L));

        assertThat(mean.get(key)).isEqualTo(33_460.0);
        assertThat(last.get(key)).isEqualTo(33_525.0);
        assertThat(count.get(key)).isEqualTo(5L);
    }

    @Test
    void mergePreferHigherCount_replaces_when_incoming_has_more_samples() {
        Map<String, Double> mean = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        Map<String, Long> count = new LinkedHashMap<>();
        String key = "module./p.task.run-tests.wall-ms";

        AggregatedMetrics.mergePreferHigherCount(
                mean, last, count, Map.of(key, 126_000.0), Map.of(key, 126_000.0), Map.of(key, 1L));
        AggregatedMetrics.mergePreferHigherCount(
                mean, last, count, Map.of(key, 30_000.0), Map.of(key, 29_000.0), Map.of(key, 8L));

        assertThat(mean.get(key)).isEqualTo(30_000.0);
        assertThat(last.get(key)).isEqualTo(29_000.0);
        assertThat(count.get(key)).isEqualTo(8L);
    }

    @Test
    void isCredibleLast_rejects_cache_restore_blip() {
        assertThat(AggregatedMetrics.isCredibleLast(32, 32_660.0)).isFalse();
        assertThat(AggregatedMetrics.isCredibleLast(31_000, 32_660.0)).isTrue();
        assertThat(AggregatedMetrics.isCredibleLast(100, null)).isTrue();
        assertThat(AggregatedMetrics.isCredibleLast(50, 80.0)).isTrue(); // small steps ok
    }

    @Test
    void loadAll_prefers_higher_count_across_project_homes(@TempDir Path root) throws Exception {
        // Two project homes, same module path key — one well sampled, one outlier.
        Path homeA = root.resolve("projects/id-a");
        Path homeB = root.resolve("projects/id-b");
        Files.createDirectories(homeA);
        Files.createDirectories(homeB);
        String key = "module./Users/me/jk/server/engine.task.run-tests.wall-ms";
        Files.writeString(
                homeA.resolve(ProjectBuilds.PROJECT_METRICS),
                """
                [mean]
                %s = 33460.2
                [last]
                %s = 33525
                [count]
                %s = 5
                """
                        .formatted(key, key, key));
        Files.writeString(
                homeB.resolve(ProjectBuilds.PROJECT_METRICS),
                """
                [mean]
                %s = 126019
                [last]
                %s = 126019
                [count]
                %s = 1
                """
                        .formatted(key, key, key));

        AggregatedMetrics agg = AggregatedMetrics.loadAll(root);
        assertThat(agg.mean(key)).hasValue(33_460.2);
        assertThat(agg.last(key)).hasValue(33_525.0);
        assertThat(agg.count(key)).isEqualTo(5L);
    }
}
