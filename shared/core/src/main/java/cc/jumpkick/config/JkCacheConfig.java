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
 */
public record JkCacheConfig(boolean autoPrune, Optional<Integer> maxSizeGb, int pruneIntervalDays, int recordTtlDays) {

    public static final JkCacheConfig DEFAULTS = new JkCacheConfig(true, Optional.empty(), 7, 30);

    /** Effective machine config: user-global file + env overrides. */
    public static JkCacheConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkCacheConfig resolve(Path userConfig, Function<String, String> env) {
        JkCacheConfig base = fromToml(userConfig);
        return new JkCacheConfig(
                EnvValues.bool(env, "JK_AUTO_PRUNE").orElse(base.autoPrune),
                envNonNegativeInt(env, "JK_MAX_SIZE_GB").or(() -> base.maxSizeGb),
                envNonNegativeInt(env, "JK_PRUNE_INTERVAL_DAYS").orElse(base.pruneIntervalDays),
                envNonNegativeInt(env, "JK_RECORD_TTL_DAYS").orElse(base.recordTtlDays));
    }

    private static Optional<Integer> envNonNegativeInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i >= 0);
    }

    /** {@code [cache]} table; missing/malformed → {@link #DEFAULTS}. Negative ints rejected. */
    public static JkCacheConfig fromToml(Path file) {
        TomlScan scan = TomlScan.scan(
                file, "cache.auto-prune", "cache.max-size-gb", "cache.prune-interval-days", "cache.record-ttl-days");
        boolean autoPrune =
                switch (String.valueOf(scan.get("cache.auto-prune"))) {
                    case "true" -> true;
                    case "false" -> false;
                    default -> DEFAULTS.autoPrune;
                };
        Optional<Integer> maxSize = nonNegative(scanInt(scan, "cache.max-size-gb"));
        int interval = nonNegative(scanInt(scan, "cache.prune-interval-days")).orElse(DEFAULTS.pruneIntervalDays);
        int ttl = nonNegative(scanInt(scan, "cache.record-ttl-days")).orElse(DEFAULTS.recordTtlDays);

        return new JkCacheConfig(autoPrune, maxSize, interval, ttl);
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
}
