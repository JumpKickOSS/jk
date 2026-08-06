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
 * <p>{@link #maxStoreSizeMb} is the <strong>artifact store</strong> budget ({@code jk repo
 * storage}, store CAS + {@code repos/}). {@link #maxCacheSizeMb} is the <strong>cache</strong>
 * budget ({@code jk cache storage}: cache CAS + action index + format stamps). Both treat {@code
 * 0} (and negatives) as unset — the documented default applies.
 *
 * <p>The store default is a <em>display</em> budget (utilization bars) only. Store blobs are
 * long-lived by design: LRU eviction of reachable store blobs runs only when the user set the
 * budget explicitly ({@link #storeBudgetConfigured}) or passed {@code --max-size}. The cache tier
 * is rebuildable, so its default budget does drive eviction.
 */
public record JkCacheConfig(
        boolean autoPrune,
        int maxStoreSizeMb,
        int pruneIntervalDays,
        int recordTtlDays,
        int maxCacheSizeMb,
        boolean storeBudgetConfigured) {

    /** Default cache-tier utilization / prune budget (1 GiB). */
    public static final int DEFAULT_MAX_CACHE_SIZE_MB = 1024;

    /** Default artifact-store utilization budget — display only, never an eviction cue (4 GiB). */
    public static final int DEFAULT_MAX_STORE_SIZE_MB = 4096;

    public static final JkCacheConfig DEFAULTS =
            new JkCacheConfig(true, DEFAULT_MAX_STORE_SIZE_MB, 7, 30, DEFAULT_MAX_CACHE_SIZE_MB, false);

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
                envPositiveInt(env, "JK_MAX_CACHE_SIZE_MB").orElse(base.maxCacheSizeMb),
                envStoreMb.isPresent() || base.storeBudgetConfigured);
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

        return new JkCacheConfig(
                autoPrune,
                storeMb.orElse(DEFAULTS.maxStoreSizeMb),
                interval,
                ttl,
                cacheMb,
                storeMb.isPresent());
    }

    /** Cache-tier budget in bytes ({@link #maxCacheSizeMb}). */
    public long maxCacheSizeBytes() {
        return Math.max(0, (long) maxCacheSizeMb) * 1024L * 1024L;
    }

    /** Artifact-store budget in bytes ({@link #maxStoreSizeMb}). */
    public long maxStoreSizeBytes() {
        return Math.max(0, (long) maxStoreSizeMb) * 1024L * 1024L;
    }

    /**
     * Store <em>eviction</em> budget in bytes: {@link #maxStoreSizeBytes()} when the user set the
     * budget explicitly (file key or env var), else {@code 0} — reachable store blobs are never
     * LRU-evicted on the display default alone.
     */
    public long configuredStoreSizeBytes() {
        return storeBudgetConfigured ? maxStoreSizeBytes() : 0L;
    }

    /**
     * Warnings for pre-split size knobs that are no longer read (JK-1505 renamed them with no
     * aliasing). One message per legacy key/env var present, naming its successor.
     */
    public static java.util.List<String> legacyKnobWarnings() {
        return legacyKnobWarnings(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #legacyKnobWarnings()} but against an explicit config file + env — for tests. */
    static java.util.List<String> legacyKnobWarnings(Path userConfig, Function<String, String> env) {
        var warnings = new java.util.ArrayList<String>();
        TomlScan scan = TomlScan.scan(userConfig, "cache.max-size-gb", "cache.action-max-size-mb");
        if (scan.get("cache.max-size-gb") != null) {
            warnings.add("config key [cache] max-size-gb is no longer read; set max-store-size-mb instead");
        }
        if (scan.get("cache.action-max-size-mb") != null) {
            warnings.add("config key [cache] action-max-size-mb is no longer read; set max-cache-size-mb instead");
        }
        if (env.apply("JK_MAX_SIZE_GB") != null) {
            warnings.add("env JK_MAX_SIZE_GB is no longer read; set JK_MAX_STORE_SIZE_MB instead");
        }
        if (env.apply("JK_ACTION_MAX_SIZE_MB") != null) {
            warnings.add("env JK_ACTION_MAX_SIZE_MB is no longer read; set JK_MAX_CACHE_SIZE_MB instead");
        }
        return warnings;
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
