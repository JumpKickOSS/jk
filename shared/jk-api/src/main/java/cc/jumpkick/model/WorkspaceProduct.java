// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

/**
 * Which build product a workspace sibling edge selects.
 *
 * <p>{@link #MAIN} is the published main jar (default). {@link #TESTS} is the sibling's test
 * compile output — Mill's {@code transport.test} / Maven's {@code test-jar} — and is only legal on
 * test scopes so helpers never leak into main artifacts.
 */
public enum WorkspaceProduct {
    MAIN("main"),
    TESTS("tests");

    private final String toml;

    WorkspaceProduct(String toml) {
        this.toml = toml;
    }

    /** Wire / TOML spelling ({@code "main"}, {@code "tests"}). */
    public String toml() {
        return toml;
    }

    /**
     * Parse a {@code product = "..."} value. Unknown spellings throw so bad imports fail at parse
     * time rather than silently selecting main.
     */
    public static WorkspaceProduct parse(String raw) {
        if (raw == null || raw.isBlank()) return MAIN;
        String s = raw.trim().toLowerCase();
        for (WorkspaceProduct p : values()) {
            if (p.toml.equals(s)) return p;
        }
        throw new IllegalArgumentException(
                "unknown workspace product `" + raw + "` — expected `main` or `tests`");
    }
}
