// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JkCacheConfigTest {

    @Test
    void missing_file_is_defaults() {
        assertThat(JkCacheConfig.fromToml(Path.of("/no/such/file"))).isEqualTo(JkCacheConfig.DEFAULTS);
    }

    @Test
    void parses_size_knobs(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(
                toml,
                """
                [cache]
                max-store-size-mb   = 8192
                prune-interval-days = 3
                record-ttl-days     = 14
                max-cache-size-mb   = 512
                """);
        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.maxStoreSizeMb()).isEqualTo(8192);
        assertThat(c.pruneIntervalDays()).isEqualTo(3);
        assertThat(c.recordTtlDays()).isEqualTo(14);
        assertThat(c.maxCacheSizeMb()).isEqualTo(512);
        assertThat(c.maxCacheSizeBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(c.maxStoreSizeBytes()).isEqualTo(8192L * 1024 * 1024);
        assertThat(c.storeBudgetConfigured()).isTrue();
        assertThat(c.configuredStoreSizeBytes()).isEqualTo(8192L * 1024 * 1024);
    }

    @Test
    void partial_table_keeps_other_defaults(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nauto-prune = false\n");
        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.autoPrune()).isFalse();
        assertThat(c.maxStoreSizeMb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_STORE_SIZE_MB);
        assertThat(c.pruneIntervalDays()).isEqualTo(JkCacheConfig.DEFAULTS.pruneIntervalDays());
        assertThat(c.recordTtlDays()).isEqualTo(JkCacheConfig.DEFAULTS.recordTtlDays());
        assertThat(c.maxCacheSizeMb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_CACHE_SIZE_MB);
        assertThat(c.maxStoreSizeBytes()).isEqualTo(4096L * 1024 * 1024);
        assertThat(c.storeBudgetConfigured()).isFalse();
        assertThat(c.configuredStoreSizeBytes()).isZero();
    }

    @Test
    void malformed_values_fall_back_to_defaults(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(
                toml,
                """
                [cache]
                max-store-size-mb = not-a-number
                max-cache-size-mb = nope
                """);
        assertThat(JkCacheConfig.fromToml(toml)).isEqualTo(JkCacheConfig.DEFAULTS);
    }

    @Test
    void env_overrides_file(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(
                toml,
                """
                [cache]
                max-store-size-mb   = 8192
                max-cache-size-mb   = 512
                prune-interval-days = 3
                """);
        var env = Map.of(
                "JK_MAX_STORE_SIZE_MB", "100",
                "JK_PRUNE_INTERVAL_DAYS", "1",
                "JK_RECORD_TTL_DAYS", "2",
                "JK_MAX_CACHE_SIZE_MB", "256");
        JkCacheConfig c = JkCacheConfig.resolve(toml, env::get);
        assertThat(c.maxStoreSizeMb()).isEqualTo(100);
        assertThat(c.pruneIntervalDays()).isEqualTo(1);
        assertThat(c.recordTtlDays()).isEqualTo(2);
        assertThat(c.maxCacheSizeMb()).isEqualTo(256);
    }

    @Test
    void zero_size_means_unset_default(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(
                toml,
                """
                [cache]
                max-store-size-mb  = 0
                max-cache-size-mb  = 0
                """);
        JkCacheConfig c = JkCacheConfig.fromToml(toml);
        assertThat(c.maxStoreSizeMb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_STORE_SIZE_MB);
        assertThat(c.maxCacheSizeMb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_CACHE_SIZE_MB);
        assertThat(c.maxStoreSizeBytes()).isEqualTo(4096L * 1024 * 1024);
        assertThat(c.maxCacheSizeBytes()).isEqualTo(1024L * 1024 * 1024);
        assertThat(c.storeBudgetConfigured()).isFalse();
        assertThat(c.configuredStoreSizeBytes()).isZero();
    }

    @Test
    void env_zero_does_not_override_file(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-store-size-mb = 8192\n");
        var env = Map.of("JK_MAX_STORE_SIZE_MB", "0", "JK_MAX_CACHE_SIZE_MB", "0");
        JkCacheConfig c = JkCacheConfig.resolve(toml, env::get);
        assertThat(c.maxStoreSizeMb()).isEqualTo(8192);
        assertThat(c.maxCacheSizeMb()).isEqualTo(JkCacheConfig.DEFAULT_MAX_CACHE_SIZE_MB);
    }

    @Test
    void env_only_without_file(@TempDir Path tempDir) {
        JkCacheConfig c2 =
                JkCacheConfig.resolve(tempDir.resolve("none.toml"), Map.of("JK_MAX_STORE_SIZE_MB", "50")::get);
        assertThat(c2.maxStoreSizeMb()).isEqualTo(50);

        Path toml = tempDir.resolve("config.toml");
        try {
            Files.writeString(toml, "[cache]\nmax-store-size-mb = 8192\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        JkCacheConfig c3 = JkCacheConfig.resolve(toml, Map.of("JK_MAX_STORE_SIZE_MB", "huge")::get);
        assertThat(c3.maxStoreSizeMb()).isEqualTo(8192);
    }

    @Test
    void store_budget_configured_via_env_alone(@TempDir Path tempDir) {
        JkCacheConfig c =
                JkCacheConfig.resolve(tempDir.resolve("none.toml"), Map.of("JK_MAX_STORE_SIZE_MB", "50")::get);
        assertThat(c.storeBudgetConfigured()).isTrue();
        assertThat(c.configuredStoreSizeBytes()).isEqualTo(50L * 1024 * 1024);
    }

    @Test
    void store_budget_not_configured_by_defaults_or_zero_env(@TempDir Path tempDir) {
        JkCacheConfig none = JkCacheConfig.resolve(tempDir.resolve("none.toml"), key -> null);
        assertThat(none.storeBudgetConfigured()).isFalse();
        assertThat(none.configuredStoreSizeBytes()).isZero();

        JkCacheConfig zeroEnv =
                JkCacheConfig.resolve(tempDir.resolve("none.toml"), Map.of("JK_MAX_STORE_SIZE_MB", "0")::get);
        assertThat(zeroEnv.storeBudgetConfigured()).isFalse();
    }

    @Test
    void legacy_knobs_warn_with_successor_names(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(
                toml,
                """
                [cache]
                max-size-gb        = 50
                action-max-size-mb = 512
                """);
        var env = Map.of("JK_MAX_SIZE_GB", "50", "JK_ACTION_MAX_SIZE_MB", "512");
        var warnings = JkCacheConfig.legacyKnobWarnings(toml, env::get);
        assertThat(warnings).hasSize(4);
        assertThat(warnings)
                .anySatisfy(w -> assertThat(w).contains("max-size-gb").contains("max-store-size-mb"))
                .anySatisfy(w -> assertThat(w).contains("action-max-size-mb").contains("max-cache-size-mb"))
                .anySatisfy(w -> assertThat(w).contains("JK_MAX_SIZE_GB").contains("JK_MAX_STORE_SIZE_MB"))
                .anySatisfy(w -> assertThat(w).contains("JK_ACTION_MAX_SIZE_MB").contains("JK_MAX_CACHE_SIZE_MB"));
    }

    @Test
    void no_legacy_warnings_for_current_knobs(@TempDir Path tempDir) throws Exception {
        Path toml = tempDir.resolve("config.toml");
        Files.writeString(toml, "[cache]\nmax-store-size-mb = 8192\nmax-cache-size-mb = 512\n");
        assertThat(JkCacheConfig.legacyKnobWarnings(toml, key -> null)).isEmpty();
        assertThat(JkCacheConfig.legacyKnobWarnings(tempDir.resolve("none.toml"), key -> null))
                .isEmpty();
    }
}
