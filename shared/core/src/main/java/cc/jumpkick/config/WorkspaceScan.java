// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final String WORKSPACE_MODULES = "workspace.modules";

    /** Manifests are few per engine; a test run's temp projects are many, so the memo is capped. */
    private static final int SCANNED_MAX = 1024;

    private static final ConcurrentHashMap<Path, Scanned> SCANNED = new ConcurrentHashMap<>();

    /** One {@link #modulesListed} answer and the file state it was read from. */
    private record Scanned(FileStamp stamp, List<String> modules) {}

    private WorkspaceScan() {}

    /**
     * The {@code [workspace] modules} entries of {@code jkToml}, as written; empty when it is
     * absent, unreadable or lists none. Remembered against the file's size and modification time:
     * catalog-root and workspace-root discovery ask for every module of every parse, and on jk's
     * own 54-module workspace the rescans of its root manifest were most of a {@code jk status}.
     */
    public static List<String> modulesListed(Path jkToml) {
        FileStamp stamp = FileStamp.of(jkToml);
        if (stamp == null) return List.of();
        Path key = jkToml.toAbsolutePath().normalize();
        Scanned kept = SCANNED.get(key);
        if (kept != null && kept.stamp().equals(stamp)) return kept.modules();
        List<String> modules =
                List.copyOf(TomlScan.scan(jkToml, WORKSPACE_MODULES).stringArray(WORKSPACE_MODULES));
        if (SCANNED.size() >= SCANNED_MAX) SCANNED.clear();
        SCANNED.put(key, new Scanned(stamp, modules));
        return modules;
    }

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
                if (WorkspaceModules.lists(modulesListed(rootJkToml), relative)) {
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
        return !modulesListed(dir.resolve(ManifestPaths.MANIFEST)).isEmpty();
    }
}
