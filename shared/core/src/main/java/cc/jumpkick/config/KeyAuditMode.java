// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * {@code JK_KEY_AUDIT}: whether a build, once it has succeeded, takes the key of every step it ran
 * or hit again from the tree as the build left it, and what a key that moved does. A step's key
 * must not depend on what its own build writes; one that does misses on the next build of an
 * unchanged tree, runs again and stores again (JK-875KR).
 */
public enum KeyAuditMode {
    /** No audit: the default, since it costs one more key derivation per step. */
    OFF,
    /** A key that moved is a {@code key-moved} warning naming the module, the step and the tokens. */
    WARN,
    /** As {@link #WARN}, and the build fails. */
    STRICT;

    /** The environment variable the client reads the mode from. */
    public static final String ENV = "JK_KEY_AUDIT";

    /**
     * The mode {@code raw} spells: {@code strict}; {@code warn} or any true spelling ({@code 1},
     * {@code true}, {@code yes}, {@code on}); anything else, unset included, is {@link #OFF}.
     */
    public static KeyAuditMode parse(@Nullable String raw) {
        if (raw == null) return OFF;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "strict" -> STRICT;
            case "warn", "1", "true", "yes", "on" -> WARN;
            default -> OFF;
        };
    }

    /** Whether the audit runs at all. */
    public boolean on() {
        return this != OFF;
    }

    /** The wire spelling: absent for {@link #OFF}. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
