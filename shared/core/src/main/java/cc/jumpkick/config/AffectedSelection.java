// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolve {@code --affected-since=&lt;git-ref&gt;} to module directories. Shared by {@code jk build},
 * {@code jk test}, and {@code jk explain}.
 */
public final class AffectedSelection {

    private AffectedSelection() {}

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
     * Map git changes since {@code ref} onto workspace modules (with reverse-dep closure), or the
     * single module dir for non-workspace projects.
     */
    public static Result resolve(Path entryDir, JkBuild entryBuild, String ref) {
        try {
            Path root = entryDir.toAbsolutePath().normalize();
            if (!entryBuild.isWorkspaceRoot()) {
                List<String> paths = gitDiffNameOnly(root, ref);
                if (paths == null) {
                    return Result.fail("git ref `" + ref + "` could not be resolved");
                }
                for (String p : paths) {
                    Path abs = root.resolve(p).normalize();
                    if (abs.startsWith(root)) return Result.ok(Set.of(root));
                }
                return Result.ok(Set.of());
            }
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entryBuild);
            Map<Path, Set<Path>> edges = AffectedModules.edgesFor(modules);
            List<String> paths = gitDiffNameOnly(root, ref);
            if (paths == null) {
                return Result.fail("git ref `" + ref + "` could not be resolved (not a git repo or bad ref)");
            }
            Set<Path> affected = AffectedModules.fromChangedPaths(root, modules.keySet(), edges, paths);
            return Result.ok(affected);
        } catch (Exception e) {
            return Result.fail(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /** {@code null} on git failure. Paths relative to the process cwd (repo root). */
    public static List<String> gitDiffNameOnly(Path root, String ref) {
        try {
            // --end-of-options: a ref like "--output=…" must be read as a revision, not a git
            // option (JK-1488). Matches the discipline in GitCliExtension.
            Process p = new ProcessBuilder("git", "diff", "--name-only", "--end-of-options", ref + "...HEAD")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
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
