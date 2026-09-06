// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.List;
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

    /**
     * The layout tokens {@code jk new} / {@code jk init} accept, first is the default. {@code auto}
     * places no tree of its own — it is the {@code jk.toml} value meaning "detect", which {@code jk
     * init} does against an existing tree — but it is last here because {@link #parse} accepts it
     * and the help must list everything the flag takes. One list for the CLI help, the MCP schema
     * and {@code builtinLayouts}, so the three surfaces cannot describe this enum three ways.
     */
    public static final List<String> SCAFFOLD_TOKENS = List.of(TOKEN_TRADITIONAL, TOKEN_SIMPLE, TOKEN_AUTO);

    /**
     * {@code traditional (default) | simple | auto} — the help phrase every scaffold surface shows.
     * {@code auto} is last: it places no tree of its own ({@code jk init} detects the existing one),
     * but it is a value {@link #parse} accepts, so the help lists everything the flag takes.
     */
    public static String scaffoldHelp() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SCAFFOLD_TOKENS.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(SCAFFOLD_TOKENS.get(i));
            if (i == 0) sb.append(" (default)");
        }
        return sb.toString();
    }

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
