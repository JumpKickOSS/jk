// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Working-tree path enumerator for {@code --affected}. ProcessBuilder only — the same style as
 * {@link AffectedSelection#gitDiffNameOnly}, not JGit.
 *
 * <p>Primary: unstaged + staged vs {@code HEAD}, plus untracked. When that set is empty (clean
 * tree after commit), union {@code HEAD~1...HEAD} so “edit, commit, jk test --affected” still
 * sees the last commit.
 */
public final class DirtyPaths {

    private DirtyPaths() {}

    /**
     * Relative paths (git spelling) of the WIP change set, or {@code null} when git cannot answer
     * (not a repo / no binary / non-zero). Empty list means git worked and nothing is dirty,
     * including a missing {@code HEAD~1} fallback.
     */
    public static @Nullable List<String> wip(Path root) {
        List<String> wt = workingTree(root);
        if (wt == null) return null;
        if (!wt.isEmpty()) return wt;
        List<String> last = gitDiffNameOnly(root, "HEAD~1...HEAD");
        return last == null ? List.of() : last;
    }

    /** Unstaged + staged vs HEAD, plus untracked. {@code null} on git failure. */
    static @Nullable List<String> workingTree(Path root) {
        List<String> diff = gitDiffNameOnly(root, "HEAD");
        if (diff == null) return null;
        List<String> untracked = gitUntracked(root);
        if (untracked == null) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>(diff);
        out.addAll(untracked);
        return withoutBuildOutput(root, out);
    }

    /**
     * {@code paths} without those inside a module's build output: a {@code target/} directory
     * beside a {@code jk.toml} or {@code pom.xml}. A build writes there whether or not the project
     * ignores it in git, and what it writes is not a change to the module.
     */
    static List<String> withoutBuildOutput(Path root, Collection<String> paths) {
        List<String> out = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (!inBuildOutput(root, p)) out.add(p);
        }
        return List.copyOf(out);
    }

    private static boolean inBuildOutput(Path root, String path) {
        Path dir = root;
        for (String segment : path.split("/")) {
            if (BuildLayout.TARGET.equals(segment) && ManifestPaths.describesProject(dir)) return true;
            dir = dir.resolve(segment);
        }
        return false;
    }

    /**
     * {@code git diff --name-only --relative --end-of-options <rev>}. Unlike {@link
     * AffectedSelection#gitDiffNameOnly}, {@code rev} is used as-is (callers pass {@code HEAD} or
     * {@code HEAD~1...HEAD}), not {@code ref + "...HEAD"}.
     *
     * <p>{@code --relative}: diff prints repo-root-relative paths by default while {@code
     * ls-files} prints cwd-relative ones — resolved against a workspace root nested inside a
     * larger repo, the two bases disagree and diff lines point at nonexistent files.
     * With it, both commands speak workspace-root-relative, and dirt outside the root (which no
     * module can own) drops out instead of mis-resolving.
     */
    static @Nullable List<String> gitDiffNameOnly(Path root, String rev) {
        List<String> lines = gitLines(root, "diff", "--name-only", "--relative", "--end-of-options", rev);
        return lines == null ? null : withoutBuildOutput(root, lines);
    }

    static @Nullable List<String> gitUntracked(Path root) {
        return gitLines(root, "ls-files", "--others", "--exclude-standard");
    }

    /**
     * {@code null} on failure; otherwise one trimmed non-blank line per path — from stdout only.
     * stderr is discarded, never parsed: a {@code warning:}/{@code hint:} line merged into the
     * output would otherwise be taken for a dirty path.
     */
    static @Nullable List<String> gitLines(Path root, String... gitArgs) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : gitArgs) cmd.add(a);
            Process p = new ProcessBuilder(cmd)
                    .directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            List<String> lines = new ArrayList<>();
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) lines.add(line.trim());
                }
            }
            if (p.waitFor() != 0) return null;
            return lines;
        } catch (Exception e) {
            return null;
        }
    }
}
