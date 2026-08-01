// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolved project dir + {@code jk.toml}/{@code jk-lock.toml} for leaf commands that require a
 * project. On a missing manifest, {@link #require} prints the standard error and returns empty
 * ({@link Exit#CONFIG}). Workspace-ascent commands resolve their own root.
 */
public record ProjectContext(Path dir, Path buildFile, Path lockFile) {

    /**
     * Resolve the project at {@code dir}, requiring {@code jk.toml}. On absence, prints {@code jk
     * <command>: no jk.toml in <dir>} to stderr and returns empty (the caller returns {@link
     * Exit#CONFIG}).
     */
    public static Optional<ProjectContext> require(Path dir, String command) {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            CliOutput.err(
                    cc.jumpkick.cli.tui.CommandWedge.fail(command, "no jk.toml in " + PathDisplay.styledRaw(dir)));
            return Optional.empty();
        }
        return Optional.of(new ProjectContext(dir, buildFile, cc.jumpkick.lock.LockPaths.lockFile(dir)));
    }

    /** True when the project has been locked ({@code jk-lock.toml} exists). */
    public boolean isLocked() {
        return Files.exists(lockFile);
    }
}
