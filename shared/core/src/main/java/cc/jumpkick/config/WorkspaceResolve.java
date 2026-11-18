// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.WorkspaceMerge;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
        Resolved resolved = resolveWorkspace(moduleDir, module);
        if (!SnapshotQualifier.active()) return resolved.module();
        JkBuild out = resolved.module();
        // The sibling set only qualifies pinned edges; reuse the list the merge loaded when it did.
        Set<String> siblings = resolved.siblings() != null
                ? resolved.siblings()
                : SnapshotQualifier.hasPinnedEdge(out) ? siblingCoordinates(moduleDir) : Set.of();
        return SnapshotQualifier.qualify(out, siblings);
    }

    /** A member after the merge, and the workspace's unit coordinates when the merge loaded them. */
    private record Resolved(JkBuild module, @Nullable Set<String> siblings) {
        Resolved(JkBuild module) {
            this(module, null);
        }
    }

    private static Resolved resolveWorkspace(Path moduleDir, JkBuild module) {
        try {
            var rootDir = WorkspaceLocator.findRoot(moduleDir);
            if (rootDir.isEmpty()) {
                // Standalone: optional-field auto-inherits (java/jdk/…) drop back to local defaults.
                // group/version inheritance still requires a workspace.
                if (module.project().requiresWorkspaceRoot()) {
                    throw new JkBuildParseException("group and version use workspace = true, but no workspace"
                            + " lists this module; set them directly");
                }
                if (module.project().inheritsFromWorkspace()) {
                    return new Resolved(module.withProject(module.project().droppingOptionalInherits()), Set.of());
                }
                return new Resolved(module, Set.of());
            }
            // Failure scope matters: a member is only entitled to fail for problems in the
            // pieces it actually needs. Root errors hit members with pending project
            // inherits; sibling errors hit members with workspace:<name> deps. A fully
            // concrete member mid-refactor keeps parsing either way — the broken file's
            // error belongs to whoever builds it.
            JkBuild root;
            try {
                // parseLocal for the root — parse() would re-enter applyWorkspace.
                root = JkBuildParser.parseLocal(ManifestPaths.manifestIn(rootDir.get()));
            } catch (UserConfigParseException unparsed) {
                throw unparsed; // config.toml's own error, with its position — never this site's fallback
            } catch (JkBuildParseException e) {
                if (module.project().inheritsFromWorkspace()
                        || module.project().requiresWorkspaceRoot()
                        || WorkspaceMerge.hasWorkspaceDeps(module)) {
                    throw e;
                }
                return new Resolved(module);
            }
            if (!root.isWorkspaceRoot()) return new Resolved(module, Set.of());
            // loadModules already rewrites project.*.workspace / omitted-field inherits.
            module = WorkspaceLoader.inheritFromRoot(module, root);
            module = WorkspaceLoader.inheritProfiles(module, root);
            module = WorkspaceLoader.inheritSuites(module, root);
            module = WorkspaceLoader.inheritPublish(module, root);
            module = WorkspaceLoader.inheritTestEnvPolicy(module, root);
            module = WorkspaceLoader.inheritImage(module, root, moduleDir);
            // Conditioned plugin contributions (kotlin-project, …) were folded pre-inheritance;
            // re-evaluate them now that the project is concrete (idempotent).
            module = JkBuildParser.reapplyPlatformContributions(moduleDir, module);
            // Only workspace:<name> placeholders need the sibling list, and only a member naming one
            // is entitled to fail for a sibling's error; a member naming none is done.
            if (!WorkspaceMerge.hasWorkspaceDeps(module)) return new Resolved(module);
            Collection<JkBuild> siblings =
                    WorkspaceLoader.loadModules(rootDir.get(), root).values();
            // resolveSiblingCoordinates, NOT applyToModule: the latter is the lock-orchestration
            // fold, which strips sibling edges entirely. A published POM has to keep them.
            return new Resolved(
                    WorkspaceMerge.resolveSiblingCoordinates(root, module, siblings), coordinates(root, siblings));
        } catch (JkBuildParseException e) {
            throw e;
        } catch (Exception e) {
            // Best-effort: a standalone project has no workspace to merge, and a malformed root is
            // surfaced by the build itself rather than here.
            return new Resolved(module);
        }
    }

    private static Set<String> coordinates(JkBuild root, Collection<JkBuild> members) {
        Set<String> out = new LinkedHashSet<>();
        out.add(root.project().group() + ":" + root.project().name());
        for (JkBuild m : members)
            out.add(m.project().group() + ":" + m.project().name());
        return out;
    }

    /**
     * The enclosing workspace's unit coordinates ({@code group:artifact} for the root and every
     * member), or an empty set when {@code moduleDir} is standalone or the workspace cannot be
     * read. Publish paths use this to recognize sibling edges after
     * {@link WorkspaceMerge#resolveSiblingCoordinates} has rewritten them to real coordinates —
     * e.g. to omit tests-kind sibling edges whose test-jar jk never produces.
     */
    public static Set<String> siblingCoordinates(Path moduleDir) {
        try {
            var rootDir = WorkspaceLocator.findRoot(moduleDir);
            if (rootDir.isEmpty()) return Set.of();
            JkBuild root = JkBuildParser.parseLocal(ManifestPaths.manifestIn(rootDir.get()));
            if (!root.isWorkspaceRoot()) return Set.of();
            return coordinates(
                    root, WorkspaceLoader.loadModules(rootDir.get(), root).values());
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (Exception e) {
            return Set.of(); // best-effort, same policy as applyWorkspace
        }
    }

    /**
     * Every workspace member's parsed manifest, keyed by {@code group:name} <em>and</em> by bare
     * {@code name} — the companion to {@link #siblingCoordinates(Path)} for callers that need what
     * a sibling itself declares, not merely that it is one.
     *
     * <p>Both spellings because callers meet both: a manifest parsed through {@link
     * JkBuildParser#parse} has had its {@code workspace:} placeholders rewritten to real
     * coordinates, while the members loaded here still carry {@code workspace:<name>}. A lookup
     * that knew only one spelling would silently miss every edge between siblings.
     *
     * <p>Empty on any failure, same best-effort policy as its sibling method.
     */
    public static Map<String, JkBuild> siblingManifests(Path moduleDir) {
        try {
            var rootDir = WorkspaceLocator.findRoot(moduleDir);
            if (rootDir.isEmpty()) return Map.of();
            JkBuild root = JkBuildParser.parseLocal(ManifestPaths.manifestIn(rootDir.get()));
            if (!root.isWorkspaceRoot()) return Map.of();
            Map<String, JkBuild> out = new LinkedHashMap<>();
            index(out, root);
            for (JkBuild m : WorkspaceLoader.loadModules(rootDir.get(), root).values()) index(out, m);
            return Map.copyOf(out);
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void index(Map<String, JkBuild> out, JkBuild module) {
        String name = module.project().name();
        out.put(module.project().group() + ":" + name, module);
        out.putIfAbsent(name, module);
    }
}
