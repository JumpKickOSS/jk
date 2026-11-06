// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.model.Layout;
import cc.jumpkick.scaffold.NewProjectStep;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Layout names and language-default order shared by the template index and the catalogue loader.
 * The catalog itself is scanned from {@code .jk-template.toml} trees — not hardcoded here.
 */
public final class Giter8ShortNames {

    public static final String LAYOUT_SIMPLE = Layout.TOKEN_SIMPLE;
    public static final String LAYOUT_TRADITIONAL = Layout.TOKEN_TRADITIONAL;
    /** Framework-specific tree (Grails {@code grails-app/}, etc.). */
    public static final String LAYOUT_CUSTOM = "custom";

    private Giter8ShortNames() {}

    /** Language default among existing dirs: the first of java, kotlin, groovy, scala present. */
    public static Optional<String> defaultLang(Iterable<String> langs) {
        if (langs == null) return Optional.empty();
        Set<String> present = new HashSet<>();
        for (String l : langs) {
            if (l != null) present.add(l.strip().toLowerCase(Locale.ROOT));
        }
        return NewProjectStep.LANGS.stream().filter(present::contains).findFirst();
    }

    public static String normalizeLayout(@Nullable String layout) {
        if (layout == null || layout.isBlank()) return LAYOUT_TRADITIONAL;
        String l = layout.strip().toLowerCase(Locale.ROOT);
        return switch (l) {
            case LAYOUT_TRADITIONAL, "maven", "maven-like" -> LAYOUT_TRADITIONAL;
            case LAYOUT_CUSTOM, "framework", "grails" -> LAYOUT_CUSTOM;
            case LAYOUT_SIMPLE, "mill", "mill-like" -> LAYOUT_SIMPLE;
            default -> LAYOUT_TRADITIONAL;
        };
    }

    static boolean isKnownLayoutToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String l = raw.strip().toLowerCase(Locale.ROOT);
        return l.equals(LAYOUT_TRADITIONAL)
                || l.equals("maven")
                || l.equals("maven-like")
                || l.equals(LAYOUT_CUSTOM)
                || l.equals("framework")
                || l.equals("grails")
                || l.equals(LAYOUT_SIMPLE)
                || l.equals("mill")
                || l.equals("mill-like");
    }
}
