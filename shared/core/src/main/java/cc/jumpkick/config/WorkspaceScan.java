// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Client-side twin of {@link WorkspaceLocator} using {@link TomlScan} instead of a full parse.
 * Exotic TOML for {@code workspace.modules} reads as "not a workspace", never wrong membership.
 *
 * <p>A directory built from its {@code pom.xml} (no {@code jk.toml}) is a workspace root when the
 * POM lists modules and no outer reactor lists it; its members are what {@link PomReactorScan}
 * reads from the raw POMs. The shadow manifest the engine renders for such a root carries the same
 * list as {@code [workspace] modules}.
 */
public final class WorkspaceScan {

    private static final int MAX_DEPTH = 8192;

    private WorkspaceScan() {}

    /**
     * The nearest strict ancestor whose {@code jk.toml} declares workspace modules, or empty.
     * Mirrors {@link WorkspaceLocator#findEnclosingWorkspace} (no membership requirement — used
     * by commands about to create/register a module, which is why only a {@code jk.toml} root
     * counts: a POM-built root has no manifest to register the module in).
     */
    public static Optional<Path> findEnclosingWorkspace(Path dir) {
        Path candidate = dir.toAbsolutePath().normalize();
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            if (declaresTomlModules(parent)) return Optional.of(parent);
            candidate = parent;
        }
        return Optional.empty();
    }

    /**
     * The workspace root that owns {@code moduleDir}, or empty: the nearest ancestor whose {@code
     * jk.toml} lists it in {@code workspace.modules}, else the outermost POM-built ancestor whose
     * reactor lists it. Mirrors {@link WorkspaceLocator#findRoot}.
     */
    public static Optional<Path> findRoot(Path moduleDir) {
        Path normalized = moduleDir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break;
            Path rootJkToml = parent.resolve(ManifestPaths.MANIFEST);
            if (Files.exists(rootJkToml)) {
                String relative = parent.relativize(normalized).toString().replace('\\', '/');
                if (WorkspaceModules.lists(
                        TomlScan.scan(rootJkToml, "workspace.modules").stringArray("workspace.modules"), relative)) {
                    return Optional.of(parent);
                }
            }
            candidate = parent;
        }
        return PomReactorScan.reactorRootOf(normalized);
    }

    /**
     * The workspace root {@code dir} belongs to — {@code dir} itself when it is a root, otherwise
     * the ancestor that lists it — or empty when {@code dir} is a standalone project. Callers
     * deciding "workspace build or single project?" want this, not {@link #findRoot}: a root is
     * not one of its own {@code workspace.modules}, so {@code findRoot} answers empty there.
     */
    public static Optional<Path> owningRoot(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        return isWorkspaceRoot(normalized) ? Optional.of(normalized) : findRoot(normalized);
    }

    /**
     * True when {@code dir/jk.toml} declares a non-empty {@code [workspace] modules} list, or when
     * {@code dir} is built from a {@code pom.xml} that lists modules and no outer reactor lists
     * {@code dir} itself (a nested aggregator belongs to the reactor above it).
     */
    public static boolean isWorkspaceRoot(Path dir) {
        if (Files.exists(dir.resolve(ManifestPaths.MANIFEST))) return declaresTomlModules(dir);
        return ManifestPaths.isShadowed(dir)
                && PomReactorScan.declaresModules(dir.resolve(ManifestPaths.POM))
                && PomReactorScan.reactorRootOf(dir).isEmpty();
    }

    private static boolean declaresTomlModules(Path dir) {
        Path toml = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(toml)) return false;
        return !TomlScan.scan(toml, "workspace.modules")
                .stringArray("workspace.modules")
                .isEmpty();
    }
}
