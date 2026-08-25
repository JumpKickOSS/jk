// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Source layout: {@code simple} (Mill-like), {@code traditional} (Maven), or {@code auto}
 * (infer from the tree; default when the key is omitted). Prefer omitting the key — set it
 * only to override ambiguous trees.
 */
public enum Layout {
    SIMPLE,
    TRADITIONAL,
    AUTO;

    /** Parse from a jk.toml string value; null or blank → AUTO. */
    public static Layout parse(String raw) {
        if (raw == null || raw.isBlank()) return AUTO;
        return switch (raw.trim().toLowerCase()) {
            case "simple" -> SIMPLE;
            case "traditional" -> TRADITIONAL;
            case "auto" -> AUTO;
            default ->
                throw new IllegalArgumentException(
                        "layout must be \"simple\", \"traditional\", or \"auto\" (got: " + raw + ")");
        };
    }

    /** The string written to jk.toml, or null for AUTO (omitted). */
    public String tomlValue() {
        return switch (this) {
            case SIMPLE -> "simple";
            case TRADITIONAL -> "traditional";
            case AUTO -> null;
        };
    }
}
