// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.config.WorkspaceScan;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.repo.M2Dirs;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

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
 * path: the spelling for a record's map keys, where two distinct inputs must never fold onto one
 * entry. Under jk's own roots it is relative to the root ({@code store:repos/central/…}, {@code
 * cache:sha256/…}, {@code jk-home:jdks/…}), so a record names a dependency the same way on every
 * machine; so it is under jk's state ({@code state:}) and JDKs ({@code jdks:}) and the Maven local
 * repository ({@code m2:}). Anywhere else it is the absolute path, forward-slashed. Under a module
 * the two agree.
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
        return moduleRelative(abs).orElseGet(() -> underJkRoot(abs));
    }

    /**
     * A classpath entry's name in key material: under jk's own roots and the Maven local repository
     * as {@link #underJkRoot} spells it, else relative to the workspace that holds it, else as
     * {@link #of}. Location-free, and two siblings' class trees stay apart where a module-relative
     * name reads {@code target/classes} for both.
     */
    public static String entryName(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        String spelled = underJkRoot(abs);
        boolean underRoot = !spelled.equals(slashed(abs));
        if (underRoot && !spelled.startsWith("jk-home:")) return spelled;
        Optional<Path> root = projectRoot(abs);
        if (root.isPresent() && abs.startsWith(root.get()) && !abs.equals(root.get()))
            return slashed(root.get().relativize(abs));
        return underRoot ? spelled : of(abs);
    }

    /**
     * {@code abs} relative to the root that holds it, named: jk's artifact store ({@code store:}),
     * cache ({@code cache:}), state ({@code state:}) and JDKs ({@code jdks:}), whichever is deepest,
     * since any may be moved; else the Maven local repository ({@code m2:}); else the jk home
     * ({@code jk-home:}); the absolute path when none holds it. A path in the user's home directory
     * outside these stays absolute: it names this host's own files.
     */
    public static String underJkRoot(Path abs) {
        for (List<Map.Entry<String, Path>> tier : tiers()) {
            Map.Entry<String, Path> best = null;
            for (Map.Entry<String, Path> root : tier) {
                Path dir = root.getValue();
                if (abs.startsWith(dir)
                        && !abs.equals(dir)
                        && (best == null || dir.getNameCount() > best.getValue().getNameCount())) best = root;
            }
            if (best != null) return best.getKey() + slashed(best.getValue().relativize(abs));
        }
        return slashed(abs);
    }

    /** The root words {@link #underJkRoot} spells. */
    public static final List<String> ROOT_WORDS = List.of("store:", "cache:", "state:", "jdks:", "m2:", "jk-home:");

    /**
     * This host's roots by word, absolute and normalized, in the tiers {@link #underJkRoot} tries:
     * jk's own movable directories, the Maven local repository, the jk home. A root that cannot be
     * resolved here is left out: no path is named against it.
     */
    static List<List<Map.Entry<String, Path>>> tiers() {
        List<Map.Entry<String, Path>> own = new ArrayList<>();
        add(own, "store:", JkDirs::store);
        add(own, "cache:", JkDirs::cache);
        add(own, "state:", JkDirs::state);
        add(own, "jdks:", JkDirs::jdks);
        List<Map.Entry<String, Path>> m2 = new ArrayList<>();
        add(m2, "m2:", M2Dirs::localRepository);
        List<Map.Entry<String, Path>> home = new ArrayList<>();
        add(home, "jk-home:", JkDirs::home);
        return List.of(own, m2, home);
    }

    /** Every root of {@link #tiers}. */
    static List<Map.Entry<String, Path>> roots() {
        List<Map.Entry<String, Path>> out = new ArrayList<>();
        tiers().forEach(out::addAll);
        return out;
    }

    private static void add(List<Map.Entry<String, Path>> out, String word, Supplier<@Nullable Path> dir) {
        try {
            Path p = dir.get();
            // A root at the file system's top names every path, and so none portably.
            if (p != null && p.toAbsolutePath().normalize().getNameCount() > 0)
                out.add(Map.entry(word, p.toAbsolutePath().normalize()));
        } catch (RuntimeException unresolvable) {
            // A root this host cannot name: nothing is spelled against it.
        }
    }

    /**
     * The path {@link #underJkRoot} spelled, on this machine: a root word resolved against this
     * host's root; anything else as written.
     */
    public static Path fromJkRoot(String spelled) {
        for (Map.Entry<String, Path> root : roots()) {
            if (spelled.startsWith(root.getKey())) {
                return root.getValue().resolve(spelled.substring(root.getKey().length()));
            }
        }
        return Path.of(spelled);
    }

    /**
     * {@code text} (a JVM flag, a command line) with every absolute path under {@code workspace}
     * spelled {@code ws:<relative>} and every one under a root of {@link #underJkRoot} by its word,
     * so a flag that names a file jk unpacked into its state, or a checkout's own file, reads the
     * same in another home and another checkout. Other text is left as it is.
     */
    public static String spelledText(String text, @Nullable Path workspace) {
        List<Map.Entry<String, Path>> all = new ArrayList<>(roots());
        if (workspace != null)
            all.add(Map.entry("ws:", workspace.toAbsolutePath().normalize()));
        // The deepest root first, so a path in the store is not spelled against the jk home.
        all.sort((x, y) -> Integer.compare(
                y.getValue().toString().length(), x.getValue().toString().length()));
        String out = text;
        for (Map.Entry<String, Path> root : all) {
            String dir = root.getValue().toString();
            for (String spelling : List.of(dir + File.separator, slashed(root.getValue()) + "/")) {
                out = out.replace(spelling, root.getKey());
            }
        }
        return out;
    }

    private static Optional<String> moduleRelative(Path abs) {
        Path dir = abs.getParent();
        Optional<Path> root = dir == null ? Optional.empty() : moduleRoot(dir);
        return root.map(r -> slashed(r.relativize(abs)));
    }

    static String slashed(Path p) {
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
