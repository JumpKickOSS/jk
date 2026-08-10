// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Storage-surface rendering: {@code jk cache usage} and {@code jk storage usage} hand-built boxes
 * stay aligned via ANSI-aware {@code BoxTable.visibleWidth} padding.
 */
class StorageSurfacesRenderTest {

    @Test
    void cache_usage_table_rows_share_one_visible_width() {
        var stats = new CacheCommand.CacheUsageStats(
                new CacheCommand.Stats(100, 50_000),
                new CacheCommand.Stats(5, 200),
                new CacheCommand.Stats(10, 5_000),
                new CacheCommand.Stats(3, 8_000),
                new CacheCommand.Stats(2, 90_000_000),
                new CacheCommand.Stats(1, 1_000_000),
                new CacheCommand.Stats(2, 59_000_000),
                new CacheCommand.Stats(0, 0),
                new CacheCommand.Stats(170, 0),
                new CacheCommand.Stats(702, 213_300_000));
        List<String> lines = CacheCommand.renderCacheUsageTable(stats, 4L * 1024 * 1024 * 1024, "1 day ago");

        assertBoxedRowsShareWidth(lines);
        String joined = TestAnsi.strip(String.join("\n", lines));
        assertThat(joined).contains("Cache Storage");
        assertThat(joined).contains("Class Files");
        assertThat(joined).contains("Test Results");
        assertThat(joined).contains("Event Logs");
        assertThat(joined).contains("Normal Jars");
        assertThat(joined).contains("Shadow Jars");
        assertThat(joined).contains("Minified Jars");
        assertThat(joined).contains("Native Bins");
        assertThat(joined).contains("OCI Images");
        assertThat(joined).contains("Format Stamps");
        assertThat(joined).contains("--"); // zero-byte stamps
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
        List<String> lines = CacheCommand.renderStoreUsageTable(stats, 20L * 1024 * 1024 * 1024, "3 days ago");

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

    private static String capture(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
