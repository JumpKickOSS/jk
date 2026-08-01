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
 * resolve. This is the shared helper so that stays fixed.
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
            if (rootDir.isEmpty()) {
                // Standalone: optional-field auto-inherits (java/jdk/…) drop back to local defaults.
                // group/version inheritance still requires a workspace.
                if (module.project().requiresWorkspaceRoot()) {
                    throw new JkBuildParseException(
                            "project.group/version inherit from the workspace"
                                    + " (no enclosing workspace lists this module — set concrete"
                                    + " group and version, or place this project under a workspace)");
                }
                if (module.project().inheritsFromWorkspace()) {
                    return module.withProject(module.project().droppingOptionalInherits());
                }
                return module;
            }
            // parseLocal for the root — parse() would re-enter applyWorkspace.
            JkBuild root = JkBuildParser.parseLocal(rootDir.get().resolve("jk.toml"));
            if (!root.isWorkspaceRoot()) return module;
            // loadModules already rewrites project.*.workspace / omitted-field inherits.
            module = WorkspaceLoader.inheritFromRoot(module, root);
            // resolveSiblingCoordinates, NOT applyToModule: the latter is the lock-orchestration
            // fold, which strips sibling edges entirely. A published POM has to keep them.
            return WorkspaceMerge.resolveSiblingCoordinates(
                    root,
                    module,
                    WorkspaceLoader.loadModules(rootDir.get(), root).values());
        } catch (JkBuildParseException e) {
            throw e;
        } catch (Exception e) {
            // Best-effort: a standalone project has no workspace to merge, and a malformed root is
            // surfaced by the build itself rather than here.
            return module;
        }
    }
}
