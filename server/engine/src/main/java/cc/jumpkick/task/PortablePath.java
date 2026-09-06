// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /**
     * Module root per directory, or empty once a walk has found none, remembered for {@link
     * #TTL_NANOS}. Directories rarely move, but manifests do appear: a directory first asked about
     * before its {@code jk.toml} existed ({@code jk new} under a resident engine, a generated
     * module, a branch switch) would otherwise be keyed against the wrong root for the life of the
     * process — a key no fresh engine reproduces, so a spurious miss on the next restart and a hit
     * this engine alone can see. The walk behind a re-check is a handful of stats; the memo is for
     * the thousands of files an action key names inside one request, not for the calendar.
     */
    private static final ConcurrentHashMap<Path, Entry> ROOTS = new ConcurrentHashMap<>();

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_ENTRIES = 4_096;

    private record Entry(Optional<Path> root, long expiresAtNanos) {}

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
        Entry memo = ROOTS.get(dir);
        if (memo != null && System.nanoTime() - memo.expiresAtNanos() < 0) return memo.root();
        Optional<Path> found = Optional.empty();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(ManifestPaths.MANIFEST))) {
                found = Optional.of(d);
                break;
            }
        }
        if (ROOTS.size() >= MAX_ENTRIES) ROOTS.clear();
        ROOTS.put(dir, new Entry(found, System.nanoTime() + TTL_NANOS));
        return found;
    }

    /** Test seam: drop every memo, as the TTL would. */
    static void forget() {
        ROOTS.clear();
    }

    /** Test seam: age every memo past its TTL so the next lookup walks again. */
    static void expire() {
        ROOTS.replaceAll((dir, e) -> new Entry(e.root(), System.nanoTime() - 1));
    }
}
