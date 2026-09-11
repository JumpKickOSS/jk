// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.script;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Scans a script's leading comments for jk ({@code //jk dep|jdk|repo|…}) and JBang
 * ({@code //DEPS}, {@code //JAVA}, …) directives; Kotlin {@code @file:} forms via {@link #parseKotlin}.
 * Unknown directives are ignored. Stops at the first non-blank, non-comment line.
 */
public final class ScriptHeaderParser {

    private static final Pattern JK_DIRECTIVE = Pattern.compile("//jk\\s+([a-z][a-z-]*)\\s*(.*)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern FILE_ANNOTATION =
            Pattern.compile("@file\\s*:\\s*(DependsOn|Repository)\\s*\\((.*)\\)\\s*");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");

    private ScriptHeaderParser() {}

    public static ScriptHeader parse(String script) {
        List<Dependency> deps = new ArrayList<>();
        List<URI> repos = new ArrayList<>();
        List<String> features = new ArrayList<>();
        List<String> javacOptions = new ArrayList<>();
        List<String> javaOptions = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        List<String> files = new ArrayList<>();
        Integer release = null;
        String main = null;
        String gav = null;
        String description = null;
        String kotlinVersion = null;

        for (String rawLine : script.split("\\R", -1)) {
            String line = rawLine.strip();
            // Shebang shim: ///usr/bin/env jk run "$0"; exit $?
            if (line.startsWith("///")) continue;
            if (line.isEmpty()) continue;
            if (line.startsWith("//")) {
                ParsedDirective d = parseDirective(line);
                if (d != null) {
                    switch (d.name) {
                        case "dep" -> deps.add(parseDep(d.value));
                        case "jdk", "java" -> release = parseJdk(d.value, release);
                        case "repo" -> repos.add(URI.create(d.value.trim()));
                        case "repos" -> repos.addAll(parseRepos(d.value));
                        case "feature" -> features.add(d.value.trim());
                        case "javac-options", "javac_options", "compile_options" ->
                            javacOptions.addAll(splitArgs(d.value));
                        case "java-options", "java_options", "runtime_options" ->
                            javaOptions.addAll(splitArgs(d.value));
                        case "source", "sources" -> sources.addAll(splitArgs(d.value));
                        case "files" -> files.addAll(splitArgs(d.value));
                        case "main" -> {
                            String v = d.value.trim();
                            if (!v.isEmpty()) main = v;
                        }
                        case "preview" -> {
                            // JBang //PREVIEW: preview features at compile AND run time.
                            // Folded into the option lists so nothing downstream changes.
                            javacOptions.add("--enable-preview");
                            javaOptions.add("--enable-preview");
                        }
                        case "gav" -> gav = blankToNull(d.value);
                        case "description" -> description = blankToNull(d.value);
                        case "deps" -> {
                            for (String coord : splitArgs(d.value)) {
                                deps.add(parseDep(coord));
                            }
                        }
                        case "kotlin" -> {
                            String v = d.value.trim();
                            if (!v.isEmpty()) kotlinVersion = v;
                        }
                        default -> {
                            /* unknown directive (//MODULE, //MANIFEST, //JAVAAGENT,
                             * //NATIVE_OPTIONS, //CDS, …) — ignored for forward-compat;
                             * such scripts still run, those features just aren't honored. */
                        }
                    }
                }
                continue;
            }
            // Non-comment, non-empty: body has started.
            break;
        }

        return new ScriptHeader(
                deps,
                release,
                repos,
                features,
                javacOptions,
                javaOptions,
                sources,
                files,
                main,
                gav,
                description,
                kotlinVersion);
    }

    /**
     * Parse a Kotlin script/source: {@code //} directives plus {@code @file:DependsOn}/{@code
     * @file:Repository} annotations anywhere in the source.
     */
    public static ScriptHeader parseKotlin(String script) {
        ScriptHeader base = parse(script);
        List<Dependency> deps = new ArrayList<>(base.deps());
        List<URI> repos = new ArrayList<>(base.repos());
        for (String rawLine : script.split("\\R", -1)) {
            var m = FILE_ANNOTATION.matcher(rawLine.strip());
            if (!m.matches()) continue;
            String kind = m.group(1);
            for (String arg : quotedStrings(m.group(2))) {
                if ("DependsOn".equals(kind)) deps.add(parseDep(arg));
                else repos.add(URI.create(arg.trim()));
            }
        }
        return new ScriptHeader(
                deps,
                base.release(),
                repos,
                base.features(),
                base.javacOptions(),
                base.javaOptions(),
                base.sources(),
                base.files(),
                base.main(),
                base.gav(),
                base.description(),
                base.kotlinVersion());
    }

    private static @Nullable ParsedDirective parseDirective(String line) {
        // jk-style: `//jk <name> <value>`
        var m = JK_DIRECTIVE.matcher(line);
        if (m.matches()) {
            return new ParsedDirective(m.group(1), m.group(2));
        }
        // JBang-style: `//DEPS ...`, `//JAVA ...`, ...
        if (line.startsWith("//") && line.length() > 2 && Character.isUpperCase(line.charAt(2))) {
            int spaceIdx = -1;
            for (int i = 2; i < line.length(); i++) {
                if (Character.isWhitespace(line.charAt(i))) {
                    spaceIdx = i;
                    break;
                }
            }
            String name = (spaceIdx < 0 ? line.substring(2) : line.substring(2, spaceIdx)).toLowerCase(Locale.ROOT);
            String value = spaceIdx < 0 ? "" : line.substring(spaceIdx + 1).trim();
            return new ParsedDirective(name, value);
        }
        return null;
    }

    /** Parse one script-dep coordinate — {@code g:a:v} pinned or {@code g:a@sel} floating. */
    public static Dependency parseDependency(String coord) {
        return parseDep(coord);
    }

    private static Dependency parseDep(String coord) {
        String spec = coord.trim();
        int firstColon = spec.indexOf(':');
        if (firstColon < 0) {
            throw new IllegalArgumentException("script dependency must be `group:artifact:version` or "
                    + "`group:artifact@version`, got: "
                    + coord);
        }
        int nextColon = spec.indexOf(':', firstColon + 1);
        int atSign = spec.indexOf('@', firstColon + 1);

        String module;
        String versionPart;
        boolean floating;

        if (nextColon < 0 && atSign < 0) {
            throw new IllegalArgumentException("script dependency must include a version, got: " + coord);
        } else if (atSign >= 0 && (nextColon < 0 || atSign < nextColon)) {
            module = spec.substring(0, atSign);
            versionPart = spec.substring(atSign + 1);
            floating = true;
        } else {
            module = spec.substring(0, nextColon);
            versionPart = spec.substring(nextColon + 1);
            floating = false;
        }

        if (versionPart.isBlank()) {
            throw new IllegalArgumentException("script dependency has empty version: " + coord);
        }

        VersionSelector selector;
        if (floating) {
            selector = VersionSelector.parseFloating(versionPart);
        } else {
            String trimmed = versionPart.trim();
            if (trimmed.startsWith("^")
                    || trimmed.startsWith("~")
                    || trimmed.startsWith(">")
                    || trimmed.startsWith("<")
                    || trimmed.contains(",")
                    || "latest".equalsIgnoreCase(trimmed)) {
                throw new IllegalArgumentException(coord
                        + " — the `:` form is for pinned versions only. "
                        + "Use `"
                        + module
                        + "@"
                        + versionPart
                        + "` for a floating constraint.");
            }
            selector = VersionSelector.parse(versionPart);
        }
        // pinned is derived from the selector; floating vs exact is encoded there.
        return new Dependency(module, selector);
    }

    /**
     * Replace {@code @file:DependsOn}/{@code @file:Repository} lines with same-length comments so
     * plain {@code kotlinc} never sees them; {@code null} when none. Preserves line count for
     * diagnostics.
     */
    public static @Nullable String neutralizeKotlinAnnotations(String script) {
        String[] lines = script.split("\\R", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            if (FILE_ANNOTATION.matcher(lines[i].strip()).matches()) {
                lines[i] = "// jk: resolved " + lines[i].strip();
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : null;
    }

    /** {@code //REPOS name=url,name2=url2} (JBang) — names are cosmetic; bare URLs accepted too. */
    private static List<URI> parseRepos(String value) {
        List<URI> out = new ArrayList<>();
        for (String part : value.split("[,\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            out.add(URI.create(eq >= 0 ? p.substring(eq + 1) : p));
        }
        return out;
    }

    private static List<String> quotedStrings(String args) {
        List<String> out = new ArrayList<>();
        var m = QUOTED.matcher(args);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static @Nullable String blankToNull(String s) {
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static @Nullable Integer parseJdk(String raw, @Nullable Integer existing) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return existing;
        // JBang's "17+" means "17 or newer is acceptable" — compile for 17.
        if (trimmed.endsWith("+")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        // Strip a trailing "-tem" / "-graalce" etc. vendor suffix and any
        // leading "1." vestige from Java 8 days.
        String version = trimmed;
        int dash = version.indexOf('-');
        if (dash > 0) version = version.substring(0, dash);
        if (version.startsWith("1.")) version = version.substring(2);
        // Take just the major component.
        int dot = version.indexOf('.');
        if (dot > 0) version = version.substring(0, dot);
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            // Ignore — silently leave existing setting in place.
            return existing;
        }
    }

    private static List<String> splitArgs(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return List.of();
        return List.of(WHITESPACE.split(trimmed));
    }

    private record ParsedDirective(String name, String value) {}
}
