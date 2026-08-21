// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import java.util.Locale;
import java.util.Optional;

/**
 * Layout names and language-default order shared by the template index and blank scaffolder.
 * The catalog itself is scanned from {@code .jk-template.toml} trees — not hardcoded here.
 */
public final class Giter8ShortNames {

    public static final String LAYOUT_SIMPLE = "simple";
    public static final String LAYOUT_TRADITIONAL = "traditional";
    /** Framework-specific tree (Grails {@code grails-app/}, etc.). */
    public static final String LAYOUT_CUSTOM = "custom";

    private Giter8ShortNames() {}

    /** Language default among existing dirs: java, else kotlin, else groovy. */
    public static Optional<String> defaultLang(Iterable<String> langs) {
        if (langs == null) return Optional.empty();
        boolean java = false, kotlin = false, groovy = false;
        for (String l : langs) {
            if (l == null) continue;
            switch (l.strip().toLowerCase(Locale.ROOT)) {
                case "java" -> java = true;
                case "kotlin" -> kotlin = true;
                case "groovy" -> groovy = true;
                default -> {}
            }
        }
        if (java) return Optional.of("java");
        if (kotlin) return Optional.of("kotlin");
        if (groovy) return Optional.of("groovy");
        return Optional.empty();
    }

    public static String normalizeLayout(String layout) {
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
