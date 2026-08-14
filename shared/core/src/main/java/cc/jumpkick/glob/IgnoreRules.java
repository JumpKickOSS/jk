// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.glob;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * One directory's {@code .gitignore}, applied the way git applies it.
 *
 * <p>Enough of the format to be right about real files: comments and blank lines, {@code !}
 * negation, a trailing {@code /} meaning directory-only, a leading {@code /} anchoring to this
 * directory, and an embedded {@code /} making a pattern path-relative rather than name-relative.
 * Within one file the last matching rule wins; {@link GlobSet} layers files nearest-last.
 *
 * <p>This exists so a manifest glob never picks up files that aren't in version control. Without it
 * {@code **}{@code /jk*.toml} in the jk repo matches 269 files, 233 of them stale copies under an
 * ignored directory.
 */
final class IgnoreRules {

    static final IgnoreRules EMPTY = new IgnoreRules(Path.of(""), List.of());

    private record Rule(PathMatcher matcher, boolean negated, boolean dirOnly) {}

    private final Path dir;
    private final List<Rule> rules;

    private IgnoreRules(Path dir, List<Rule> rules) {
        this.dir = dir;
        this.rules = rules;
    }

    /** The {@code .gitignore} in {@code dir}, or null when there isn't one. */
    static IgnoreRules read(Path dir) {
        Path file = dir.resolve(".gitignore");
        if (!Files.isRegularFile(file)) return null;
        List<Rule> parsed = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                Rule rule = parse(dir, raw);
                if (rule != null) parsed.add(rule);
            }
        } catch (IOException e) {
            return null; // unreadable ignore file: ignore nothing rather than guess
        }
        return new IgnoreRules(dir, parsed);
    }

    private static Rule parse(Path dir, String raw) {
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#")) return null;
        boolean negated = line.startsWith("!");
        if (negated) line = line.substring(1);
        boolean dirOnly = line.endsWith("/");
        if (dirOnly) line = line.substring(0, line.length() - 1);
        if (line.isEmpty()) return null;

        boolean anchored = line.startsWith("/");
        if (anchored) line = line.substring(1);
        // A pattern containing a slash is matched against the path relative to the .gitignore's
        // directory; one without is matched against a file's name at any depth.
        boolean pathRelative = anchored || line.contains("/");
        String glob = pathRelative ? line : "**/" + line;
        try {
            return new Rule(dir.getFileSystem().getPathMatcher("glob:" + glob), negated, dirOnly);
        } catch (IllegalArgumentException e) {
            return null; // a pattern the JDK matcher rejects: skip rather than fail the build
        }
    }

    /**
     * {@code TRUE} ignored, {@code FALSE} explicitly un-ignored, {@code null} when no rule applies.
     * Callers layer several files and take the last non-null verdict.
     */
    Boolean verdict(Path path, boolean isDir) {
        if (rules.isEmpty()) return null;
        Path rel;
        try {
            rel = dir.relativize(path);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // Match "a/b" as well as "b" so a name-only pattern hits at any depth.
        Path normalized = Path.of(rel.toString().replace(File.separatorChar, '/'));
        Boolean verdict = null;
        for (Rule r : rules) {
            if (r.dirOnly() && !isDir) continue;
            if (r.matcher().matches(normalized)) verdict = !r.negated();
        }
        return verdict;
    }
}
