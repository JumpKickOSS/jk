// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * Machine-scoped {@code [history]} journal policy (on by default). Retention: {@code max-age-days}
 * and {@code max-disk-mb}, whichever bites first. Not project-overridable; env overrides apply,
 * ranked by {@link MachineConfig}.
 */
public record JkHistoryConfig(boolean enabled, int maxAgeDays, int maxDiskMb) {

    public static final boolean DEFAULT_ENABLED = true;

    public static final int DEFAULT_MAX_AGE_DAYS = 30;

    public static final int DEFAULT_MAX_DISK_MB = 512;

    public static final JkHistoryConfig DEFAULTS =
            new JkHistoryConfig(DEFAULT_ENABLED, DEFAULT_MAX_AGE_DAYS, DEFAULT_MAX_DISK_MB);

    private static final MachineConfig<Boolean> ENABLED = MachineConfig.of(DEFAULT_ENABLED);

    /** A negative age or budget is meaningless, so it is not a value — it falls through. */
    private static final MachineConfig<Integer> MAX_AGE_DAYS = MachineConfig.of(DEFAULT_MAX_AGE_DAYS, i -> i >= 0);

    private static final MachineConfig<Integer> MAX_DISK_MB = MachineConfig.of(DEFAULT_MAX_DISK_MB, i -> i >= 0);

    /** Effective machine config: user-global file + {@code JK_HISTORY_*} env. */
    public static JkHistoryConfig resolve() {
        return resolve(JkDirs.userConfigFile(), System::getenv);
    }

    /** As {@link #resolve()} but against an explicit config file + env — for tests. */
    static JkHistoryConfig resolve(Path userConfig, Function<String, String> env) {
        TomlTable history = historyTable(userConfig);
        return new JkHistoryConfig(
                ENABLED.layer(
                        EnvValues.bool(env, "JK_HISTORY_ENABLED").orElse(null),
                        TomlValues.optBoolean(history, "enabled").orElse(null)),
                MAX_AGE_DAYS.layer(
                        EnvValues.intValue(env, "JK_HISTORY_MAX_AGE_DAYS").orElse(null),
                        TomlValues.optInt(history, "max-age-days").orElse(null)),
                MAX_DISK_MB.layer(
                        EnvValues.intValue(env, "JK_HISTORY_MAX_DISK_MB").orElse(null),
                        TomlValues.optInt(history, "max-disk-mb").orElse(null)));
    }

    /**
     * Load from a TOML file's {@code [history]} table — the explicit-path seam used by {@link
     * #resolve()} and by tests. Missing file, missing table, or malformed file → {@link #DEFAULTS}.
     */
    public static JkHistoryConfig fromToml(Path file) {
        TomlTable history = historyTable(file);
        return new JkHistoryConfig(
                ENABLED.layer(TomlValues.optBoolean(history, "enabled").orElse(null)),
                MAX_AGE_DAYS.layer(TomlValues.optInt(history, "max-age-days").orElse(null)),
                MAX_DISK_MB.layer(TomlValues.optInt(history, "max-disk-mb").orElse(null)));
    }

    /** The {@code [history]} table, or {@code null} for a missing/unreadable/table-less file. */
    private static @Nullable TomlTable historyTable(Path file) {
        return TomlValues.parse(file).map(root -> root.getTable("history")).orElse(null);
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
