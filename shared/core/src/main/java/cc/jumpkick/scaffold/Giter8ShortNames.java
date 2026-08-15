// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Official Giter8 short-name catalog shared by {@code jk new --template}, {@code GET
 * /api/templates}, and help text. Not an exclusive allow-list for resolution — unknown short names
 * may still resolve from disk / git sources.
 *
 * <p><b>Portable metadata</b> lives on the template itself in {@code default.properties}:
 *
 * <ul>
 *   <li>{@code jk_languages} — comma-separated {@code java} / {@code kotlin} / {@code groovy}
 *   <li>{@code jk_layout} — {@code simple} | {@code traditional} | {@code custom}
 * </ul>
 *
 * The catalog below is a fast index for first-party short names (description + defaults). {@link
 * Giter8TemplateIndex} merges catalog rows with on-disk props when building the picker list.
 *
 * <p><b>Layout vs template:</b> simple/traditional is a <em>blank scaffolder</em> choice only. A
 * Giter8 apply copies a fixed tree (our apply has no conditionals), so the user's layout selection
 * does not reshape a template. Framework templates may use {@code custom} (e.g. Grails).
 */
public final class Giter8ShortNames {

    /** Layout the template ships; not a user override at apply time. */
    public static final String LAYOUT_SIMPLE = "simple";

    public static final String LAYOUT_TRADITIONAL = "traditional";
    /** Framework-specific tree (Grails {@code grails-app/}, etc.) — not Mill simple or Maven traditional. */
    public static final String LAYOUT_CUSTOM = "custom";

    /**
     * One catalog row: stable short name, description, languages, and the layout the template
     * materializes.
     */
    public record Entry(String id, String description, List<String> languages, String layout) {
        public Entry {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
            if (description == null) description = "";
            languages = languages == null || languages.isEmpty() ? List.of("java") : List.copyOf(languages);
            layout = normalizeLayout(layout);
        }

        /** True when this template is appropriate for {@code lang} ({@code java}/{@code kotlin}/{@code groovy}). */
        public boolean supports(String lang) {
            if (lang == null || lang.isBlank()) return true;
            String l = lang.strip().toLowerCase(Locale.ROOT);
            for (String s : languages) {
                if (s.equalsIgnoreCase(l)) return true;
            }
            return false;
        }

        public Entry withDescription(String d) {
            return new Entry(id, d == null ? description : d, languages, layout);
        }

        public Entry withLanguages(List<String> langs) {
            return new Entry(id, description, langs, layout);
        }

        public Entry withLayout(String lay) {
            return new Entry(id, description, languages, lay);
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            entry("java-cli", "Simple Java 25 executable (Mill SIMPLE layout)", LAYOUT_SIMPLE, "java"),
            entry("kotlin-cli", "Simple Kotlin executable (Mill SIMPLE layout)", LAYOUT_SIMPLE, "kotlin"),
            entry("java-cli-native", "Interactive Java CLI with JLine (jk native ready)", LAYOUT_SIMPLE, "java"),
            entry("spring-boot-webmvc", "Spring Boot 4.1 WebMVC + JPA/H2 + Actuator", LAYOUT_TRADITIONAL, "java"),
            entry(
                    "spring-boot-webmvc-kotlin",
                    "Kotlin Spring Boot 4.1 WebMVC + JPA/H2 + Actuator",
                    LAYOUT_TRADITIONAL,
                    "kotlin"),
            entry("spring-boot-mcp", "Spring Boot MCP server (Spring AI, @Tool over SSE)", LAYOUT_TRADITIONAL, "java"),
            entry("quarkus", "Quarkus 3.x REST application ([quarkus] plugin)", LAYOUT_SIMPLE, "java"),
            entry("ktor-3", "Ktor 3 service with Koin DI and Exposed/H2", LAYOUT_SIMPLE, "kotlin"),
            entry("micronaut", "Micronaut HTTP service (compile-time DI, Netty)", LAYOUT_SIMPLE, "java"),
            entry("grails-8", "Grails 8 REST app (GORM, H2, Groovy 5)", LAYOUT_CUSTOM, "groovy"));

    private Giter8ShortNames() {}

    private static Entry entry(String id, String description, String layout, String... languages) {
        return new Entry(id, description, List.of(languages), layout);
    }

    /** Ordered official catalog (immutable). */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** Immutable ordered map of short name → one-line description (CLI help / legacy callers). */
    public static Map<String, String> descriptions() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Entry e : ENTRIES) m.put(e.id(), e.description());
        return Collections.unmodifiableMap(m);
    }

    public static Optional<Entry> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        for (Entry e : ENTRIES) {
            if (e.id().equals(id)) return Optional.of(e);
        }
        return Optional.empty();
    }

    public static String normalizeLayout(String layout) {
        if (layout == null || layout.isBlank()) return LAYOUT_SIMPLE;
        String l = layout.strip().toLowerCase(Locale.ROOT);
        return switch (l) {
            case LAYOUT_TRADITIONAL, "maven", "maven-like" -> LAYOUT_TRADITIONAL;
            case LAYOUT_CUSTOM, "framework", "grails" -> LAYOUT_CUSTOM;
            case LAYOUT_SIMPLE, "mill", "mill-like" -> LAYOUT_SIMPLE;
            default -> LAYOUT_SIMPLE;
        };
    }

    /**
     * Parse {@code jk_languages} / {@code jk_language} / {@code language} from a template's {@code
     * default.properties} (or equivalent map). Returns empty when the template does not declare a
     * language — callers may fall back to catalog metadata or "show for all".
     *
     * <p>Accepted forms: {@code java}, {@code java,kotlin}, whitespace around commas OK.
     */
    public static List<String> languagesFromProperties(Map<String, String> props) {
        if (props == null || props.isEmpty()) return List.of();
        String raw = firstNonBlank(props, "jk_languages", "jk_language", "language");
        if (raw == null) return List.of();
        // A "language=25" style JDK pin is not a language name — only accept known JVM langs.
        String[] parts = raw.split("[,\\s]+");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String l = p.strip().toLowerCase(Locale.ROOT);
            if (l.equals("java") || l.equals("kotlin") || l.equals("groovy")) {
                if (!out.contains(l)) out.add(l);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Parse {@code jk_layout} / {@code layout} from props. Empty when undeclared (caller falls back
     * to catalog or tree inference).
     */
    public static Optional<String> layoutFromProperties(Map<String, String> props) {
        if (props == null || props.isEmpty()) return Optional.empty();
        String raw = firstNonBlank(props, "jk_layout", "layout");
        if (raw == null) return Optional.empty();
        String l = raw.strip().toLowerCase(Locale.ROOT);
        // Ignore junk (e.g. accidental non-layout values).
        if (l.equals(LAYOUT_SIMPLE)
                || l.equals(LAYOUT_TRADITIONAL)
                || l.equals(LAYOUT_CUSTOM)
                || l.equals("mill")
                || l.equals("mill-like")
                || l.equals("maven")
                || l.equals("maven-like")
                || l.equals("framework")
                || l.equals("grails")) {
            return Optional.of(normalizeLayout(l));
        }
        return Optional.empty();
    }

    private static String firstNonBlank(Map<String, String> props, String... keys) {
        for (String k : keys) {
            String v = props.get(k);
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }
}
