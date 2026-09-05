// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.withinPercentage;

import cc.jumpkick.task.RunNotices;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkCacheConfigTest {

    /** Large volume — no free-space clamp on defaults. */
    private static final JkCacheConfig.DiskSpace BIG_DISK =
            new JkCacheConfig.DiskSpace(100L * JkCacheConfig.GIB, 50L * JkCacheConfig.GIB);

    @Test
    void missing_file_is_defaults() {
        assertThat(JkCacheConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkCacheConfig.DEFAULTS);
    }

    @Test
    void parses_size_knobs_in_gib(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
                [cache]
                prune-interval-days = 3
                max-cache-size-gb   = 0.5
                """);
        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.pruneIntervalDays()).isEqualTo(3);
        assertThat(c.maxCacheSizeGb()).isEqualTo(0.5);
        assertThat(c.maxCacheSizeBytes()).isEqualTo(Math.round(0.5 * JkCacheConfig.GIB));
    }

    @Test
    void partial_table_keeps_other_defaults(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nauto-prune = false\n");
        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.autoPrune()).isFalse();
        assertThat(c.pruneIntervalDays()).isEqualTo(JkCacheConfig.DEFAULTS.pruneIntervalDays());
        assertThat(c.maxCacheSizeGb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_CACHE_SIZE_GB);
        assertThat(c.maxCacheSizeBytes()).isEqualTo(4L * JkCacheConfig.GIB);
    }

    @Test
    void malformed_values_fall_back_to_defaults(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
                [cache]
                max-cache-size-gb = nope
                """);
        assertThat(JkCacheConfig.fromToml(toml)).isEqualTo(JkCacheConfig.DEFAULTS);
    }

    @Test
    void env_overrides_file(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
                [cache]
                max-cache-size-gb   = 0.5
                prune-interval-days = 3
                """);
        var env = Map.of("JK_PRUNE_INTERVAL_DAYS", "1", "JK_MAX_CACHE_SIZE_GB", "0.25");
        JkCacheConfig c = JkCacheConfig.resolve(toml, env::get, BIG_DISK);
        assertThat(c.pruneIntervalDays()).isEqualTo(1);
        assertThat(c.maxCacheSizeGb()).isEqualTo(0.25);
    }

    @Test
    void zero_size_means_unset_default(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, """
                [cache]
                max-cache-size-gb  = 0
                """);
        JkCacheConfig c = JkCacheConfig.resolve(toml, k -> null, BIG_DISK);
        assertThat(c.maxCacheSizeGb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_CACHE_SIZE_GB);
        assertThat(c.maxCacheSizeBytes()).isEqualTo(4L * JkCacheConfig.GIB);
    }

    @Test
    void env_zero_does_not_override_file(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-cache-size-gb = 8\n");
        var env = Map.of("JK_MAX_CACHE_SIZE_GB", "0");
        JkCacheConfig c = JkCacheConfig.resolve(toml, env::get, BIG_DISK);
        assertThat(c.maxCacheSizeGb()).isEqualTo(8.0);

        JkCacheConfig noFileValue = JkCacheConfig.resolve(tempDir.resolve("none.toml"), env::get, BIG_DISK);
        assertThat(noFileValue.maxCacheSizeGb())
                .as("with nothing to fall back to, env 0 still means unset")
                .isEqualTo(JkCacheConfig.DEFAULT_MAX_CACHE_SIZE_GB);
    }

    @Test
    void env_only_without_file(@TempDir Path tempDir) throws Exception {
        JkCacheConfig c2 = JkCacheConfig.resolve(
                tempDir.resolve("none.toml"), Map.of("JK_MAX_CACHE_SIZE_GB", "0.5")::get, BIG_DISK);
        assertThat(c2.maxCacheSizeGb()).isEqualTo(0.5);
        assertThat(c2.maxCacheSizeBytes()).isEqualTo(Math.round(0.5 * JkCacheConfig.GIB));

        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-cache-size-gb = 8\n");
        JkCacheConfig c3 = JkCacheConfig.resolve(toml, Map.of("JK_MAX_CACHE_SIZE_GB", "huge")::get, BIG_DISK);
        assertThat(c3.maxCacheSizeGb()).isEqualTo(8.0);
    }

    @Test
    void ci_bumps_logical_defaults(@TempDir Path tempDir) {
        JkCacheConfig c = JkCacheConfig.resolve(tempDir.resolve("none.toml"), Map.of("CI", "true")::get, BIG_DISK);
        assertThat(c.maxCacheSizeGb()).isEqualTo(JkCacheConfig.CI_MAX_CACHE_SIZE_GB);

        JkCacheConfig c1 = JkCacheConfig.resolve(tempDir.resolve("none.toml"), Map.of("CI", "1")::get, BIG_DISK);
        assertThat(c1.maxCacheSizeGb()).isEqualTo(8.0);
    }

    @Test
    void small_disk_clamps_defaults_to_free_space_share(@TempDir Path tempDir) {
        // 8 GiB total, 2 GiB free → (2 * 0.8) / 2 = 0.8 GiB
        var small = new JkCacheConfig.DiskSpace(8L * JkCacheConfig.GIB, 2L * JkCacheConfig.GIB);
        JkCacheConfig c = JkCacheConfig.resolve(tempDir.resolve("none.toml"), k -> null, small);
        assertThat(c.maxCacheSizeGb()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void small_disk_does_not_clamp_explicit_sizes(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-cache-size-gb = 3\n");
        var small = new JkCacheConfig.DiskSpace(8L * JkCacheConfig.GIB, 2L * JkCacheConfig.GIB);
        JkCacheConfig c = JkCacheConfig.resolve(toml, k -> null, small);
        assertThat(c.maxCacheSizeGb()).isEqualTo(3.0);
    }

    @Test
    void format_gb_is_compact() {
        assertThat(JkCacheConfig.formatGb(4.0)).isEqualTo("4");
        assertThat(JkCacheConfig.formatGb(0.5)).isEqualTo("0.5");
        assertThat(JkCacheConfig.formatGb(0.8)).isEqualTo("0.8");
    }

    @Test
    void small_disk_clamp_counts_the_tiers_own_bytes_as_headroom() {
        // An 8 GiB volume with 2 GiB free where the cache itself holds 3 GiB must
        // budget from 5 GiB of reclaimable space, not 2 — otherwise the budget chases its own
        // eviction downward.
        long gib = 1024L * 1024 * 1024;
        var disk = new JkCacheConfig.DiskSpace(8 * gib, 2 * gib);
        double withOwn = JkCacheConfig.clampDefaultGb(6.0, disk, () -> 3 * gib);
        double withoutOwn = JkCacheConfig.clampDefaultGb(6.0, disk, () -> 0L);
        assertThat(withOwn).isCloseTo((5.0 * 0.8) / 2.0, withinPercentage(1));
        assertThat(withOwn).isGreaterThan(withoutOwn);
    }

    @Test
    void legacy_mb_keys_and_envs_still_pin_the_budget(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-cache-size-mb = 512\n");
        JkCacheConfig fromFile = JkCacheConfig.resolve(toml, k -> null, BIG_DISK);
        assertThat(fromFile.maxCacheSizeGb()).isCloseTo(0.5, withinPercentage(1));

        JkCacheConfig fromEnv =
                JkCacheConfig.resolve(dir.resolve("none.toml"), Map.of("JK_MAX_CACHE_SIZE_MB", "2048")::get, BIG_DISK);
        assertThat(fromEnv.maxCacheSizeGb()).isCloseTo(2.0, withinPercentage(1));
    }

    /** The env and file spellings are two facts, so a machine that sets both is told about both. */
    @Test
    void both_legacy_mb_spellings_present_warn_once_each(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-cache-size-mb = 512\n");
        var err = new ByteArrayOutputStream();
        var original = System.err;
        RunNotices.clear();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            JkCacheConfig.resolve(toml, Map.of("JK_MAX_CACHE_SIZE_MB", "2048")::get, BIG_DISK);
            JkCacheConfig.resolve(toml, Map.of("JK_MAX_CACHE_SIZE_MB", "2048")::get, BIG_DISK);
        } finally {
            System.setErr(original);
            RunNotices.clear();
        }
        String out = err.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("JK_MAX_CACHE_SIZE_MB is the pre-rename spelling");
        assertThat(out).contains("cache.max-cache-size-mb is the pre-rename spelling");
        assertThat(out.indexOf("JK_MAX_CACHE_SIZE_MB is")).isEqualTo(out.lastIndexOf("JK_MAX_CACHE_SIZE_MB is"));
        assertThat(out.indexOf("cache.max-cache-size-mb is")).isEqualTo(out.lastIndexOf("cache.max-cache-size-mb is"));
    }
}
