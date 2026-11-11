// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlTable;

/**
 * {@code [cache] heavy-ttl} / {@code JK_HEAVY_TTL}: how long a heavy output (a blob of {@link
 * DiskGuard#HEAVY_BYTES} or more: a native image, a fat jar, a jlink image) stays in the cache after
 * its last use. Heavy outputs are expensive to make and to hold alike, and rarely asked for again
 * after a day: the window is long enough to help parallel worktrees and agents through a working
 * day, and no longer. {@code 0} keeps them out of the cache altogether, so they live only where
 * they were built.
 *
 * <p>Default {@link #DEFAULT}, and {@code 0} on CI ({@code CI} set), where a short-lived runner gains
 * nothing from them and a long-lived one is where a full disk does the most harm. Capped at {@link
 * #MAX}. A project's {@code jk.toml} may only turn heavy caching off ({@code [cache] heavy = false}),
 * never lengthen the window: disk is the machine's.
 */
public final class HeavyTtl {

    /** Twelve hours from the last use. */
    public static final Duration DEFAULT = Duration.ofHours(12);

    /** No configuration keeps a heavy output longer than a day. */
    public static final Duration MAX = Duration.ofHours(24);

    private HeavyTtl() {}

    /** Effective machine value: {@code JK_HEAVY_TTL} over the user-global file over the default. */
    public static Duration resolve() {
        return resolve(JkDirs.userConfigFile(), ProcessEnv::get);
    }

    /** As {@link #resolve()} against an explicit config file and environment. */
    public static Duration resolve(Path userConfig, Function<String, @Nullable String> env) {
        Duration fromEnv =
                SessionExpiry.duration(EnvValues.string(env, "JK_HEAVY_TTL").orElse(null));
        if (fromEnv != null) return clamp(fromEnv);
        TomlTable cache =
                TomlValues.parse(userConfig).map(root -> root.getTable("cache")).orElse(null);
        Duration fromFile = SessionExpiry.duration(cache == null ? null : cache.get(List.of("heavy-ttl")));
        if (fromFile != null) return clamp(fromFile);
        return EnvValues.isCi(env) ? Duration.ZERO : DEFAULT;
    }

    /** True when heavy outputs may enter the cache at all under {@code ttl}. */
    public static boolean caches(Duration ttl) {
        return !ttl.isZero() && !ttl.isNegative();
    }

    private static Duration clamp(Duration d) {
        if (d.isNegative()) return Duration.ZERO;
        return d.compareTo(MAX) > 0 ? MAX : d;
    }
}
