// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.TestAnsi;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1441 — storage-surface rendering: {@code jk cache usage} label column accounts for the
 * colon (all four values start in the same column), and {@code jk storage usage}'s hand-built box
 * stays aligned via ANSI-aware {@code BoxTable.visibleWidth} padding.
 */
class StorageSurfacesRenderTest {

    @Test
    void cache_usage_values_all_start_in_the_same_column(@TempDir Path tempDir) throws Exception {
        Path cache = tempDir.resolve("cache");
        Path key = cache.resolve("actions/keys/task1");
        Files.createDirectories(key.getParent());
        Files.write(key, new byte[2048]);

        String stdout = capture(() -> Jk.execute("cache", "usage", "--cache-dir", cache.toString()));

        // "  • <Label>:<pad> <value>" — the value column must be identical on every detail row.
        Pattern detail = Pattern.compile("^(\\s*\\S\\s([A-Za-z ]+):\\s+)\\S");
        List<Integer> valueColumns = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String line : TestAnsi.strip(stdout).split("\\R")) {
            Matcher m = detail.matcher(line);
            if (m.find()) {
                valueColumns.add(m.group(1).length());
                labels.add(m.group(2));
            }
        }
        assertThat(labels).contains("File Count", "Storage Size", "Utilization", "Last Pruned");
        assertThat(valueColumns).hasSizeGreaterThanOrEqualTo(4);
        assertThat(valueColumns.stream().distinct())
                .as("all storage detail values start in the same column (labels: %s)", labels)
                .hasSize(1);
    }

    @Test
    void store_usage_table_rows_share_one_visible_width() {
        var stats = new CacheCommand.StoreUsageStats(
                new CacheCommand.Stats(12, 8192),
                new CacheCommand.Stats(3, 1024),
                new CacheCommand.Stats(7, 555),
                new CacheCommand.Stats(10_370, 2_500_000),
                new CacheCommand.Stats(1736, 0));
        List<String> lines = CacheCommand.renderStoreUsageTable(stats, 20L * 1024 * 1024 * 1024, "3 days ago");

        List<Integer> widths = new ArrayList<>();
        for (String line : lines) {
            String plain = TestAnsi.strip(line);
            // Boxed chrome only — the trailing "Last pruned:" footer hangs outside the box.
            if (plain.startsWith("│") || plain.startsWith("├") || plain.startsWith("╰")) {
                widths.add(plain.length());
            }
        }
        assertThat(widths).hasSizeGreaterThanOrEqualTo(10);
        assertThat(widths.stream().distinct())
                .as("every boxed row renders the same visible width")
                .hasSize(1);

        String joined = TestAnsi.strip(String.join("\n", lines));
        assertThat(joined).contains("Jar Files");
        assertThat(joined).contains("Executables");
        assertThat(joined).contains("OCI Images");
        assertThat(joined).contains("Worker JARs");
        assertThat(joined).contains("Format Stamps");
        assertThat(joined).contains("--"); // zero-byte stamps
        assertThat(joined).doesNotContain("CAS Blobs");
        assertThat(joined).doesNotContain("Run Logs");
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
