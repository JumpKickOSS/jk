// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

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
                    throw new JkBuildParseException("project.group/version inherit from the workspace"
                            + " (no enclosing workspace lists this module — set concrete"
                            + " group and version, or place this project under a workspace)");
                }
                if (module.project().inheritsFromWorkspace()) {
                    return module.withProject(module.project().droppingOptionalInherits());
                }
                return module;
            }
            // Failure scope matters: a member is only entitled to fail for problems in the
            // pieces it actually needs. Root errors hit members with pending [project]
            // inherits; sibling errors hit members with workspace:<name> deps. A fully
            // concrete member mid-refactor keeps parsing either way — the broken file's
            // error belongs to whoever builds it.
            JkBuild root;
            try {
                // parseLocal for the root — parse() would re-enter applyWorkspace.
                root = JkBuildParser.parseLocal(rootDir.get().resolve("jk.toml"));
            } catch (JkBuildParseException e) {
                if (module.project().inheritsFromWorkspace()
                        || module.project().requiresWorkspaceRoot()
                        || hasWorkspaceDeps(module)) {
                    throw e;
                }
                return module;
            }
            if (!root.isWorkspaceRoot()) return module;
            // loadModules already rewrites project.*.workspace / omitted-field inherits.
            module = WorkspaceLoader.inheritFromRoot(module, root);
            // Conditioned plugin contributions (kotlin-project, …) were folded pre-inheritance;
            // re-evaluate them now that the project is concrete (idempotent).
            module = JkBuildParser.reapplyPlatformContributions(moduleDir, module);
            Collection<JkBuild> siblings;
            try {
                siblings = WorkspaceLoader.loadModules(rootDir.get(), root).values();
            } catch (JkBuildParseException e) {
                // Identity is already resolved from the root above; only workspace:<name>
                // placeholders still need the sibling list.
                if (hasWorkspaceDeps(module)) throw e;
                return module;
            }
            // resolveSiblingCoordinates, NOT applyToModule: the latter is the lock-orchestration
            // fold, which strips sibling edges entirely. A published POM has to keep them.
            return WorkspaceMerge.resolveSiblingCoordinates(root, module, siblings);
        } catch (JkBuildParseException e) {
            throw e;
        } catch (Exception e) {
            // Best-effort: a standalone project has no workspace to merge, and a malformed root is
            // surfaced by the build itself rather than here.
            return module;
        }
    }

    /**
     * The enclosing workspace's unit coordinates ({@code group:artifact} for the root and every
     * member), or an empty set when {@code moduleDir} is standalone or the workspace cannot be
     * read. Publish paths use this to recognize sibling edges after
     * {@link WorkspaceMerge#resolveSiblingCoordinates} has rewritten them to real coordinates —
     * e.g. to omit tests-kind sibling edges whose test-jar jk never produces (JK-1643).
     */
    public static Set<String> siblingCoordinates(Path moduleDir) {
        try {
            var rootDir = WorkspaceLocator.findRoot(moduleDir);
            if (rootDir.isEmpty()) return Set.of();
            JkBuild root = JkBuildParser.parseLocal(rootDir.get().resolve("jk.toml"));
            if (!root.isWorkspaceRoot()) return Set.of();
            Set<String> out = new LinkedHashSet<>();
            out.add(root.project().group() + ":" + root.project().name());
            for (JkBuild m : WorkspaceLoader.loadModules(rootDir.get(), root).values()) {
                out.add(m.project().group() + ":" + m.project().name());
            }
            return out;
        } catch (Exception e) {
            return Set.of(); // best-effort, same policy as applyWorkspace
        }
    }

    /** True when any declared dependency is a {@code workspace:<name>} sibling placeholder. */
    private static boolean hasWorkspaceDeps(JkBuild module) {
        for (cc.jumpkick.model.Scope scope : cc.jumpkick.model.Scope.values()) {
            for (var dep : module.dependencies().of(scope)) {
                if (dep.isWorkspace()) return true;
            }
        }
        return false;
    }
}
