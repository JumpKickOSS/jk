// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Machine-scoped {@code [history]} journal policy (on by default). Retention: {@code max-age-days}
 * and {@code max-disk-mb}, whichever bites first. Not project-overridable; env overrides apply.
 */
public record JkHistoryConfig(boolean enabled, int maxAgeDays, int maxDiskMb) {

    public static final boolean DEFAULT_ENABLED = true;

    public static final int DEFAULT_MAX_AGE_DAYS = 30;

    public static final int DEFAULT_MAX_DISK_MB = 512;

    public static final JkHistoryConfig DEFAULTS =
            new JkHistoryConfig(DEFAULT_ENABLED, DEFAULT_MAX_AGE_DAYS, DEFAULT_MAX_DISK_MB);

    /** Effective machine config: user-global file + {@code JK_HISTORY_*} env. */
    public static JkHistoryConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkHistoryConfig resolve(Path userConfig, Function<String, String> env) {
        JkHistoryConfig base = fromToml(userConfig);
        return new JkHistoryConfig(
                EnvValues.bool(env, "JK_HISTORY_ENABLED").orElse(base.enabled),
                envNonNegativeInt(env, "JK_HISTORY_MAX_AGE_DAYS").orElse(base.maxAgeDays),
                envNonNegativeInt(env, "JK_HISTORY_MAX_DISK_MB").orElse(base.maxDiskMb));
    }

    private static Optional<Integer> envNonNegativeInt(Function<String, String> env, String name) {
        return EnvValues.intValue(env, name).filter(i -> i >= 0);
    }

    /**
     * Load from a TOML file's {@code [history]} table — the explicit-path seam used by {@link
     * #resolve()} and by tests. Missing file, missing table, or malformed file → {@link #DEFAULTS}.
     * Negative integers are rejected (a negative age/budget is meaningless) and fall back to the
     * default.
     */
    public static JkHistoryConfig fromToml(Path file) {
        Optional<TomlParseResult> parsed = TomlValues.parse(file);
        if (parsed.isEmpty()) return DEFAULTS;
        TomlTable history = parsed.get().getTable("history");
        if (history == null) return DEFAULTS;

        boolean enabled = TomlValues.optBoolean(history, "enabled").orElse(DEFAULTS.enabled);
        int maxAge = nonNegative(TomlValues.optInt(history, "max-age-days")).orElse(DEFAULTS.maxAgeDays);
        int maxDisk = nonNegative(TomlValues.optInt(history, "max-disk-mb")).orElse(DEFAULTS.maxDiskMb);
        return new JkHistoryConfig(enabled, maxAge, maxDisk);
    }

    private static Optional<Integer> nonNegative(Optional<Integer> value) {
        return value.filter(i -> i >= 0);
    }

    /** Age budget in milliseconds, or {@code 0} for "no age limit" (keep regardless of age). */
    public long maxAgeMillis() {
        return maxAgeDays <= 0 ? 0L : (long) maxAgeDays * 86_400_000L;
    }

    /** Disk budget in bytes, or {@code 0} for "no cap" (keep regardless of total size). */
    public long maxDiskBytes() {
        return maxDiskMb <= 0 ? 0L : (long) maxDiskMb * 1024L * 1024L;
    }
}
