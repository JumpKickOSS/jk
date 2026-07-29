// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * Version selector. {@link #parse} treats bare versions as {@link Exact} ({@code :} form);
 * {@link #parseFloating} as {@link Caret} ({@code @} form). Decorations: {@code ^}/{@code ~}/
 * {@code =}/range/{@code latest}/{@code snapshot}.
 */
public sealed interface VersionSelector {

    /** The text the user wrote, preserved for round-tripping and diagnostics. */
    String raw();

    record Caret(String raw, String version) implements VersionSelector {}

    record Exact(String raw, String version) implements VersionSelector {}

    record Tilde(String raw, String version) implements VersionSelector {}

    record Range(String raw) implements VersionSelector {}

    record Latest(String raw) implements VersionSelector {}

    /**
     * {@code snapshot} — the newest advertised version, pre-releases included.
     *
     * <p>The deliberate opt-in counterpart to every other floating selector, which resolve to stable
     * releases only (JK-1287). Before this existed, reaching an RC was something that happened *to*
     * you: a caret admitted the next major's pre-releases because {@code 3.0-rc5} sorts below
     * {@code 3.0}. Now wanting a bleeding edge is something you say.
     */
    record Snapshot(String raw) implements VersionSelector {}

    static VersionSelector parse(String spec) {
        return parse(spec, /* bareIsCaret */ false);
    }

    /** Parse with caret-default bare versions ({@code @} form). */
    public static VersionSelector parseFloating(String spec) {
        return parse(spec, /* bareIsCaret */ true);
    }

    private static VersionSelector parse(String spec, boolean bareIsCaret) {
        Objects.requireNonNull(spec, "spec");
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("version selector must not be blank");
        }
        if ("latest".equalsIgnoreCase(trimmed)) {
            return new Latest(spec);
        }
        if ("snapshot".equalsIgnoreCase(trimmed)) {
            return new Snapshot(spec);
        }
        if (trimmed.startsWith("^")) {
            return new Caret(spec, trimmed.substring(1).trim());
        }
        if (trimmed.startsWith("~")) {
            return new Tilde(spec, trimmed.substring(1).trim());
        }
        if (trimmed.startsWith(">") || trimmed.startsWith("<") || trimmed.contains(",")) {
            return new Range(spec);
        }
        // Leading `=` is always Exact (explicit pin / lockfile round-trip).
        if (trimmed.startsWith("=")) {
            return new Exact(spec, trimmed.substring(1).trim());
        }
        return bareIsCaret ? new Caret(spec, trimmed) : new Exact(spec, trimmed);
    }
}
