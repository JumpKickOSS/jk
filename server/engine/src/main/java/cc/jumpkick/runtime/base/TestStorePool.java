// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.PoolUse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The verified downloads every test sandbox on a host shares, under {@code <home>/test-store}: the
 * public-origin repository trees and the build-tool distributions, each linked into a sandbox store
 * at the same relative path ({@link #SHARED}), and a managed Android SDK a suite reaches through
 * {@code -Djk.test.pool.dir} ({@link #ANDROID_SDK}). A worktree's first suite therefore finds what
 * any other worktree's suites already fetched, instead of starting cold.
 *
 * <p>Only content that is verified and published whole is pooled. A repository tree holds what its
 * origin served: a store is keyed by origin, so a loopback stub or a {@code file:} repository lands
 * in a tree of its own, and an artifact lands through a temp file and an atomic rename. A tool
 * distribution is checked against its pin or its publisher's checksum and published by one rename
 * of a staged tree; an Android SDK component likewise. The rest of a sandbox store stays its own:
 * the {@code jk-local} shelf a suite installs onto, version lists, the store CAS and the closure
 * indexes into it, credentials.
 *
 * <p>The pool sits beside {@code test-homes}, outside every sandbox home, so a test that nukes or
 * cleans its home deletes the links and not the pool: jk's deletes never follow a link. A test that
 * writes fake bytes under a public coordinate or a real tool version, mirrors Central at a stub, or
 * must see a cold download takes a private store ({@code jk.env.JK_STORE_DIR}).
 *
 * <p>The pool is bounded by {@link #reap}: a unit unused for {@link #UNUSED_MILLIS} goes, then least
 * recently used units go once it is over its cap, and what a killed process left staged goes after an hour.
 */
public final class TestStorePool {

    /** Under the product home ({@link JkDirs#home()}), beside {@code test-homes}. */
    public static final String DIR = "test-store";

    /** Store-relative trees the pool holds, each linked into a sandbox store at the same path. */
    static final List<String> SHARED = List.of(
            "repos/" + RepositorySpec.CENTRAL,
            "repos/" + RepositorySpec.GOOGLE,
            "repos/" + RepositorySpec.JUMPKICK_NAME,
            "tools/kotlin",
            "tools/gradle",
            "tools/maven");

    /** The managed Android SDK root under the pool. */
    public static final String ANDROID_SDK = "android-sdk";

    /** The test JVM property naming the pool, for caches a suite opts into by name. */
    public static final String PROPERTY = "jk.test.pool.dir";

    /** A staged download, install or unpack this old was left by a process that died. */
    static final long LEAK_MILLIS = 60L * 60 * 1000;

    /** A unit used this recently is never evicted, over the cap or not: a running suite may hold it. */
    static final long IDLE_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * A unit unused this long goes whatever the pool weighs. The pool is a convenience for the next
     * run, not an archive: a fetched dependency nobody has asked for in a week is cheaper fetched
     * again than held.
     */
    static final long UNUSED_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** One reap per host per this long: {@link #REAPED_AT} records the last. */
    static final long REAP_EVERY_MILLIS = 60L * 60 * 1000;

    /**
     * Bytes the pool may hold before the least recently used units go: about what the
     * integration, slow and network tiers fetch together. {@link #MAX_MB_PROPERTY} overrides it.
     */
    static final long DEFAULT_MAX_BYTES = 2L << 30;

    /** The default on CI, where one runner serves many branches and a cold fetch costs the most. */
    static final long CI_MAX_BYTES = 8L << 30;

    /** The engine property (env {@code JK_TEST_POOL_MAX_MB}) that sets the cap in MiB. */
    public static final String MAX_MB_PROPERTY = "jk.test.pool.max.mb";

    /** Under the pool: its mtime is when the last reap on this host started. */
    static final String REAPED_AT = ".reaped-at";

    /** Under the pool: held by the one process reaping it. */
    static final String REAP_LOCK = ".reap.lock";

    /** Under the pool: where an evicted unit is renamed before it is deleted. */
    static final String TRASH = ".trash";

    /** When this process last asked whether a pool was due, by pool. */
    private static final ConcurrentHashMap<Path, Long> CHECKED = new ConcurrentHashMap<>();

    private static final AtomicLong SEQ = new AtomicLong();

    private TestStorePool() {}

    /** {@code <home>/test-store}: under {@code JK_HOME} when set, else {@code ~/.jk}. */
    public static Path root() {
        return JkDirs.home().resolve(DIR);
    }

    /**
     * Make each of {@link #SHARED} in {@code sandboxStore} a link into {@code pool}; returns how many
     * links were placed. A private tree already there is replaced, once the link is known to work, so
     * a host that cannot make links keeps the sandbox's own tree. Reaps the pool first when it is
     * due, and marks every pooled tree for use stamps ({@link PoolUse}).
     */
    public static int link(Path pool, Path sandboxStore) throws IOException {
        reapIfDue(pool, Clock.SYSTEM.millis(), maxBytes());
        PoolUse.mark(pool.resolve(ANDROID_SDK));
        int placed = 0;
        for (String rel : SHARED) {
            Path shared =
                    Files.createDirectories(pool.resolve(rel)).toAbsolutePath().normalize();
            PoolUse.mark(shared);
            Path entry = sandboxStore.resolve(rel);
            if (linksTo(entry, shared)) continue;
            Path parent = Files.createDirectories(entry.getParent());
            Path staged = parent.resolve("." + entry.getFileName() + ".link-"
                    + ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet());
            Files.createSymbolicLink(staged, shared);
            try {
                if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(entry)) {
                    PathUtil.deleteRecursivelyOrThrow(entry);
                }
                Files.move(staged, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                placed++;
            } finally {
                Files.deleteIfExists(staged);
            }
        }
        return placed;
    }

    /** Whether {@code entry} is a link naming {@code target}. */
    static boolean linksTo(Path entry, Path target) {
        if (!Files.isSymbolicLink(entry)) return false;
        try {
            return Files.readSymbolicLink(entry).equals(target);
        } catch (IOException | UnsupportedOperationException unreadable) {
            return false;
        }
    }

    static long maxBytes() {
        return maxBytes(System.getProperty(MAX_MB_PROPERTY), System::getenv);
    }

    /**
     * The cap in bytes: {@code property}, else {@code JK_TEST_POOL_MAX_MB}, else {@link
     * #CI_MAX_BYTES} on CI and {@link #DEFAULT_MAX_BYTES} elsewhere.
     */
    static long maxBytes(@Nullable String property, Function<String, @Nullable String> env) {
        long fallback = EnvValues.isCi(env) ? CI_MAX_BYTES : DEFAULT_MAX_BYTES;
        String raw = property != null ? property : env.apply("JK_TEST_POOL_MAX_MB");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Math.max(0, Long.parseLong(raw.trim())) << 20;
        } catch (NumberFormatException e) {
            Log.debug("TestStorePool: " + MAX_MB_PROPERTY + "=" + raw + " is not a number of MiB; the default holds");
            return fallback;
        }
    }

    /** What one reap did: the bytes it left, the units it evicted, the leaked temps it removed. */
    record Pass(long bytes, int evicted, int leaks) {}

    /**
     * {@link #reap} when no process on this host started one within {@link #REAP_EVERY_MILLIS} and
     * none is reaping now; null when skipped. Never fails the caller.
     */
    static @Nullable Pass reapIfDue(Path pool, long nowMillis, long capBytes) {
        Long asked = CHECKED.get(pool);
        if (asked != null && nowMillis - asked < REAP_EVERY_MILLIS) return null;
        CHECKED.put(pool, nowMillis);
        Path stamp = pool.resolve(REAPED_AT);
        if (!Files.isDirectory(pool) || reapedSince(stamp, nowMillis)) return null;
        FileLocks.Probe probe = FileLocks.tryHold(pool.resolve(REAP_LOCK));
        if (probe instanceof FileLocks.Held) return null;
        try {
            if (reapedSince(stamp, nowMillis)) return null;
            Files.writeString(stamp, "");
            Files.setLastModifiedTime(stamp, FileTime.fromMillis(nowMillis));
            Pass pass = reap(pool, nowMillis, capBytes);
            if (pass.evicted() > 0 || pass.leaks() > 0) {
                Log.debug("TestStorePool: evicted " + pass.evicted() + " units and " + pass.leaks()
                        + " leaked temps from " + pool + "; " + pass.bytes() + " bytes remain");
            }
            return pass;
        } catch (IOException | RuntimeException e) {
            Log.debug("TestStorePool: the pool is reaped next time", e);
            return null;
        } finally {
            // Unavailable (a filesystem without locks) reaps unlocked: every step tolerates a racer.
            if (probe instanceof FileLocks.Hold hold) closeQuietly(hold);
        }
    }

    private static boolean reapedSince(Path stamp, long nowMillis) {
        try {
            return Files.getLastModifiedTime(stamp).toMillis() > nowMillis - REAP_EVERY_MILLIS;
        } catch (IOException absent) {
            return false;
        }
    }

    private static void closeQuietly(FileLocks.Hold hold) {
        try {
            hold.close();
        } catch (IOException e) {
            Log.debug("TestStorePool: the reap lock goes when this process exits", e);
        }
    }

    /**
     * One walk of the pool: remove the leaked temps older than {@link #LEAK_MILLIS} wherever they
     * sit, then, while the pool is over {@code capBytes}, evict the least recently used units not
     * used within {@link #IDLE_MILLIS}.
     *
     * <p>A unit is what is fetched and published whole: an artifact version directory under {@code
     * repos/<id>} (the shallowest directory holding files), a tool version under {@code
     * tools/<slug>}, an Android SDK component. Its last use is its directory's mtime, which the
     * repository store, the tool registry and the SDK installer stamp on use ({@link PoolUse}). An
     * evicted unit is renamed into {@link #TRASH} in one step before it is deleted, so a reader finds
     * the whole unit or none of it, and fetches it again.
     */
    static Pass reap(Path pool, long nowMillis, long capBytes) throws IOException {
        Path trash = pool.resolve(TRASH);
        PathUtil.forEachChild(trash, (left, attrs) -> {
            PathUtil.deleteRecursively(left);
            return true;
        });
        Walk walk = new Walk(nowMillis - LEAK_MILLIS);
        PathUtil.forEachChild(pool.resolve("repos"), (tree, attrs) -> {
            if (attrs.isDirectory()) walk.tree(tree, false);
            return true;
        });
        PathUtil.forEachChild(pool.resolve("tools"), (tree, attrs) -> {
            if (attrs.isDirectory()) walk.tree(tree, true);
            return true;
        });
        walk.tree(pool.resolve(ANDROID_SDK), false);

        long bytes = walk.bytes;
        int evicted = 0;
        long idleBefore = nowMillis - IDLE_MILLIS;
        long unusedBefore = nowMillis - UNUSED_MILLIS;
        walk.units.sort(Comparator.comparingLong(Unit::usedAt));
        for (Unit unit : walk.units) {
            if (unit.usedAt() >= idleBefore || (bytes <= capBytes && unit.usedAt() >= unusedBefore)) break;
            if (evict(unit.dir(), trash, idleBefore)) {
                bytes -= unit.bytes();
                evicted++;
            }
        }
        return new Pass(bytes, evicted, walk.leaks);
    }

    /**
     * Rename {@code unit} into {@code trash}, then delete it there, unless it was used since the walk
     * saw it. False when it stays: used meanwhile, gone already, or held where a rename is refused.
     */
    static boolean evict(Path unit, Path trash, long idleBefore) {
        try {
            if (Files.getLastModifiedTime(unit, LinkOption.NOFOLLOW_LINKS).toMillis() >= idleBefore) return false;
            Path doomed = Files.createDirectories(trash)
                    .resolve(ProcessHandle.current().pid() + "-" + SEQ.incrementAndGet() + "-" + unit.getFileName());
            Files.move(unit, doomed, StandardCopyOption.ATOMIC_MOVE);
            PathUtil.deleteRecursively(doomed);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.debug("TestStorePool: " + unit + " stays until a later reap", e);
            return false;
        }
    }

    /** A unit the walk found: its bytes and when it was last used. */
    record Unit(Path dir, long bytes, long usedAt) {}

    /** Whether {@code name} is what a writer stages: a download's temp, a tool's stage, an unpack, an SDK zip. */
    static boolean staged(String name) {
        return (name.startsWith(".") && name.endsWith(".part"))
                || (name.startsWith(".put-") && name.endsWith(".tmp"))
                || name.startsWith("jk-tool-stage-")
                || name.contains(".extract-")
                || (name.startsWith("jk-sdk-") && name.endsWith(".zip"));
    }

    /** One reap's walk: the units it found, the bytes it counted, the leaks it removed. */
    private static final class Walk {
        private final long leakBefore;
        final List<Unit> units = new ArrayList<>();
        long bytes;
        int leaks;

        Walk(long leakBefore) {
            this.leakBefore = leakBefore;
        }

        /**
         * The tree at {@code root}. A tool tree's units are its child directories; any other's are
         * found by {@link #dir}. A dot directory or {@code licenses} is never a unit.
         */
        void tree(Path root, boolean childUnits) throws IOException {
            for (Entry child : list(root)) {
                if (!child.dir()) {
                    bytes += child.bytes();
                } else if (child.name().startsWith(".") || child.name().equals("licenses")) {
                    for (Entry kept : list(child.path())) bytes += Math.max(0, kept.bytes());
                } else if (childUnits) {
                    unit(child, PathUtil.measureTrees(List.of(child.path())).bytes());
                } else {
                    dir(child);
                }
            }
        }

        /** A directory holding files is a unit; one holding only directories is walked further. */
        private void dir(Entry at) throws IOException {
            List<Entry> children = list(at.path());
            long files = 0;
            boolean holdsFiles = false;
            List<Path> nested = new ArrayList<>();
            for (Entry child : children) {
                if (child.dir()) {
                    nested.add(child.path());
                } else {
                    holdsFiles = true;
                    files += child.bytes();
                }
            }
            if (holdsFiles) {
                unit(
                        at,
                        files
                                + (nested.isEmpty()
                                        ? 0
                                        : PathUtil.measureTrees(nested).bytes()));
                return;
            }
            for (Entry child : children) {
                if (!child.name().startsWith(".")) dir(child);
            }
        }

        private void unit(Entry at, long size) {
            units.add(new Unit(at.path(), size, at.mtime()));
            bytes += size;
        }

        /**
         * The directories and regular files in {@code dir}, links aside. A staged entry older than
         * the leak window is removed; a younger one may be a live download's and only counts.
         */
        private List<Entry> list(Path dir) throws IOException {
            List<Entry> out = new ArrayList<>();
            PathUtil.forEachChild(dir, (path, attrs) -> {
                String name = path.getFileName().toString();
                long mtime = attrs.lastModifiedTime().toMillis();
                if (staged(name)) {
                    if (mtime < leakBefore) {
                        PathUtil.deleteRecursively(path);
                        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) leaks++;
                    } else {
                        bytes += attrs.isDirectory()
                                ? PathUtil.measureTrees(List.of(path)).bytes()
                                : attrs.size();
                    }
                } else if (attrs.isDirectory()) {
                    out.add(new Entry(path, name, true, -1, mtime));
                } else if (attrs.isRegularFile()) {
                    out.add(new Entry(path, name, false, attrs.size(), mtime));
                }
                return true;
            });
            return out;
        }
    }

    /** One entry of a listing; a directory's {@code bytes} is -1. */
    private record Entry(Path path, String name, boolean dir, long bytes, long mtime) {}
}
