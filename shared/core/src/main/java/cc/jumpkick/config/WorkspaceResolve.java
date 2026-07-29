// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Path;

/**
 * Rewrite a workspace member's {@code workspace:<name>} dependency placeholders into the siblings'
 * real {@code group:artifact:version} coordinates.
 *
 * <p>{@link JkBuildParser} cannot do this on its own: resolving a sibling needs the full module
 * list, which only the workspace root knows. So a single-file parse leaves a synthetic
 * {@code workspace:<name>} module carrying a {@code Latest} selector, and every consumer that cares
 * about real coordinates has to run the merge first.
 *
 * <p>Most of the build already does. {@code jk publish} did not — it parsed the module's
 * {@code jk.toml} directly and emitted the placeholder verbatim, producing POMs with
 * {@code <groupId>workspace</groupId>} and {@code <version>LATEST</version>} that nothing can
 * resolve (JK-1255). This is the shared helper so that stays fixed.
 */
public final class WorkspaceResolve {

    private WorkspaceResolve() {}

    /**
     * {@code module} with its workspace placeholders resolved, or {@code module} unchanged when
     * {@code moduleDir} is not part of a workspace (or the workspace cannot be read).
     */
    public static JkBuild applyWorkspace(Path moduleDir, JkBuild module) {
        try {
            var rootDir = WorkspaceLocator.findRoot(moduleDir);
            if (rootDir.isEmpty()) return module;
            JkBuild root = JkBuildParser.parse(rootDir.get().resolve("jk.toml"));
            if (!root.isWorkspaceRoot()) return module;
            // resolveSiblingCoordinates, NOT applyToModule: the latter is the lock-orchestration
            // fold, which strips sibling edges entirely. A published POM has to keep them.
            return WorkspaceMerge.resolveSiblingCoordinates(
                    root, module, WorkspaceLoader.loadModules(rootDir.get(), root).values());
        } catch (Exception e) {
            // Best-effort: a standalone project has no workspace to merge, and a malformed root is
            // surfaced by the build itself rather than here.
            return module;
        }
    }
}
