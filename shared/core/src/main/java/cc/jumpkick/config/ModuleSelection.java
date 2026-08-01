// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolve {@code --modules}/{@code -m} selectors to absolute module directories. Shared by {@code
 * jk build}, {@code jk test}, {@code jk explain}, and {@code jk selective}.
 *
 * <p>Syntax (comma-separated tokens):
 *
 * <ul>
 *   <li>workspace path: {@code api}, {@code server/engine}, {@code plugins/kotlin-compiler}
 *   <li>project {@code [project] name}: {@code jk-engine}
 *   <li>Gradle-style path/name (leading {@code :}, {@code :} as path seps): {@code :engine}, {@code
 *       :jk-engine}, {@code :server:engine}
 *   <li>brace expansion: {@code {api,worker}}
 *   <li>glob ({@code *} / {@code ?}) against path aliases: {@code libs/*}, {@code :plugins:*}
 * </ul>
 *
 * Matching is case-insensitive against each module's path, last path segment, and {@code
 * [project] name}. Single-project trees match {@code .}, the project name, or the directory name.
 */
public final class ModuleSelection {

    private ModuleSelection() {}

    public record Result(Set<Path> moduleDirs, String errorMessage) {
        public boolean ok() {
            return errorMessage == null;
        }

        public static Result ok(Set<Path> dirs) {
            return new Result(Set.copyOf(dirs), null);
        }

        public static Result fail(String msg) {
            return new Result(Set.of(), msg);
        }
    }

    /**
     * One workspace (or single-project) unit: the relative path used to resolve the directory, plus
     * every alias {@code --modules} may use to select it.
     */
    record Candidate(String relPath, Set<String> aliases) {
        Candidate {
            aliases = Set.copyOf(aliases);
        }
    }

    /**
     * Select modules under {@code entryDir} given optional {@code modulesSpec} and optional
     * {@code affectedSince}. When both are set, the result is their <strong>intersection</strong>.
     * When neither is set, returns {@code null} (caller should not filter).
     */
    public static Result resolveOptional(Path entryDir, JkBuild entryBuild, String modulesSpec, String affectedSince) {
        Result modules = null;
        if (modulesSpec != null && !modulesSpec.isBlank()) {
            modules = resolve(entryDir, entryBuild, modulesSpec);
            if (!modules.ok()) return modules;
        }
        AffectedSelection.Result affected = null;
        if (affectedSince != null && !affectedSince.isBlank()) {
            affected = AffectedSelection.resolve(entryDir, entryBuild, affectedSince);
            if (!affected.ok()) return Result.fail(affected.errorMessage());
        }
        if (modules == null && affected == null) return null;
        if (modules == null) return Result.ok(affected.moduleDirs());
        if (affected == null) return modules;
        Set<Path> inter = new LinkedHashSet<>();
        for (Path p : modules.moduleDirs()) {
            if (affected.moduleDirs().contains(p)) inter.add(p);
        }
        return Result.ok(inter);
    }

    /** Resolve {@code --modules} only. Empty match → fail with a clear message. */
    public static Result resolve(Path entryDir, JkBuild entryBuild, String modulesSpec) {
        Path root = entryDir.toAbsolutePath().normalize();
        List<Candidate> candidates = candidates(root, entryBuild);
        List<String> tokens = expandSpec(modulesSpec);
        if (tokens.isEmpty()) {
            return Result.fail("--modules is empty");
        }
        boolean workspace = entryBuild.isWorkspaceRoot();
        Set<String> matched = new LinkedHashSet<>();
        for (String token : tokens) {
            String t = normalizeToken(token);
            if (t.isEmpty()) continue;
            boolean any = false;
            if (isGlob(t)) {
                Pattern pat = globToPattern(t);
                for (Candidate c : candidates) {
                    if (matchesGlob(c, pat)) {
                        matched.add(c.relPath());
                        any = true;
                    }
                }
            } else {
                for (Candidate c : candidates) {
                    if (matchesLiteral(c, t)) {
                        matched.add(c.relPath());
                        any = true;
                    }
                }
            }
            if (!any) {
                return Result.fail("no module matched `" + token + "` (known: " + knownLabels(candidates) + ")");
            }
        }
        if (matched.isEmpty()) {
            return Result.fail("no modules matched --modules=" + modulesSpec);
        }
        Set<Path> dirs = new LinkedHashSet<>();
        if (!workspace) {
            // Single project: any matching alias maps to the project root, not root/name.
            dirs.add(root);
            return Result.ok(dirs);
        }
        for (String rel : matched) {
            dirs.add(root.resolve(rel).normalize());
        }
        return Result.ok(dirs);
    }

    /** Relative paths only (tests / callers that only need the path form). */
    static List<String> candidateRelPaths(Path root, JkBuild entryBuild) {
        List<String> out = new ArrayList<>();
        for (Candidate c : candidates(root, entryBuild)) out.add(c.relPath());
        return out;
    }

    static List<Candidate> candidates(Path root, JkBuild entryBuild) {
        List<Candidate> out = new ArrayList<>();
        if (entryBuild.isWorkspaceRoot()) {
            for (String m : entryBuild.workspaceOpt().orElseThrow().modules()) {
                String rel = normalizeRel(m);
                out.add(candidateFor(root, rel, null));
            }
        } else {
            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            aliases.add(".");
            String name = entryBuild.project().name();
            if (name != null && !name.isBlank()) addNameAliases(aliases, name);
            Path fn = root.getFileName();
            if (fn != null) aliases.add(fn.toString());
            out.add(new Candidate(".", aliases));
        }
        return out;
    }

    /**
     * Build aliases for a workspace member: path, last segment, {@code [project] name}, and soft
     * forms without a leading {@code jk-} on the project name.
     */
    private static Candidate candidateFor(Path root, String rel, JkBuild preParsed) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(rel);
        String bare = bareName(rel);
        if (!bare.isBlank()) aliases.add(bare);
        JkBuild unit = preParsed;
        if (unit == null) {
            Path manifest = root.resolve(rel).resolve("jk.toml");
            if (Files.isRegularFile(manifest)) {
                try {
                    unit = JkBuildParser.parse(manifest);
                } catch (Exception ignored) {
                    unit = null;
                }
            }
        }
        if (unit != null) {
            String name = unit.project().name();
            if (name != null && !name.isBlank()) addNameAliases(aliases, name);
        }
        return new Candidate(rel, aliases);
    }

    private static void addNameAliases(Set<String> aliases, String name) {
        aliases.add(name);
        // Gradle short names often drop a monorepo prefix (jk-engine → engine).
        if (name.regionMatches(true, 0, "jk-", 0, 3) && name.length() > 3) {
            aliases.add(name.substring(3));
        }
    }

    private static boolean matchesLiteral(Candidate c, String token) {
        for (String a : c.aliases()) {
            if (a.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private static boolean matchesGlob(Candidate c, Pattern pat) {
        for (String a : c.aliases()) {
            if (pat.matcher(a).matches()) return true;
        }
        return false;
    }

    private static String knownLabels(List<Candidate> candidates) {
        List<String> labels = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            // Prefer project-ish name when distinct from the path bare segment.
            String label = c.relPath();
            for (String a : c.aliases()) {
                if (!a.equals(c.relPath()) && !a.equals(bareName(c.relPath())) && !a.equals(".")) {
                    label = c.relPath() + " (" + a + ")";
                    break;
                }
            }
            labels.add(label);
        }
        return String.join(", ", labels);
    }

    /** Expand braces and split on commas (outside braces). */
    static List<String> expandSpec(String spec) {
        List<String> parts = splitCommaOutsideBraces(spec.trim());
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            out.addAll(expandBraces(part.trim()));
        }
        return out;
    }

    private static List<String> splitCommaOutsideBraces(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static List<String> expandBraces(String s) {
        int open = s.indexOf('{');
        if (open < 0) return List.of(s);
        int close = s.indexOf('}', open + 1);
        if (close < 0) return List.of(s);
        String prefix = s.substring(0, open);
        String suffix = s.substring(close + 1);
        String body = s.substring(open + 1, close);
        List<String> out = new ArrayList<>();
        for (String alt : body.split(",", -1)) {
            out.addAll(expandBraces(prefix + alt.trim() + suffix));
        }
        return out;
    }

    private static boolean isGlob(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0;
    }

    private static Pattern globToPattern(String glob) {
        StringBuilder re = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                case '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\' ->
                    re.append('\\').append(c);
                default -> re.append(c);
            }
        }
        re.append('$');
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String normalizeRel(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Normalize a user token: slash form, strip a leading Gradle {@code :}, map remaining {@code :}
     * to {@code /} ({@code :server:engine} → {@code server/engine}).
     */
    static String normalizeToken(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith(":")) s = s.substring(1);
        s = s.replace(':', '/');
        return normalizeRel(s);
    }

    private static String bareName(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }
}
