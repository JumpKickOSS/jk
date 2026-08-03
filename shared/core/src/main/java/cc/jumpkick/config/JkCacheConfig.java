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
 * <p>{@link #maxSizeGb} is the <strong>CAS / store</strong> budget ({@code jk repo storage}, prune
 * {@code --max-size}). {@link #actionMaxSizeMb} is the <strong>action cache</strong> budget
 * ({@code jk cache storage} utilization bar); default 1024 MiB. Both size budgets treat {@code 0}
 * (and negatives) as unset — the documented default applies, on every surface (JK-1441).
 */
public record JkCacheConfig(
        boolean autoPrune,
        Optional<Integer> maxSizeGb,
        int pruneIntervalDays,
        int recordTtlDays,
        int actionMaxSizeMb) {

    /** Default action-cache utilization denominator (1 GiB). */
    public static final int DEFAULT_ACTION_MAX_SIZE_MB = 1024;

    public static final JkCacheConfig DEFAULTS =
            new JkCacheConfig(true, Optional.empty(), 7, 30, DEFAULT_ACTION_MAX_SIZE_MB);

    /** Effective machine config: user-global file + env overrides. */
    public static JkCacheConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env) {
        JkCacheConfig base = fromToml(userConfig);
        return new JkCacheConfig(
                EnvValues.bool(env, "JK_AUTO_PRUNE").orElse(base.autoPrune),
                envPositiveInt(env, "JK_MAX_SIZE_GB").or(() -> base.maxSizeGb),
                envNonNegativeInt(env, "JK_PRUNE_INTERVAL_DAYS").orElse(base.pruneIntervalDays),
                envNonNegativeInt(env, "JK_RECORD_TTL_DAYS").orElse(base.recordTtlDays),
                envPositiveInt(env, "JK_ACTION_MAX_SIZE_MB").orElse(base.actionMaxSizeMb));
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
                "cache.max-size-gb",
                "cache.prune-interval-days",
                "cache.record-ttl-days",
                "cache.action-max-size-mb");
        boolean autoPrune =
                switch (String.valueOf(scan.get("cache.auto-prune"))) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> DEFAULTS.autoPrune;
                };
        Optional<Integer> maxSize = positive(scanInt(scan, "cache.max-size-gb"));
        int interval = nonNegative(scanInt(scan, "cache.prune-interval-days")).orElse(DEFAULTS.pruneIntervalDays);
        int ttl = nonNegative(scanInt(scan, "cache.record-ttl-days")).orElse(DEFAULTS.recordTtlDays);
        int actionMb = positive(scanInt(scan, "cache.action-max-size-mb")).orElse(DEFAULTS.actionMaxSizeMb);

        return new JkCacheConfig(autoPrune, maxSize, interval, ttl, actionMb);
    }

    /** Action-cache utilization denominator in bytes ({@link #actionMaxSizeMb}). */
    public long actionMaxSizeBytes() {
        return Math.max(0, (long) actionMaxSizeMb) * 1024L * 1024L;
    }

    /**
     * Store / CAS utilization denominator in bytes. Unset {@link #maxSizeGb} → documented 20 GiB
     * default (same as historical {@code jk cache info}).
     */
    public long storeMaxSizeBytes() {
        return (long) maxSizeGb.orElse(20) * 1024L * 1024L * 1024L;
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
