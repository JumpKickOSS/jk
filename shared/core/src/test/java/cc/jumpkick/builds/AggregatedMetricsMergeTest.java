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
                mean, last, count, Map.of(key, 33_460.0), Map.of(key, 33_525.0), Map.of(key, 5L));
        AggregatedMetrics.mergePreferHigherCount(
                mean, last, count, Map.of(key, 126_019.0), Map.of(key, 126_019.0), Map.of(key, 1L));

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
        Files.writeString(homeA.resolve(ProjectBuilds.PROJECT_METRICS), """
                [mean]
                %s = 33460.2
                [last]
                %s = 33525
                [count]
                %s = 5
                """.formatted(key, key, key));
        Files.writeString(homeB.resolve(ProjectBuilds.PROJECT_METRICS), """
                [mean]
                %s = 126019
                [last]
                %s = 126019
                [count]
                %s = 1
                """.formatted(key, key, key));

        AggregatedMetrics agg = AggregatedMetrics.loadAll(root);
        assertThat(agg.mean(key)).hasValue(33_460.2);
        assertThat(agg.last(key)).hasValue(33_525.0);
        assertThat(agg.count(key)).isEqualTo(5L);
    }

    @Test
    void partial_rows_merge_per_key_and_move_together() {
        // (a) last-only keys fold even when the file has a [mean] section for OTHER
        // keys; (b) a count-winner missing [last] clears the loser's last (no mixed rows);
        // (c) a mean-less row never beats a row with a real mean, regardless of count.
        var mean = new LinkedHashMap<String, Double>();
        var last = new LinkedHashMap<String, Double>();
        var count = new LinkedHashMap<String, Long>();

        // Seed: k1 full row (mean+last, count 3); k3 mean-only row (count 2).
        AggregatedMetrics.mergePreferHigherCount(
                mean, last, count, Map.of("k1", 100.0, "k3", 40.0), Map.of("k1", 90.0), Map.of("k1", 3L, "k3", 2L));

        // (a) mixed file: mean for k1 only, last-only for k2 — k2 must still fold.
        AggregatedMetrics.mergePreferHigherCount(
                mean, last, count, Map.of("k1", 200.0), Map.of("k2", 55.0), Map.of("k1", 5L, "k2", 1L));
        assertThat(last).containsEntry("k2", 55.0);
        // (b) k1's winner had no [last] entry — the old last must not survive beside the new mean.
        assertThat(mean).containsEntry("k1", 200.0);
        assertThat(last).doesNotContainKey("k1");
        assertThat(count).containsEntry("k1", 5L);

        // (c) a last-only row with a huge count must not displace k3's real mean.
        AggregatedMetrics.mergePreferHigherCount(mean, last, count, Map.of(), Map.of("k3", 9_999.0), Map.of("k3", 50L));
        assertThat(mean).containsEntry("k3", 40.0);
        assertThat(last).doesNotContainKey("k3");
    }
}
