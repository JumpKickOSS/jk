// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Machine-scoped {@code [cache]} policy from {@code ~/.config/jk/config.toml} (not project-overridable).
 * Precedence: {@code JK_*} env &gt; user file &gt; defaults ({@link #DEFAULTS}: auto-prune on).
 * Malformed values fall back to defaults.
 *
 * <p>{@link #maxStoreSizeMb} is the <strong>artifact store</strong> display budget ({@code jk repo
 * storage}, store CAS + {@code repos/}). Exceeding it never evicts reachable blobs — GC only
 * reclaims unreferenced/expired garbage. {@link #maxCacheSizeMb} is the <strong>cache</strong>
 * budget ({@code jk cache storage}: cache CAS + action index + format stamps). Both treat {@code
 * 0} (and negatives) as unset — the documented default applies.
 */
public record JkCacheConfig(
        boolean autoPrune, int maxStoreSizeMb, int pruneIntervalDays, int recordTtlDays, int maxCacheSizeMb) {

    /** Default cache-tier utilization / prune budget (1 GiB). */
    public static final int DEFAULT_MAX_CACHE_SIZE_MB = 1024;

    /** Default artifact-store utilization budget — display only, never an eviction cue (4 GiB). */
    public static final int DEFAULT_MAX_STORE_SIZE_MB = 4096;

    public static final JkCacheConfig DEFAULTS =
            new JkCacheConfig(true, DEFAULT_MAX_STORE_SIZE_MB, 7, 30, DEFAULT_MAX_CACHE_SIZE_MB);

    /** Effective machine config: user-global file + env overrides. */
    public static JkCacheConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env) {
        JkCacheConfig base = fromToml(userConfig);
        Optional<Integer> envStoreMb = envPositiveInt(env, "JK_MAX_STORE_SIZE_MB");
        return new JkCacheConfig(
                EnvValues.bool(env, "JK_AUTO_PRUNE").orElse(base.autoPrune),
                envStoreMb.orElse(base.maxStoreSizeMb),
                envNonNegativeInt(env, "JK_PRUNE_INTERVAL_DAYS").orElse(base.pruneIntervalDays),
                envNonNegativeInt(env, "JK_RECORD_TTL_DAYS").orElse(base.recordTtlDays),
                envPositiveInt(env, "JK_MAX_CACHE_SIZE_MB").orElse(base.maxCacheSizeMb));
    }

    private static Optional<Integer> envNonNegativeInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i >= 0);
    }

    /** Size budgets: {@code 0} means unset (default applies), so only positive values count. */
    private static Optional<Integer> envPositiveInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i > 0);
    }

    /** {@code [cache]} table; missing/malformed → {@link #DEFAULTS}. Zero/negative sizes = unset. */
    public static JkCacheConfig fromToml(Path file) {
        TomlScan scan = TomlScan.scan(
                file,
                "cache.auto-prune",
                "cache.max-store-size-mb",
                "cache.prune-interval-days",
                "cache.record-ttl-days",
                "cache.max-cache-size-mb");
        boolean autoPrune =
                switch (String.valueOf(scan.get("cache.auto-prune"))) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> DEFAULTS.autoPrune;
                };
        Optional<Integer> storeMb = positive(scanInt(scan, "cache.max-store-size-mb"));
        int interval = nonNegative(scanInt(scan, "cache.prune-interval-days")).orElse(DEFAULTS.pruneIntervalDays);
        int ttl = nonNegative(scanInt(scan, "cache.record-ttl-days")).orElse(DEFAULTS.recordTtlDays);
        int cacheMb = positive(scanInt(scan, "cache.max-cache-size-mb")).orElse(DEFAULTS.maxCacheSizeMb);

        return new JkCacheConfig(autoPrune, storeMb.orElse(DEFAULTS.maxStoreSizeMb), interval, ttl, cacheMb);
    }

    /** Cache-tier budget in bytes ({@link #maxCacheSizeMb}). */
    public long maxCacheSizeBytes() {
        return Math.max(0, (long) maxCacheSizeMb) * 1024L * 1024L;
    }

    /** Artifact-store budget in bytes ({@link #maxStoreSizeMb}). */
    public long maxStoreSizeBytes() {
        return Math.max(0, (long) maxStoreSizeMb) * 1024L * 1024L;
    }

    /** A scanned integer scalar; absent or malformed → empty (the lenient-read contract). */
    private static Optional<Integer> scanInt(TomlScan scan, String key) {
        String v = scan.get(key);
        if (v == null) return Optional.empty();
        try {
            return Optional.of(Integer.valueOf(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> nonNegative(Optional<Integer> value) {
        return value.filter(i -> i >= 0);
    }

    /** Size budgets: {@code 0} (or a negative) means unset, so the documented default applies. */
    private static Optional<Integer> positive(Optional<Integer> value) {
        return value.filter(i -> i > 0);
    }
}
