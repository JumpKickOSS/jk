// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Which output of a dependency coordinate (or workspace sibling) an edge selects.
 *
 * <p>{@link #MAIN} is the primary jar (default). {@link #TESTS} is the tests variant — Mill's
 * {@code transport.test} / Maven's {@code test-jar} classifier — and is only legal on test scopes
 * so helpers never leak into main artifacts.
 */
public enum DependencyKind {
    MAIN("main"),
    TESTS("tests");

    private final String toml;

    DependencyKind(String toml) {
        this.toml = toml;
    }

    /** Wire / TOML spelling ({@code "main"}, {@code "tests"}). */
    public String toml() {
        return toml;
    }

    /**
     * Parse a {@code kind = "..."} value. Unknown spellings throw so bad imports fail at parse
     * time rather than silently selecting main.
     */
    public static DependencyKind parse(String raw) {
        if (raw == null || raw.isBlank()) return MAIN;
        String s = raw.trim().toLowerCase();
        for (DependencyKind k : values()) {
            if (k.toml.equals(s)) return k;
        }
        throw new IllegalArgumentException("unknown dependency kind `" + raw + "` — expected `main` or `tests`");
    }
}
