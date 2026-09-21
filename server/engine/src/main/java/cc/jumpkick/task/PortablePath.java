// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The spelling of a path inside an action key. An action key depends on the content of its
 * inputs, not on where the workspace is checked out, so a path is rendered relative to the module
 * that owns it — the nearest ancestor that {@linkplain ManifestPaths#describesProject describes a
 * project}, by {@code jk.toml} or by {@code pom.xml} — with forward slashes. Two checkouts of the
 * same project at different paths then compute the same key for identical inputs, which is what a
 * shared cache needs and what an absolute path designs out.
 *
 * <p>A path under no module (a JDK, a store blob, a jar declared by absolute path) has two
 * spellings. {@link #of} keeps its last two segments: key material that names nothing about the
 * machine, at the price that two distinct paths may spell the same. {@link #key} keeps the whole
 * absolute path, forward-slashed: the spelling for a record's map keys, where two distinct inputs
 * must never fold onto one entry. Under a module the two agree.
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

    /** Module root → the workspace root that owns it (or itself), same TTL as {@link #ROOTS}. */
    private static final ConcurrentHashMap<Path, Entry> OWNERS = new ConcurrentHashMap<>();

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_ENTRIES = 4_096;

    private record Entry(Optional<Path> root, long expiresAtNanos) {}

    private PortablePath() {}

    /** Module-relative under a module, else the last two segments. Key material. */
    public static String of(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Optional<String> relative = moduleRelative(abs);
        if (relative.isPresent()) return relative.get();
        Path name = abs.getFileName();
        if (name == null) return slashed(abs);
        Path parent = abs.getParent();
        Path parentName = parent == null ? null : parent.getFileName();
        return (parentName == null ? "" : parentName + "/") + name;
    }

    /**
     * Module-relative under a module, else the absolute path with forward slashes. The map key of
     * a record entry: unique per path, and location-free wherever {@link #of} is.
     */
    public static String key(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        return moduleRelative(abs).orElseGet(() -> slashed(abs));
    }

    private static Optional<String> moduleRelative(Path abs) {
        Path dir = abs.getParent();
        Optional<Path> root = dir == null ? Optional.empty() : moduleRoot(dir);
        return root.map(r -> slashed(r.relativize(abs)));
    }

    private static String slashed(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static Optional<Path> moduleRoot(Path dir) {
        Entry memo = ROOTS.get(dir);
        if (memo != null && Clock.SYSTEM.nanos() - memo.expiresAtNanos() < 0) return memo.root();
        Optional<Path> found = Optional.empty();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (ManifestPaths.describesProject(d)) {
                found = Optional.of(d);
                break;
            }
        }
        if (ROOTS.size() >= MAX_ENTRIES) ROOTS.clear();
        ROOTS.put(dir, new Entry(found, Clock.SYSTEM.nanos() + TTL_NANOS));
        return found;
    }

    /**
     * The project root that owns {@code path}: the nearest ancestor describing a project by {@code
     * jk.toml} or {@code pom.xml} (the path itself when it does), lifted to the workspace root that
     * lists it ({@link WorkspaceScan#owningRoot}). Empty for a path under no project. This is the
     * root {@link ActionKey#taskTag} spells an output dir against, so a module's outputs and its
     * own directory resolve to the same root whether they sit under the workspace's {@code
     * target/} or the module's.
     */
    public static Optional<Path> projectRoot(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        Optional<Path> nearest = ManifestPaths.describesProject(abs)
                ? Optional.of(abs)
                : abs.getParent() == null ? Optional.empty() : moduleRoot(abs.getParent());
        if (nearest.isEmpty()) return Optional.empty();
        Path module = nearest.get();
        Entry memo = OWNERS.get(module);
        if (memo != null && Clock.SYSTEM.nanos() - memo.expiresAtNanos() < 0) return memo.root();
        Optional<Path> owner = Optional.of(WorkspaceScan.owningRoot(module).orElse(module));
        if (OWNERS.size() >= MAX_ENTRIES) OWNERS.clear();
        OWNERS.put(module, new Entry(owner, Clock.SYSTEM.nanos() + TTL_NANOS));
        return owner;
    }

    /** Test seam: drop every memo, as the TTL would. */
    static void forget() {
        ROOTS.clear();
        OWNERS.clear();
    }

    /** Test seam: age every memo past its TTL so the next lookup walks again. */
    static void expire() {
        ROOTS.replaceAll((dir, e) -> new Entry(e.root(), Clock.SYSTEM.nanos() - 1));
        OWNERS.replaceAll((dir, e) -> new Entry(e.root(), Clock.SYSTEM.nanos() - 1));
    }
}
