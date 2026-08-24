// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.config.JkCacheConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Storage-surface rendering: {@code jk cache usage} and {@code jk storage usage} hand-built boxes
 * stay aligned via ANSI-aware {@code Table.visibleWidth} padding.
 */
class StorageSurfacesRenderTest {

    /** 4 GiB action budget / 512 MiB incremental budget — the shipped defaults. */
    private static final JkCacheConfig CACHE_CONFIG = new JkCacheConfig(true, 7, 4.0, 0.5);

    @Test
    void cache_usage_table_rows_share_one_visible_width() {
        var stats = new CacheCommand.CacheUsageStats(
                new CacheCommand.Stats(100, 50_000),
                new CacheCommand.Stats(5, 200),
                new CacheCommand.Stats(3, 8_000),
                new CacheCommand.Stats(2, 90_000_000),
                new CacheCommand.Stats(1, 1_000_000),
                new CacheCommand.Stats(2, 59_000_000),
                new CacheCommand.Stats(0, 0),
                new CacheCommand.Stats(48, 1_200_000),
                new CacheCommand.Stats(170, 0),
                new CacheCommand.Stats(16_449, 1_400_000),
                new CacheCommand.Stats(702, 213_300_000));
        List<String> lines = CacheCommand.renderCacheUsageTable(stats, CACHE_CONFIG, "1 day ago");

        assertBoxedRowsShareWidth(lines);
        String joined = TestAnsi.strip(String.join("\n", lines));
        assertThat(joined).contains("Cache Storage");
        assertThat(joined).contains("Class Files");
        assertThat(joined).contains("Test Results");
        assertThat(joined).contains("Normal Jars");
        assertThat(joined).contains("Shadow Jars");
        assertThat(joined).contains("Minified Jars");
        assertThat(joined).contains("Native Bins");
        assertThat(joined).contains("OCI Images");
        assertThat(joined).contains("Format Stamps");
        assertThat(joined).contains("--"); // zero-byte stamps
        String derived = lines.stream()
                .map(TestAnsi::strip)
                .filter(l -> l.contains("Derived caches"))
                .findFirst()
                .orElseThrow();
        // Bounded by count and by supersession, so the line reports what is there and stops:
        // "of <budget>" would name a number that is not this tier's bound.
        assertThat(derived).contains("apparent").doesNotContain(" of ");
        assertThat(joined).contains("Last cleaned: 1 day ago");
        assertThat(joined).doesNotContain("Last pruned:");
        assertThat(joined).doesNotContain("Last Pruned");
    }

    @Test
    void store_usage_table_rows_share_one_visible_width() {
        var stats = new CacheCommand.StoreUsageStats(
                new CacheCommand.Stats(12, 8192),
                new CacheCommand.Stats(3, 1024),
                new CacheCommand.Stats(7, 555),
                new CacheCommand.Stats(10_370, 2_500_000));
        List<String> lines = CacheCommand.renderStoreUsageTable(stats, "3 days ago");

        assertBoxedRowsShareWidth(lines);
        String joined = TestAnsi.strip(String.join("\n", lines));
        assertThat(joined).contains("Jar Files");
        assertThat(joined).contains("Native Bins");
        assertThat(joined).contains("OCI Images");
        assertThat(joined).contains("Worker JARs");
        assertThat(joined).contains("Last cleaned: 3 days ago");
        assertThat(joined).doesNotContain("Format Stamps");
        assertThat(joined).doesNotContain("CAS Blobs");
        assertThat(joined).doesNotContain("Run Logs");
        assertThat(joined).doesNotContain("Last pruned:");
        assertThat(joined).doesNotContain("Executables");
        assertThat(joined).doesNotContain("Utilization");
    }

    @Test
    void store_usage_table_omits_the_utilization_row() {
        // The artifact store has no budget, so a percentage row would have no denominator.
        var stats = new CacheCommand.StoreUsageStats(
                new CacheCommand.Stats(12, 8192),
                new CacheCommand.Stats(3, 1024),
                new CacheCommand.Stats(7, 555),
                new CacheCommand.Stats(10_370, 2_500_000));
        String store = TestAnsi.strip(String.join("\n", CacheCommand.renderStoreUsageTable(stats, "never")));
        assertThat(store).contains("Total").doesNotContain("Utilization");

        var cacheStats = new CacheCommand.CacheUsageStats(
                new CacheCommand.Stats(100, 50_000),
                new CacheCommand.Stats(5, 200),
                new CacheCommand.Stats(3, 8_000),
                new CacheCommand.Stats(2, 90_000_000),
                new CacheCommand.Stats(1, 1_000_000),
                new CacheCommand.Stats(2, 59_000_000),
                new CacheCommand.Stats(0, 0),
                new CacheCommand.Stats(48, 1_200_000),
                new CacheCommand.Stats(170, 0),
                new CacheCommand.Stats(0, 0),
                new CacheCommand.Stats(702, 213_300_000));
        String cache = TestAnsi.strip(
                String.join("\n", CacheCommand.renderCacheUsageTable(cacheStats, CACHE_CONFIG, "today")));
        assertThat(cache).contains("Utilization");
    }

    private static void assertBoxedRowsShareWidth(List<String> lines) {
        List<Integer> widths = new ArrayList<>();
        for (String line : lines) {
            String plain = TestAnsi.strip(line);
            if (plain.startsWith("│") || plain.startsWith("├") || plain.startsWith("╰")) {
                widths.add(plain.length());
            }
        }
        assertThat(widths).hasSizeGreaterThanOrEqualTo(8);
        assertThat(widths.stream().distinct())
                .as("every boxed row renders the same visible width")
                .hasSize(1);
    }
}
