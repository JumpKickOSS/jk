// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.glob;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Glob matching for manifest-declared file sets — one implementation so every {@code jk.toml} key
 * that takes paths means the same thing by them.
 *
 * <p>Patterns use the JDK's {@code glob:} syntax ({@code *}, {@code **}, {@code ?}, {@code {a,b}},
 * {@code [abc]}), where {@code *} stops at a directory boundary and {@code **} crosses it. Patterns
 * are <b>module-relative</b> and may reach upward with {@code ../}; the result is clamped to
 * {@code clampRoot} so a manifest can't read outside the project.
 *
 * <p>Three behaviours exist because a naive {@code **} is overwhelmingly wrong in a real tree. In
 * this repo {@code **}{@code /jk*.toml} matches 269 files, of which 4 are wanted — the rest are
 * build outputs and stale worktree copies. So:
 *
 * <ul>
 *   <li><b>Build outputs and tooling directories are skipped</b> by default ({@link #DEFAULT_EXCLUDES}).
 *   <li><b>Ignored files are skipped</b> — {@code .gitignore} rules are honoured the way git applies
 *       them, nearest file first, so anything not in version control is not a build input.
 *   <li><b>Matches are sorted.</b> Filesystem iteration order is not stable, and an unsorted match
 *       set makes jar bytes and action keys differ between machines.
 * </ul>
 *
 * <p>A pattern that matches nothing is an error unless explicitly {@code optional}: a typo'd path
 * that silently contributes no files is the same failure mode as a silently skipped code generator.
 */
public final class GlobSet {

    /** Directories never descended into: build outputs, VCS metadata, IDE and tool state. */
    public static final Set<String> DEFAULT_EXCLUDES =
            Set.of("target", "build", "out", ".git", ".jk", ".gradle", ".idea", "node_modules");

    private static final Set<Character> WILDCARDS = Set.of('*', '?', '[', '{');

    private GlobSet() {}

    /**
     * One matched file: the file itself, the {@code base} the pattern's literal prefix resolved to,
     * and the captures of the pattern's wildcard segments (for {@code {1}}-style renaming).
     */
    public record Match(Path file, Path base, List<String> captures) {
        public Match {
            captures = List.copyOf(captures);
        }

        /** The file's path relative to {@code base} — what preserves tree shape at the destination. */
        public String relative() {
            return base.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        }
    }

    /** Thrown when a required pattern matches nothing, or escapes {@code clampRoot}. */
    public static class GlobException extends RuntimeException {
        public GlobException(String message) {
            super(message);
        }
    }

    /**
     * Files matching {@code pattern} under {@code moduleDir}, sorted, minus {@code exclude} patterns,
     * default-excluded directories, and anything git ignores.
     *
     * @param moduleDir the module the pattern is relative to
     * @param clampRoot the outermost directory a pattern may reach (workspace root, or the module
     *     itself for a standalone project)
     * @param pattern module-relative glob; may contain {@code ../} and wildcards
     * @param exclude additional globs, matched against the same base
     * @param optional when true, matching nothing yields an empty list instead of throwing
     */
    public static List<Match> resolve(
            Path moduleDir, Path clampRoot, String pattern, List<String> exclude, boolean optional) {
        Path module = moduleDir.toAbsolutePath().normalize();
        Path clamp = clampRoot.toAbsolutePath().normalize();

        String normalized = pattern.replace('\\', '/');
        Path base = literalPrefix(module, normalized);
        if (!base.startsWith(clamp)) {
            throw new GlobException(
                    "path `" + pattern + "` resolves outside the project root (" + clamp + ") — refusing to read it");
        }

        // A pattern with no wildcard is a plain path: a file, or a whole directory.
        if (!hasWildcard(normalized)) {
            List<Match> literal = new ArrayList<>();
            if (Files.isRegularFile(base)) {
                literal.add(new Match(base, base.getParent(), List.of()));
            } else if (Files.isDirectory(base)) {
                literal.addAll(walk(base, base, p -> true, exclude, clamp));
            }
            return require(literal, pattern, optional);
        }

        PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + normalized);
        Path relBase = module; // patterns are written relative to the module
        List<Match> matches =
                walk(base, relBase, p -> matcher.matches(relativeUnderModule(relBase, p)), exclude, clamp);
        List<Match> captured = new ArrayList<>(matches.size());
        for (Match m : matches) {
            captured.add(new Match(m.file(), base, captures(normalized, relativeUnderModule(relBase, m.file()))));
        }
        return require(captured, pattern, optional);
    }

    private static List<Match> require(List<Match> matches, String pattern, boolean optional) {
        if (matches.isEmpty() && !optional) {
            throw new GlobException(
                    "path `" + pattern + "` matched no files — fix the pattern, or mark it `optional = true`");
        }
        return matches;
    }

    /**
     * Walk {@code base}, collecting files that satisfy {@code accept}, skipping default-excluded
     * directories, {@code exclude} globs, and git-ignored entries. Results are sorted.
     */
    private static List<Match> walk(
            Path base, Path relBase, java.util.function.Predicate<Path> accept, List<String> exclude, Path clamp) {
        List<PathMatcher> excludes = new ArrayList<>();
        for (String e : exclude) {
            excludes.add(base.getFileSystem().getPathMatcher("glob:" + e.replace('\\', '/')));
        }
        List<Path> found = new ArrayList<>();
        if (!Files.exists(base)) return List.of();
        Deque<IgnoreRules> ignoreStack = new ArrayDeque<>();
        // Seed with .gitignore files from the clamp root down to base, so rules declared above the
        // pattern's base still apply — git resolves them the same way.
        for (Path dir : chain(clamp, base)) {
            IgnoreRules rules = IgnoreRules.read(dir);
            if (rules != null) ignoreStack.addLast(rules);
        }
        try {
            Files.walkFileTree(base, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(base)) {
                        String name = dir.getFileName().toString();
                        if (DEFAULT_EXCLUDES.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                        if (ignored(ignoreStack, dir, true)) return FileVisitResult.SKIP_SUBTREE;
                    }
                    IgnoreRules rules = IgnoreRules.read(dir);
                    ignoreStack.addLast(rules == null ? IgnoreRules.EMPTY : rules);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (ignored(ignoreStack, file, false)) return FileVisitResult.CONTINUE;
                    Path rel = relativeUnderModule(relBase, file);
                    for (PathMatcher ex : excludes) {
                        if (ex.matches(rel)) return FileVisitResult.CONTINUE;
                    }
                    if (accept.test(file)) found.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // unreadable entry: not a build input
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    ignoreStack.pollLast();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new GlobException("cannot read `" + base + "`: " + e.getMessage());
        }
        found.sort(Comparator.comparing(Path::toString));
        List<Match> out = new ArrayList<>(found.size());
        for (Path f : found) out.add(new Match(f, base, List.of()));
        return out;
    }

    /** The directories from {@code from} (inclusive) down to {@code to} (exclusive), outermost first. */
    private static List<Path> chain(Path from, Path to) {
        List<Path> dirs = new ArrayList<>();
        Path cur = to;
        while (cur != null && cur.startsWith(from) && !cur.equals(from)) {
            dirs.add(cur.getParent());
            cur = cur.getParent();
        }
        dirs.add(from);
        java.util.Collections.reverse(dirs);
        return dirs.stream().distinct().toList();
    }

    private static boolean ignored(Deque<IgnoreRules> stack, Path path, boolean isDir) {
        // Nearest .gitignore wins, and within one file the last matching rule wins — git's order.
        Boolean verdict = null;
        for (IgnoreRules rules : stack) {
            Boolean r = rules.verdict(path, isDir);
            if (r != null) verdict = r;
        }
        return verdict != null && verdict;
    }

    /** The pattern's leading wildcard-free directory portion, resolved against {@code moduleDir}. */
    static Path literalPrefix(Path moduleDir, String pattern) {
        String[] parts = pattern.split("/");
        Path base = moduleDir;
        for (String part : parts) {
            if (hasWildcard(part)) break;
            base = base.resolve(part);
        }
        // A trailing literal segment is the file itself, not a directory to walk.
        if (!hasWildcard(pattern)) return base.normalize();
        return base.normalize();
    }

    private static boolean hasWildcard(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (WILDCARDS.contains(s.charAt(i))) return true;
        }
        return false;
    }

    private static Path relativeUnderModule(Path moduleDir, Path file) {
        // Patterns are written module-relative (possibly with ../), so match against the same shape.
        Path rel = moduleDir.relativize(file);
        return Path.of(rel.toString().replace(java.io.File.separatorChar, '/'));
    }

    /**
     * The text each wildcard segment of {@code pattern} consumed, for {@code {1}}-style renaming.
     * Only whole-segment wildcards are captured — the common {@code plugins/&#42;/jk-plugin.toml}
     * shape — which keeps the rule easy to state.
     */
    static List<String> captures(String pattern, Path relativePath) {
        String[] pat = pattern.split("/");
        String[] act =
                relativePath.toString().replace(java.io.File.separatorChar, '/').split("/");
        List<String> caps = new ArrayList<>();
        int ai = 0;
        for (String p : pat) {
            if (ai >= act.length) break;
            if (p.equals("**")) {
                // Greedy to the point where the remaining literal segments still fit.
                int remaining = pat.length - java.util.Arrays.asList(pat).indexOf(p) - 1;
                int take = Math.max(0, act.length - ai - remaining);
                caps.add(String.join("/", java.util.Arrays.copyOfRange(act, ai, ai + take)));
                ai += take;
            } else if (hasWildcard(p)) {
                caps.add(act[ai]);
                ai++;
            } else {
                ai++;
            }
        }
        return caps;
    }

    /** Substitute {@code {1}}, {@code {2}} … in {@code template} with a match's captures. */
    public static String applyCaptures(String template, List<String> captures) {
        String out = template;
        for (int i = 0; i < captures.size(); i++) {
            out = out.replace("{" + (i + 1) + "}", captures.get(i));
        }
        return out;
    }
}
