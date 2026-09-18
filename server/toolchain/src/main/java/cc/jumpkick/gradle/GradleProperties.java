// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static cc.jumpkick.gradle.GradleScriptText.STR;
import static cc.jumpkick.gradle.GradleScriptText.firstNonNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The properties a build script's {@code $name} and {@code ${expression}} placeholders read, as the
 * scanner sees them without evaluating the script: {@code gradle.properties} entries, {@code ext {
 * }} assignments, Kotlin {@code extra} entries and script-level {@code val} / {@code def}
 * declarations of a string literal, and a version catalog's {@code [versions]} through {@code
 * libs.versions.x.get()}. Interpolating a text names every placeholder it could not resolve, so the
 * caller writes a row instead of a literal {@code $}.
 */
final class GradleProperties {

    /** A text with its placeholders substituted, and the property names none of the sources defined. */
    record Interpolated(String text, List<String> unresolved) {
        boolean complete() {
            return unresolved.isEmpty();
        }
    }

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$(?:\\{(?<braced>[^}]*)\\}|(?<bare>[A-Za-z_][A-Za-z0-9_]*))");
    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_]*";
    // val k = "v" / def k = 'v' / k = "v" (inside ext { }) / ext.k = "v" / project.ext.k = "v"
    private static final Pattern ASSIGN = Pattern.compile("(?m)^\\s*(?:(?:val|def|var|final|String)\\s+)?"
            + "(?:(?:project|rootProject)\\.)?(?:ext\\.)?(?<key>" + IDENT + ")(?:\\s*:\\s*String)?\\s*=\\s*" + STR
            + "\\s*$");
    // extra["k"] = "v"  /  ext["k"] = "v"  /  ext.set("k", "v")
    private static final Pattern INDEXED = Pattern.compile("(?:extra|ext)\\s*\\[\\s*" + STR + "\\s*\\]\\s*=\\s*" + STR);
    private static final Pattern SET_CALL = Pattern.compile("\\bset\\s*\\(\\s*" + STR + "\\s*,\\s*" + STR + "\\s*\\)");
    // val k by extra("v")  /  val k: String by extra("v")
    private static final Pattern BY_EXTRA = Pattern.compile(
            "(?:val|var)\\s+(?<key>" + IDENT + ")(?:\\s*:\\s*String)?\\s+by\\s+extra\\s*\\(\\s*" + STR + "\\s*\\)");
    // property("k") / findProperty("k") / properties["k"] / extra["k"]
    private static final Pattern LOOKUP = Pattern.compile(
            "^(?:(?:project|rootProject)\\.)?(?:property|findProperty|properties|extra|ext)\\s*[(\\[]\\s*" + STR
                    + "\\s*[)\\]]$");
    // libs.versions.okio.get() — a version catalog's [versions] entry
    private static final Pattern CATALOG_VERSION =
            Pattern.compile("^" + IDENT + "\\.versions\\.(?<alias>[A-Za-z0-9_.]+)\\.get\\(\\)$");
    // id("x") version someVal  /  kotlin("jvm") version kotlinVersion
    private static final Pattern BARE_VERSION_IDENT = Pattern.compile("\\bversion\\s+(?<ident>" + IDENT + ")\\b");

    private final Map<String, String> script;
    private final Map<String, String> files;
    private final @Nullable GradleVersionCatalog catalog;

    private GradleProperties(
            Map<String, String> script, Map<String, String> files, @Nullable GradleVersionCatalog catalog) {
        this.script = script;
        this.files = files;
        this.catalog = catalog;
    }

    /**
     * The properties {@code strippedScript} declares itself, over {@code fileProperties} (a {@code
     * gradle.properties} reading, {@link #fileProperties}), over {@code catalog}'s versions.
     */
    static GradleProperties of(
            String strippedScript, Map<String, String> fileProperties, @Nullable GradleVersionCatalog catalog) {
        Map<String, String> script = new LinkedHashMap<>();
        for (Matcher m = ASSIGN.matcher(strippedScript); m.find(); ) {
            script.put(m.group("key"), Objects.requireNonNull(firstNonNull(m.group(2), m.group(3))));
        }
        for (Matcher m = INDEXED.matcher(strippedScript); m.find(); ) {
            script.put(
                    Objects.requireNonNull(firstNonNull(m.group(1), m.group(2))),
                    Objects.requireNonNull(firstNonNull(m.group(3), m.group(4))));
        }
        for (Matcher m = SET_CALL.matcher(strippedScript); m.find(); ) {
            script.put(
                    Objects.requireNonNull(firstNonNull(m.group(1), m.group(2))),
                    Objects.requireNonNull(firstNonNull(m.group(3), m.group(4))));
        }
        for (Matcher m = BY_EXTRA.matcher(strippedScript); m.find(); ) {
            script.put(m.group("key"), Objects.requireNonNull(firstNonNull(m.group(2), m.group(3))));
        }
        return new GradleProperties(script, new LinkedHashMap<>(fileProperties), catalog);
    }

    /** Script-only properties: no {@code gradle.properties}, no catalog. */
    static GradleProperties of(String strippedScript) {
        return of(strippedScript, Map.of(), null);
    }

    /**
     * The {@code gradle.properties} of {@code projectDir}, over the build root's when the project is
     * a subproject (no settings file of its own, one in the parent directory).
     */
    static Map<String, String> fileProperties(Path projectDir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path parent = projectDir.toAbsolutePath().getParent();
        if (parent != null && !hasSettings(projectDir) && hasSettings(parent)) {
            out.putAll(readProperties(parent.resolve("gradle.properties")));
        }
        out.putAll(readProperties(projectDir.resolve("gradle.properties")));
        return out;
    }

    private static boolean hasSettings(Path dir) {
        for (String settings : GradleImporter.SETTINGS_FILES) {
            if (Files.isRegularFile(dir.resolve(settings))) return true;
        }
        return false;
    }

    private static Map<String, String> readProperties(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        }
        for (String name : props.stringPropertyNames())
            out.put(name, props.getProperty(name).trim());
        return out;
    }

    /** The value of {@code name}, script declarations first, then {@code gradle.properties}. */
    @Nullable
    String value(String name) {
        String v = script.get(name);
        return v != null ? v : files.get(name);
    }

    /** {@code text} with every placeholder it can resolve substituted; the rest are named, and left in place. */
    Interpolated interpolate(String text) {
        if (text.indexOf('$') < 0) return new Interpolated(text, List.of());
        StringBuilder out = new StringBuilder();
        List<String> unresolved = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String expression = m.group("bare") != null
                    ? m.group("bare")
                    : m.group("braced").trim();
            String value = resolve(expression);
            if (value == null) {
                unresolved.add(expression);
                out.append(m.group());
            } else {
                out.append(value);
            }
            last = m.end();
        }
        out.append(text, last, text.length());
        return new Interpolated(out.toString(), unresolved);
    }

    /** The value of a placeholder expression, or {@code null} when nothing defines it. */
    private @Nullable String resolve(String expression) {
        Matcher lookup = LOOKUP.matcher(expression);
        if (lookup.matches()) return value(Objects.requireNonNull(firstNonNull(lookup.group(1), lookup.group(2))));
        Matcher catalogVersion = CATALOG_VERSION.matcher(expression);
        if (catalogVersion.matches()) {
            return catalog == null
                    ? null
                    : catalog.resolveVersion(catalogVersion.group("alias")).orElse(null);
        }
        String name = expression;
        for (String prefix : List.of("project.", "rootProject.", "ext.", "extra.")) {
            if (name.startsWith(prefix)) name = name.substring(prefix.length());
        }
        return name.matches(IDENT) ? value(name) : null;
    }

    /**
     * A plugins block with each {@code version someVal} spelled as the literal the property holds
     * and each {@code "$x"} inside a version literal substituted. Placeholders nothing defines stay,
     * for the caller to report.
     */
    Interpolated resolvePluginVersions(String pluginsBody) {
        StringBuilder out = new StringBuilder();
        Matcher m = BARE_VERSION_IDENT.matcher(pluginsBody);
        int last = 0;
        while (m.find()) {
            String value = value(m.group("ident"));
            out.append(pluginsBody, last, m.start());
            out.append(value == null ? m.group() : "version \"" + value + "\"");
            last = m.end();
        }
        out.append(pluginsBody, last, pluginsBody.length());
        return interpolate(out.toString());
    }
}
