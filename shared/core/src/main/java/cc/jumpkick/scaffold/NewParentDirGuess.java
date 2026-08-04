// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.scaffold;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Educated guess for the parent directory of a new project (web {@code New project} modal and any
 * other host that wants {@code jk new}-like defaults). Prefer a root that already holds the user's
 * code; never invent a directory that does not exist.
 *
 * <ol>
 *   <li>Longest common parent of past build dirs (from the engine journal / history), when it is a
 *       real directory under {@code $HOME} and not {@code $HOME} itself
 *   <li>Well-known IDE / source roots under home ({@code src}, {@code projects}, {@code
 *       IdeaProjects}, …)
 *   <li>Shallow scan (max depth 4) for non-hidden git repos under home — use the parent of the first
 *       hits' common root when possible
 *   <li>{@code $HOME} as last resort
 * </ol>
 */
public final class NewParentDirGuess {

    /** Ordered preference of folder names under {@code $HOME}. */
    private static final List<String> WELL_KNOWN = List.of(
            "src",
            "projects",
            "IdeaProjects",
            "Developer",
            "source",
            "workspace",
            "work",
            "code",
            "repos",
            "git",
            "dev");

    private static final int GIT_SCAN_MAX_DEPTH = 4;
    private static final int GIT_SCAN_MAX_VISITS = 400;

    private NewParentDirGuess() {}

    /** Real-home entry point. */
    public static Path guess() {
        Path home = Optional.ofNullable(System.getProperty("user.home"))
                .map(Path::of)
                .map(p -> p.toAbsolutePath().normalize())
                .orElse(null);
        return guess(home, List.of());
    }

    /**
     * @param home user home (may be null)
     * @param historyDirs absolute project dirs from past builds (may be empty)
     */
    public static Path guess(Path home, List<Path> historyDirs) {
        Path homeAbs = home == null ? null : home.toAbsolutePath().normalize();
        if (homeAbs != null && !Files.isDirectory(homeAbs)) homeAbs = null;

        Path fromHistory = commonBuildParent(historyDirs, homeAbs);
        if (fromHistory != null) return fromHistory;

        if (homeAbs != null) {
            for (String name : WELL_KNOWN) {
                Path p = homeAbs.resolve(name);
                if (Files.isDirectory(p)) return p;
            }
            Path fromGit = gitClusterParent(homeAbs);
            if (fromGit != null) return fromGit;
            return homeAbs;
        }
        // No home — last resort is cwd (caller may still refuse non-absolute paths).
        return Path.of(".").toAbsolutePath().normalize();
    }

    /**
     * Longest common ancestor of history dirs that is under home (or unrestricted when home is
     * null), exists, and is deeper than home when possible.
     */
    static Path commonBuildParent(List<Path> historyDirs, Path homeAbs) {
        if (historyDirs == null || historyDirs.isEmpty()) return null;
        List<Path> parents = new ArrayList<>();
        for (Path d : historyDirs) {
            if (d == null) continue;
            Path abs = d.toAbsolutePath().normalize();
            // Prefer the parent of the project (where new siblings land), not the project itself.
            Path parent = abs.getParent();
            if (parent == null) continue;
            if (homeAbs != null && !parent.startsWith(homeAbs)) continue;
            if (!Files.isDirectory(parent)) continue;
            parents.add(parent);
        }
        if (parents.isEmpty()) return null;
        Path common = parents.get(0);
        for (int i = 1; i < parents.size(); i++) {
            common = longestCommonPrefix(common, parents.get(i));
            if (common == null) return null;
        }
        if (!Files.isDirectory(common)) return null;
        // Don't suggest "/" or drive roots as a parent for new projects.
        if (common.getParent() == null) return null;
        // Prefer something under home that's more specific than home itself.
        if (homeAbs != null && common.equals(homeAbs)) {
            // All projects lived directly under home — still better than a random scan; fall through.
            return null;
        }
        return common;
    }

    static Path longestCommonPrefix(Path a, Path b) {
        if (a == null || b == null) return null;
        a = a.toAbsolutePath().normalize();
        b = b.toAbsolutePath().normalize();
        int n = Math.min(a.getNameCount(), b.getNameCount());
        int i = 0;
        // Root component (e.g. "/" or "C:\") must match.
        if (!a.getRoot().equals(b.getRoot())) return null;
        while (i < n && a.getName(i).equals(b.getName(i))) i++;
        if (i == 0) return a.getRoot();
        return a.getRoot().resolve(a.subpath(0, i));
    }

    /**
     * Walk non-hidden children under home (depth ≤ 4), collect parents of directories that contain
     * {@code .git}, and return the most common such parent (tie → shorter path, then name order).
     */
    static Path gitClusterParent(Path homeAbs) {
        List<Path> repoParents = new ArrayList<>();
        int[] visits = {0};
        scanGit(homeAbs, homeAbs, 0, repoParents, visits);
        if (repoParents.isEmpty()) return null;
        Map<Path, Integer> counts = new HashMap<>();
        for (Path p : repoParents) {
            counts.merge(p, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Path, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparingInt(e -> e.getKey().getNameCount())
                        .thenComparing(e -> e.getKey().toString().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getKey)
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(null);
    }

    private static void scanGit(Path home, Path dir, int depth, List<Path> out, int[] visits) {
        if (depth > GIT_SCAN_MAX_DEPTH || visits[0] >= GIT_SCAN_MAX_VISITS) return;
        visits[0]++;
        if (depth > 0 && Files.isDirectory(dir.resolve(".git"))) {
            Path parent = dir.getParent();
            if (parent != null && parent.startsWith(home) && !parent.equals(home)) {
                out.add(parent);
            } else if (parent != null && parent.equals(home)) {
                out.add(home); // repos directly under home — home is acceptable as cluster
            }
            return; // don't descend into a repo
        }
        if (depth == GIT_SCAN_MAX_DEPTH) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (visits[0] >= GIT_SCAN_MAX_VISITS) return;
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (!Files.isDirectory(child) || Files.isSymbolicLink(child)) continue;
                // Skip huge / system trees under home.
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("library")
                        || lower.equals("appdata")
                        || lower.equals("applications")
                        || lower.equals("node_modules")
                        || lower.equals("target")
                        || lower.equals("build")
                        || lower.equals(".cache")) {
                    continue;
                }
                scanGit(home, child, depth + 1, out, visits);
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
