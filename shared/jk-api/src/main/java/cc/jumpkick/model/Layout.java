// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Source layout: {@code simple} (Mill-like), {@code traditional} (Maven), or {@code auto}
 * (infer from the tree; default when the key is omitted). Prefer omitting the key — set it
 * only to override ambiguous trees.
 */
public enum Layout {
    SIMPLE,
    TRADITIONAL,
    AUTO;

    /** Canonical token for {@link #SIMPLE}; usable in {@code switch} case labels. */
    public static final String TOKEN_SIMPLE = "simple";

    /** Canonical token for {@link #TRADITIONAL}; usable in {@code switch} case labels. */
    public static final String TOKEN_TRADITIONAL = "traditional";

    /** Canonical token for {@link #AUTO}; usable in {@code switch} case labels. */
    public static final String TOKEN_AUTO = "auto";

    /** Parse from a jk.toml string value; null or blank → AUTO. */
    public static Layout parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case TOKEN_SIMPLE -> SIMPLE;
            case TOKEN_TRADITIONAL -> TRADITIONAL;
            case TOKEN_AUTO -> AUTO;
            default ->
                throw new IllegalArgumentException("layout must be \""
                        + TOKEN_SIMPLE
                        + "\", \""
                        + TOKEN_TRADITIONAL
                        + "\", or \""
                        + TOKEN_AUTO
                        + "\" (got: "
                        + raw
                        + ")");
        };
    }

    /** The string written to jk.toml, or null for AUTO (omitted). */
    public @Nullable String tomlValue() {
        return switch (this) {
            case SIMPLE -> TOKEN_SIMPLE;
            case TRADITIONAL -> TOKEN_TRADITIONAL;
            case AUTO -> null;
        };
    }

    /**
     * Canonical lowercase token for CLI flags and wire ({@code simple}, {@code traditional},
     * {@code auto}). Prefer {@link #tomlValue()} when writing {@code jk.toml} (AUTO is omitted).
     */
    public String token() {
        String v = tomlValue();
        return v != null ? v : TOKEN_AUTO;
    }
}
