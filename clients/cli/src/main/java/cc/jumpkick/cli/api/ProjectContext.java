// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolved project dir + manifest/{@code jk-lock.toml} for leaf commands that require a project.
 * A {@code pom.xml} with no {@code jk.toml} counts: its manifest is the engine-rendered shadow
 * ({@link ManifestPaths#manifestIn}). On neither, {@link #require} prints the standard error and
 * returns empty ({@link Exit#CONFIG}). Workspace-ascent commands resolve their own root.
 */
public record ProjectContext(Path dir, Path buildFile, Path lockFile) {

    /**
     * Resolve the project at {@code dir}, requiring a {@code jk.toml} or a {@code pom.xml}. On
     * absence, prints {@code jk <command>: no jk.toml in <dir>} to stderr and returns empty (the
     * caller returns {@link Exit#CONFIG}); so does a {@code pom.xml} the engine declines to build
     * from, printed as the engine's one line ({@link ProjectInfos#refusal}).
     */
    public static Optional<ProjectContext> require(Path dir, String command) {
        if (!ManifestPaths.describesProject(dir)) {
            CommandWedge.printFail(command, "no jk.toml in " + PathDisplay.styledRaw(dir));
            return Optional.empty();
        }
        // A directory built from its pom.xml has its manifest rendered by the engine, which declines
        // one Maven would not build here (a module only an inactive profile lists): one line, exit 2.
        String refusal = ManifestPaths.isShadowed(dir) ? ProjectInfos.refusal(dir) : null;
        if (refusal != null) {
            CommandWedge.printFail(command, refusal);
            return Optional.empty();
        }
        return Optional.of(new ProjectContext(dir, ManifestPaths.manifestIn(dir), LockPaths.lockFile(dir)));
    }

    /** True when the project has been locked ({@code jk-lock.toml} exists). */
    public boolean isLocked() {
        return Files.exists(lockFile);
    }
}
