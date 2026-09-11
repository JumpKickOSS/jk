// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The file that tells the pre-commit hook the next commit may carry a changed
 * {@code jk-guards-baseline.toml}. Two writers leave it, and only they: {@code jk guard freeze},
 * which grows the baseline with a reason, and the engine when it tightens the baseline because
 * sites disappeared. A hand edit leaves no marker and the hook refuses it.
 */
public final class GuardBaselineMarker {

    /** File name under the common git directory. The hook script names it by this string. */
    public static final String NAME = "jk-guard-freeze";

    private GuardBaselineMarker() {}

    /** Leave the marker for the repository containing {@code repoDir}; silent outside a repository. */
    public static void leave(Path repoDir, String writer) {
        try {
            Path gitDir = gitCommonDir(repoDir);
            if (gitDir == null) return;
            Files.writeString(gitDir.resolve(NAME), writer + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the marker is a courtesy to the hook; the baseline write it announces already happened
        }
    }

    /**
     * The common git directory of the repository containing {@code dir}: {@code .git} as a
     * directory, or the {@code gitdir:} pointer of a worktree or submodule followed through {@code
     * commondir}, so hooks and markers land where every worktree's git reads them. Null outside a
     * repository.
     */
    public static @Nullable Path gitCommonDir(Path dir) throws IOException {
        Path d = dir.toAbsolutePath().normalize();
        while (d != null) {
            Path dotGit = d.resolve(".git");
            if (Files.isDirectory(dotGit)) return dotGit;
            if (Files.isRegularFile(dotGit)) {
                String pointer =
                        Files.readString(dotGit, StandardCharsets.UTF_8).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                Path gitDir =
                        d.resolve(pointer.substring("gitdir:".length()).strip()).normalize();
                Path common = gitDir.resolve("commondir");
                if (Files.isRegularFile(common)) {
                    return gitDir.resolve(Files.readString(common, StandardCharsets.UTF_8)
                                    .strip())
                            .normalize();
                }
                return gitDir;
            }
            d = d.getParent();
        }
        return null;
    }
}
