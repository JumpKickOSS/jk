// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;

/**
 * {@code [resolve] pom-repositories}: whether the {@code <repositories>} a dependency's POM declares
 * are consulted for its subtree.
 *
 * <ul>
 * <li>{@link #USE} (default) — as Maven does, after every repository the project declares, under
 * the trust rules in docs/user/dependencies.md.
 * <li>{@link #IGNORE} — never: the project resolves only through the repositories it declares, and
 * the lock notes each POM-declared repository it passed over.
 * </ul>
 */
public enum PomRepositoriesPolicy {
    USE,
    IGNORE;

    /** Parse {@code use} / {@code ignore} (case-insensitive). */
    public static PomRepositoriesPolicy parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "use" -> USE;
            case "ignore" -> IGNORE;
            default ->
                throw new IllegalArgumentException(
                        "unknown pom-repositories policy `" + raw + "` (want use or ignore)");
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
