// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code [mcp] session-expiry} / {@code JK_MCP_SESSION_EXPIRY}: a session with no activity for this
 * long ends, and its MCP connection is forgotten. It governs every session, MCP, BSP, shell and
 * dashboard alike, so it is read wherever sessions are, whatever {@code [http]} and {@code [mcp]
 * enabled} say. A missing or unreadable file, or a value that is not a positive duration, means
 * {@link #DEFAULT}.
 */
public final class SessionExpiry {

    public static final Duration DEFAULT = Duration.ofHours(24);

    private static final MachineConfig<Duration> EXPIRY = MachineConfig.of(DEFAULT, Duration::isPositive);

    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([smhd])?");

    private SessionExpiry() {}

    /** Effective machine value: {@code JK_MCP_SESSION_EXPIRY} over the user-global file over {@link #DEFAULT}. */
    public static Duration resolve() {
        return resolve(JkDirs.userConfigFile(), ProcessEnv::get);
    }

    /** As {@link #resolve()} but against an explicit config file + env. */
    public static Duration resolve(Path userConfig, Function<String, @Nullable String> env) {
        TomlTable mcp =
                TomlValues.parse(userConfig).map(root -> root.getTable("mcp")).orElse(null);
        return EXPIRY.layer(
                duration(EnvValues.string(env, "JK_MCP_SESSION_EXPIRY").orElse(null)),
                duration(mcp == null ? null : mcp.get(List.of("session-expiry"))));
    }

    /**
     * {@code "30m"}, {@code "24h"}, {@code "7d"}, {@code "90s"}, or a number of seconds; null when
     * {@code value} is none of them.
     */
    static @Nullable Duration duration(@Nullable Object value) {
        if (value instanceof Long seconds) return Duration.ofSeconds(seconds);
        if (!(value instanceof String s)) return null;
        Matcher m = DURATION.matcher(s.trim());
        if (!m.matches()) return null;
        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = m.group(2) == null ? "s" : m.group(2);
        try {
            return switch (unit) {
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> Duration.ofSeconds(n);
            };
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** {@code d} as {@link #duration} reads it: whole days past one day, else hours, minutes or seconds. */
    public static String spell(Duration d) {
        long s = d.toSeconds();
        if (s % 86_400 == 0 && s > 86_400) return s / 86_400 + "d";
        if (s % 3_600 == 0) return s / 3_600 + "h";
        if (s % 60 == 0) return s / 60 + "m";
        return s + "s";
    }
}
