// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code [engine] keep-awake}: whether the engine keeps the machine from idle sleep while a job
 * runs. {@link #AC} holds it only on mains power, {@link #ALWAYS} on battery too, {@link #NEVER}
 * not at all. A closed lid is never blocked.
 */
public enum KeepAwake {
    AC,
    ALWAYS,
    NEVER;

    /** The spelling in {@code config.toml} and {@code JK_KEEP_AWAKE}. */
    public String spelling() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code ac}, {@code always} or {@code never}, any case; empty for anything else. */
    public static Optional<KeepAwake> parse(@Nullable String s) {
        if (s == null) return Optional.empty();
        String v = s.strip().replace("\"", "").toLowerCase(Locale.ROOT);
        for (KeepAwake k : values()) {
            if (k.spelling().equals(v)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
