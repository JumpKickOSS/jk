// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.EngineAotCache;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How long the JEP 514 startup caches under {@code <state>/} live: the workers' ({@code aot/},
 * {@link WorkerAotCache}) and the engine's own ({@code engine-aot/}, {@link EngineAotCache}). Each
 * file's name carries the jk version that recorded it; mapping a worker cache touches it, and an
 * engine touches its own when it sweeps.
 *
 * <ul>
 *   <li>Another jk version's cache can never map again and goes, a snapshot's under a release and a
 *       release's under a snapshot alike.
 *   <li>Under a {@code -SNAPSHOT} jk (a contributor's checkout, CI, a gate host) every rebuilt worker
 *       or engine jar is a new key under the same version, so every cache not mapped for {@link
 *       #SNAPSHOT_UNUSED} goes, whatever its key. No end user runs a snapshot.
 *   <li>Under a release nothing goes by age: only a volume {@link DiskGuard} calls critical takes
 *       caches, least recently mapped first, as many as get it back over the line ({@link
 *       #reclaimUnderPressure}). {@code jk storage clean} takes every one not in use ({@link #clear}).
 * </ul>
 *
 * <p>{@link #sweep} runs at engine start, when a worker cache lands and on the engine's 12 h
 * maintenance cycle. Never deleted ({@link #inUse}): a cache a live process maps, read off its
 * command line ({@code -XX:AOTCache=}: a warm worker, this engine itself, another engine of the
 * home), one this engine is recording, and the engine cache for this engine's own jar. A sweep
 * touches the ones live processes map, so their mtime says they are still mapped. A cache another
 * engine of the same home recorded or mapped moments ago carries a fresh mtime, so the age rule
 * spares it. Where a platform shows no process arguments (Windows), the platform itself refuses to
 * delete a mapped file; a refused delete is skipped and counted, never thrown.
 */
public final class StartupCaches {

    private StartupCaches() {}

    /** Under a snapshot jk, a cache not mapped for this long goes. */
    static final Duration SNAPSHOT_UNUSED = Duration.ofHours(12);

    /** A worker trainer's temp sibling older than this belongs to a dead engine. */
    static final Duration TMP_TTL = Duration.ofHours(24);

    /** {@code engine-<jk-version>-<16 hex key>.aot}, as {@link EngineAotCache#cacheFile} names it. */
    private static final Pattern ENGINE_NAME =
            Pattern.compile("engine-(?<version>\\d[^-]*(?:-SNAPSHOT)?)-[0-9a-f]{16}\\.aot");

    /** What a pass removed (or, dry, would), and the deletes the platform refused. */
    public record Report(int deleted, long freedBytes, int skipped) {
        static final Report NONE = new Report(0, 0, 0);
    }

    /** Deletes one file; a test makes it refuse. */
    @FunctionalInterface
    interface Deleter {
        void delete(Path file) throws IOException;
    }

    private static final Deleter DELETE = Files::deleteIfExists;

    /** One cache on disk: {@code version} is null for a name no jk of today writes. */
    record Entry(Path path, @Nullable String version, long mtimeMillis, long bytes) {}

    /** The two directories, workers' first. */
    static List<Path> dirs(Path state) {
        return List.of(state.resolve("aot"), EngineAotCache.dir(state));
    }

    /** Files and bytes under both directories, for {@code jk storage usage}. */
    public static long[] usage(Path state) {
        long[] total = {0, 0};
        for (Path dir : dirs(state)) {
            try {
                PathUtil.forEachRegularFile(dir, (file, attrs) -> {
                    total[0]++;
                    total[1] += attrs.size();
                });
            } catch (IOException unreadable) {
                // a directory that cannot be listed counts nothing
            }
        }
        return total;
    }

    /** The version and snapshot rules over this home's caches; best-effort, says what went. */
    public static void sweepQuietly(Consumer<String> log) {
        try {
            Set<Path> held = inUse();
            touch(held);
            Report r = sweep(JkDirs.state(), JkVersion.VERSION, held, Clock.SYSTEM.millis(), DELETE);
            if (r.deleted() > 0) {
                log.accept("jk engine: removed " + r.deleted() + " startup cache file(s) (" + (r.freedBytes() >> 20)
                        + " MiB)"
                        + (Versions.isSnapshot(JkVersion.VERSION)
                                ? " of another version or unused for " + SNAPSHOT_UNUSED.toHours() + " h"
                                : " of another version"));
            }
        } catch (RuntimeException e) {
            Log.debug("StartupCaches.sweep: best-effort", e);
        }
    }

    /**
     * Delete, under {@code state}, every cache another version recorded; under a snapshot {@code
     * running} also every one unmapped for {@link #SNAPSHOT_UNUSED}; and worker trainer temps older
     * than {@link #TMP_TTL}. {@code inUse} is never touched.
     */
    static Report sweep(Path state, String running, Set<Path> inUse, long nowMillis, Deleter deleter) {
        boolean snapshot = Versions.isSnapshot(running);
        List<Entry> doomed = new ArrayList<>();
        for (Path dir : dirs(state)) {
            try {
                PathUtil.forEachChild(dir, (p, attrs) -> {
                    if (!attrs.isRegularFile()) return true;
                    String name = p.getFileName().toString();
                    long age = nowMillis - attrs.lastModifiedTime().toMillis();
                    if (name.endsWith(".aot")) {
                        Entry e = entry(dir, p, attrs.lastModifiedTime(), attrs.size());
                        if (inUse.contains(normal(p))) return true;
                        if (!running.equals(e.version()) || (snapshot && age > SNAPSHOT_UNUSED.toMillis())) {
                            doomed.add(e);
                        }
                    } else if (name.contains(".aot.tmp-") && age > TMP_TTL.toMillis()) {
                        doomed.add(new Entry(p, null, attrs.lastModifiedTime().toMillis(), attrs.size()));
                    }
                    return true;
                });
            } catch (IOException unreadable) {
                // opportunistic: a leftover cache costs disk, not correctness
            }
        }
        return delete(doomed, Long.MAX_VALUE, false, deleter);
    }

    /**
     * The volume holding the state directory is critical ({@link DiskGuard}): delete caches no live
     * process uses, least recently mapped first, until the volume is back over the line or
     * none are left. Called by the engine's disk guard after its store and cache passes.
     */
    public static Report reclaimUnderPressure(Consumer<String> log) {
        try {
            Path state = JkDirs.state();
            long goal = shortfall(JkCacheConfig.DiskSpace.probe(state));
            if (goal <= 0) return Report.NONE;
            Report r = reclaim(state, inUse(), goal, false, DELETE);
            if (r.deleted() > 0) {
                log.accept("jk engine: emergency reclaim removed " + r.deleted() + " startup cache file(s) ("
                        + (r.freedBytes() >> 20) + " MiB); they are recorded again when next used");
            }
            return r;
        } catch (RuntimeException e) {
            Log.debug("StartupCaches.reclaimUnderPressure: best-effort", e);
            return Report.NONE;
        }
    }

    /** {@code jk storage clean}: every cache not in use, whatever its age or version. */
    public static Report clear(boolean dryRun) {
        return reclaim(JkDirs.state(), inUse(), Long.MAX_VALUE, dryRun, DELETE);
    }

    /** Bytes {@code space} lacks to be off {@link DiskGuard}'s critical line; 0 when it is not critical. */
    static long shortfall(JkCacheConfig.@Nullable DiskSpace space) {
        if (space == null || !DiskGuard.critical(space)) return 0;
        long line = Math.max(
                DiskGuard.CRITICAL_FREE_BYTES, (long) Math.ceil(space.totalBytes() * DiskGuard.CRITICAL_FREE_RATIO));
        return Math.max(1, line - space.freeBytes());
    }

    /** Caches under {@code state} not in {@code inUse}, oldest mtime first, until {@code goalBytes} are freed. */
    static Report reclaim(Path state, Set<Path> inUse, long goalBytes, boolean dryRun, Deleter deleter) {
        List<Entry> candidates = new ArrayList<>();
        for (Path dir : dirs(state)) {
            try {
                PathUtil.forEachChild(dir, (p, attrs) -> {
                    if (attrs.isRegularFile()
                            && p.getFileName().toString().endsWith(".aot")
                            && !inUse.contains(normal(p))) {
                        candidates.add(entry(dir, p, attrs.lastModifiedTime(), attrs.size()));
                    }
                    return true;
                });
            } catch (IOException unreadable) {
                // nothing to take from a directory that cannot be listed
            }
        }
        candidates.sort(Comparator.comparingLong(Entry::mtimeMillis));
        return delete(candidates, goalBytes, dryRun, deleter);
    }

    private static Report delete(List<Entry> doomed, long goalBytes, boolean dryRun, Deleter deleter) {
        int deleted = 0;
        int skipped = 0;
        long freed = 0;
        for (Entry e : doomed) {
            if (freed >= goalBytes) break;
            try {
                if (!dryRun) deleter.delete(e.path());
                deleted++;
                freed += e.bytes();
            } catch (IOException refused) {
                // Windows refuses while another process maps the file; the next pass tries again.
                skipped++;
                Log.debug("startup cache " + e.path() + " not deleted: " + refused.getMessage());
            }
        }
        return new Report(deleted, freed, skipped);
    }

    private static Entry entry(Path dir, Path p, FileTime mtime, long bytes) {
        String name = p.getFileName().toString();
        Matcher m = dir.getFileName().toString().equals("aot")
                ? WorkerAotCache.NAME.matcher(name)
                : ENGINE_NAME.matcher(name);
        return new Entry(p, m.matches() ? m.group("version") : null, mtime.toMillis(), bytes);
    }

    /**
     * What must stay: caches live processes map, those this engine is recording, and the engine
     * cache for this engine's own jar and JDK (mapped by the next engine of this install even when
     * this one started before it was recorded).
     */
    static Set<Path> inUse() {
        Set<Path> held = new HashSet<>();
        ProcessHandle.allProcesses()
                .forEach(h -> h.info().arguments().ifPresent(args -> held.addAll(mappedBy(List.of(args)))));
        for (Path p : WorkerAotCache.training()) held.add(normal(p));
        Path own = ownEngineCache();
        if (own != null) held.add(normal(own));
        return held;
    }

    /** The caches {@code args}, one process's command line, maps. */
    static Set<Path> mappedBy(List<String> args) {
        Set<Path> mapped = new HashSet<>();
        for (String arg : args) {
            if (!arg.startsWith(MAP_FLAG)) continue;
            try {
                mapped.add(normal(Path.of(arg.substring(MAP_FLAG.length()))));
            } catch (InvalidPathException notOurs) {
                // another tool's flag spelled oddly: nothing of ours
            }
        }
        return mapped;
    }

    private static final String MAP_FLAG = "-XX:AOTCache=";

    /** The engine cache for this process's jar and JDK, or null when this process is not an installed engine. */
    private static @Nullable Path ownEngineCache() {
        Path jar;
        try {
            jar = Path.of(System.getProperty("java.class.path", ""));
        } catch (InvalidPathException severalEntries) {
            // A class path of several entries is no path on Windows (its ';' and second drive colon).
            return null;
        }
        Path home = JavaHomes.runningJavaHome();
        if (home == null || !Files.isRegularFile(jar)) return null;
        return EngineAotCache.cacheFile(JkDirs.state(), home, jar);
    }

    /** Mapping touches a cache; a sweep says so again for the ones still mapped, here and in other processes. */
    private static void touch(Set<Path> held) {
        long now = Clock.SYSTEM.millis();
        for (Path p : held) {
            try {
                if (Files.isRegularFile(p)) Files.setLastModifiedTime(p, FileTime.fromMillis(now));
            } catch (IOException ignored) {
                // best-effort: the in-use set spares it in this pass either way
            }
        }
    }

    private static Path normal(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
