// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The spelling of a path inside an action key. An action key depends on the content of its
 * inputs, not on where the workspace is checked out, so a path is rendered relative to the module
 * that owns it — the nearest ancestor carrying {@code jk.toml} — with forward slashes. Two checkouts
 * of the same project at different paths then compute the same key for identical inputs, which is
 * what a shared cache needs and what an absolute path designs out.
 *
 * <p>A path under no module (a JDK, a store blob, a jar declared by absolute path) keeps its last two
 * segments: enough to tell two absent entries apart, nothing that names the machine.
 */
public final class PortablePath {

    /** Module root per directory, or empty once a walk has found none. Directories rarely move. */
    private static final ConcurrentHashMap<Path, Optional<Path>> ROOTS = new ConcurrentHashMap<>();

    private PortablePath() {}

    public static String of(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        Optional<Path> root = dir == null ? Optional.empty() : moduleRoot(dir);
        if (root.isPresent()) return root.get().relativize(abs).toString().replace('\\', '/');
        Path name = abs.getFileName();
        if (name == null) return abs.toString().replace('\\', '/');
        Path parent = abs.getParent();
        Path parentName = parent == null ? null : parent.getFileName();
        return (parentName == null ? "" : parentName + "/") + name;
    }

    private static Optional<Path> moduleRoot(Path dir) {
        Optional<Path> memo = ROOTS.get(dir);
        if (memo != null) return memo;
        Optional<Path> found = Optional.empty();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(ManifestPaths.MANIFEST))) {
                found = Optional.of(d);
                break;
            }
        }
        if (ROOTS.size() > 4096) ROOTS.clear();
        ROOTS.put(dir, found);
        return found;
    }

    /** Test seam: a module created after its directory was first asked about must be seen. */
    static void forget() {
        ROOTS.clear();
    }
}
