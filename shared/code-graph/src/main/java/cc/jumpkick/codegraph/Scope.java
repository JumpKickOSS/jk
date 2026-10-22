// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.codegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which part of the workspace a read may see: path globs (from the workspace root, {@code ;} between
 * them, {@code !} to exclude), module directories, and a source set. A node is in scope when its file
 * is: matched by some include (or there are none), by no exclude, under one of the modules (or there
 * are none) and in the source set (or none was asked for). A node with no file (a dependency, an
 * external type) is in scope only when nothing narrows it.
 *
 * <p>Globs: {@code *} is any run within one path segment, {@code **} any number of segments, {@code
 * ?} one character, {@code {a,b}} either. A pattern without wildcards also matches everything under
 * it, so {@code server/engine} is the module's directory.
 */
public final class Scope {

    /** Everything. */
    public static final Scope ALL = new Scope(List.of(), List.of(), List.of(), null, Modules.NONE);

    private final List<Pattern> include;
    private final List<Pattern> exclude;
    private final List<String> modulePrefixes;
    private final Modules.@Nullable SourceSet set;
    private final Modules modules;

    private Scope(
            List<Pattern> include,
            List<Pattern> exclude,
            List<String> modulePrefixes,
            Modules.@Nullable SourceSet set,
            Modules modules) {
        this.include = include;
        this.exclude = exclude;
        this.modulePrefixes = modulePrefixes;
        this.set = set;
        this.modules = modules;
    }

    /**
     * The scope {@code globs} ({@code null} or blank for every path), {@code modulePrefixes} (module
     * directories ending in {@code /}, {@code ""} for the root) and {@code sourceSet} ({@code main},
     * {@code test}, or {@code null}) spell over a graph whose modules are {@code modules}.
     */
    public static Scope of(
            @Nullable String globs, List<String> modulePrefixes, @Nullable String sourceSet, Modules modules) {
        List<Pattern> include = new ArrayList<>();
        List<Pattern> exclude = new ArrayList<>();
        if (globs != null) {
            for (String raw : globs.split(";")) {
                String g = raw.strip();
                if (g.isEmpty()) continue;
                boolean not = g.startsWith("!");
                (not ? exclude : include).add(pattern(not ? g.substring(1).strip() : g));
            }
        }
        Modules.SourceSet set = sourceSet == null || sourceSet.isBlank()
                ? null
                : switch (sourceSet.strip().toLowerCase(Locale.ROOT)) {
                    case "main" -> Modules.SourceSet.MAIN;
                    case "test", "tests" -> Modules.SourceSet.TEST;
                    default ->
                        throw new IllegalArgumentException("source set must be main or test, got '" + sourceSet + "'");
                };
        if (include.isEmpty() && exclude.isEmpty() && modulePrefixes.isEmpty() && set == null) return ALL;
        return new Scope(include, exclude, List.copyOf(modulePrefixes), set, modules);
    }

    public boolean all() {
        return this == ALL;
    }

    /** Whether {@code n} is in scope. */
    public boolean contains(@Nullable GraphNode n) {
        if (n == null) return false;
        if (all()) return true;
        return containsPath(n.sourceFile());
    }

    /** Whether a file at {@code rel} (from the workspace root) is in scope. */
    public boolean containsPath(String rel) {
        if (all()) return true;
        if (rel.isEmpty()) return false;
        if (!include.isEmpty() && include.stream().noneMatch(p -> p.matcher(rel).matches())) return false;
        if (exclude.stream().anyMatch(p -> p.matcher(rel).matches())) return false;
        if (!modulePrefixes.isEmpty() && modulePrefixes.stream().noneMatch(m -> inModule(rel, m))) return false;
        return set == null || modules.sourceSet(rel) == set;
    }

    /** {@code rel} is under module {@code prefix} and not under a module nested in it. */
    private boolean inModule(String rel, String prefix) {
        if (!rel.startsWith(prefix)) return false;
        Modules.Module owner = modules.of(rel);
        return owner == null || owner.prefix().length() <= prefix.length();
    }

    /** {@code glob} as a regex over a whole repo-relative path. */
    static Pattern pattern(String glob) {
        String g = glob.replace('\\', '/');
        while (g.startsWith("./")) g = g.substring(2);
        while (g.startsWith("/")) g = g.substring(1);
        boolean literal = g.chars().noneMatch(c -> c == '*' || c == '?' || c == '{' || c == '[');
        if (literal && g.endsWith("/")) g = g.substring(0, g.length() - 1);
        StringBuilder re = new StringBuilder();
        int brace = 0;
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        boolean slash = i + 2 < g.length() && g.charAt(i + 2) == '/';
                        re.append(slash ? "(?:.*/)?" : ".*");
                        i += slash ? 2 : 1;
                    } else {
                        re.append("[^/]*");
                    }
                }
                case '?' -> re.append("[^/]");
                case '{' -> {
                    brace++;
                    re.append("(?:");
                }
                case '}' -> {
                    if (brace > 0) {
                        brace--;
                        re.append(')');
                    } else {
                        re.append("\\}");
                    }
                }
                case ',' -> re.append(brace > 0 ? "|" : ",");
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (literal) re.append("(?:/.*)?");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }
}
