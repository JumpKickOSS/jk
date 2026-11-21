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
import java.lang.management.ManagementFactory;
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
 * file's name carries the jk version that recorded it. Starting a process with a cache touches it
 * (a worker in {@link JobWorkers}, the engine in its spawner), so a cache's mtime says when it was
 * last mapped.
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
 * maintenance cycle. Never deleted ({@link #inUse}): a cache a live worker of this engine was
 * started with, from the engine's own lease ledger ({@link WorkerLeases.Ledger#mappedCaches}, on
 * every OS); the one this engine maps; one it is recording; and the engine cache for its own jar.
 * Where the OS shows other processes' arguments (macOS, Linux; never Windows) a cache another
 * process names in {@code -XX:AOTCache=} counts too. Another engine of the same home is otherwise
 * covered by mtime: it touched what it maps when it started each process, and its own sweeps touch
 * what it still maps, so the 12 h rule spares them; {@code jk storage clean} and the emergency pass
 * may delete them on Windows.
 *
 * <p>Deleting a cache a live process maps is harmless on every OS: the process keeps its mapping
 * (POSIX unlinks under it; the JVM opens its cache with delete sharing on Windows), and the next
 * fresh worker records the cache again, about 4 s and 20-75 MiB. So the guarantee is that jk does
 * not delete what its own engine's live workers map, nor, on a snapshot, what was mapped in the
 * last 12 h; not that a mapped file is never deleted.
 *
 * <p>A trainer temp ({@code <cache>.tmp-<engine pid>} and its {@code .config}) goes once no
 * recording can still be writing it ({@link #staleTemp}).
 */
public final class StartupCaches {

    private StartupCaches() {}

    private static volatile @Nullable String pinnedVersion;

    /** Under a snapshot jk, a cache not mapped for this long goes. */
    static final Duration SNAPSHOT_UNUSED = Duration.ofHours(12);

    /** A trainer temp older than this goes whoever named it: no recording runs a day. */
    static final Duration TMP_TTL = Duration.ofHours(24);

    /**
     * A temp whose engine is gone goes once untouched this long: a trainer outlives a killed engine
     * by at most its own run, which the engine caps at two minutes ({@link
     * WorkerAotCache#trainingTimeoutMillis}).
     */
    static final Duration ORPHAN_GRACE = Duration.ofMinutes(5);

    /** {@code <cache>.aot.tmp-<pid>}, optionally {@code .config}: the engine pid that named it. */
    private static final Pattern TEMP_NAME = Pattern.compile("(?<cache>.+\\.aot)\\.tmp-(?<pid>\\d+)(?:\\..*)?");

    /** Whether a trainer temp may still be written: its engine's own recording, or a live engine's. */
    @FunctionalInterface
    interface Recorders {
        /** {@code pid} named the temp of {@code cacheName}, last written at {@code mtimeMillis}. */
        boolean mayWrite(long pid, String cacheName, long mtimeMillis);
    }

    /** This engine: the caches it records now; another pid: a live process that started before the write. */
    static final Recorders RECORDERS = (pid, cacheName, mtimeMillis) -> pid
                    == ProcessHandle.current().pid()
            ? WorkerAotCache.training().stream().anyMatch(p -> cacheName.equals(String.valueOf(p.getFileName())))
            : startedBefore(pid, mtimeMillis);

    /** Every other process's command line, where the OS shows it (macOS, Linux); none on Windows. */
    @FunctionalInterface
    interface CommandLines {
        List<List<String>> read();
    }

    static final CommandLines OTHER_PROCESSES = () -> ProcessHandle.allProcesses()
            .map(h -> h.info().arguments().map(args -> List.<String>of(args)).orElse(List.of()))
            .filter(args -> !args.isEmpty())
            .toList();

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
            Set<Path> held = inUse(WorkerLeases.engine(), OTHER_PROCESSES);
            touch(held);
            String running = running();
            Report r = sweep(JkDirs.state(), running, held, Clock.SYSTEM.millis(), DELETE, RECORDERS);
            if (r.deleted() > 0) {
                log.accept("jk engine: removed " + r.deleted() + " startup cache file(s) (" + (r.freedBytes() >> 20)
                        + " MiB)"
                        + (Versions.isSnapshot(running)
                                ? " of another version or unused for " + SNAPSHOT_UNUSED.toHours() + " h"
                                : " of another version"));
            }
        } catch (RuntimeException e) {
            Log.debug("StartupCaches.sweep: best-effort", e);
        }
    }

    /** The jk version whose caches this engine names and keeps: this build's, unless a test pins one. */
    static String running() {
        String pinned = pinnedVersion;
        return pinned != null ? pinned : JkVersion.VERSION;
    }

    /** Test seam: name and sweep caches as jk {@code version} would; null goes back to this build's. */
    static void pinVersionForTests(@Nullable String version) {
        pinnedVersion = version;
    }

    /**
     * Delete, under {@code state}, every cache another version recorded; under a snapshot {@code
     * running} also every one unmapped for {@link #SNAPSHOT_UNUSED}; and every trainer temp no
     * recording can still write ({@link #staleTemp}). {@code inUse} is never touched.
     */
    static Report sweep(
            Path state, String running, Set<Path> inUse, long nowMillis, Deleter deleter, Recorders recorders) {
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
                    } else if (staleTemp(name, attrs.lastModifiedTime().toMillis(), nowMillis, recorders)) {
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
     * Whether {@code name} is a trainer temp no recording can still write: one a day old whoever
     * named it; this engine's when it records that cache no more (a failed recording that could
     * not delete its own); another engine's once that pid is gone, or is a later process that
     * reused it, and {@link #ORPHAN_GRACE} has passed since the last write. A reused pid can only
     * keep a temp (until the day is up), never take a live recording's.
     */
    static boolean staleTemp(String name, long mtimeMillis, long nowMillis, Recorders recorders) {
        Matcher m = TEMP_NAME.matcher(name);
        if (!m.matches()) return false;
        long age = nowMillis - mtimeMillis;
        if (age > TMP_TTL.toMillis()) return true;
        long pid;
        try {
            pid = Long.parseLong(m.group("pid"));
        } catch (NumberFormatException tooLong) {
            return false;
        }
        if (recorders.mayWrite(pid, m.group("cache"), mtimeMillis)) return false;
        return pid == ProcessHandle.current().pid() || age > ORPHAN_GRACE.toMillis();
    }

    /** Whether {@code pid} is a live process that started no later than {@code mtimeMillis}. */
    static boolean startedBefore(long pid, long mtimeMillis) {
        return ProcessHandle.of(pid)
                .filter(ProcessHandle::isAlive)
                .map(h -> h.info()
                        .startInstant()
                        .map(start -> start.toEpochMilli() <= mtimeMillis)
                        .orElse(true))
                .orElse(false);
    }

    /**
     * The volume holding the state directory is critical ({@link DiskGuard}): delete caches not in
     * use, stale temps first and then least recently mapped, until the volume is back over the line
     * or none are left. Called by the engine's disk guard after its store and cache passes.
     */
    public static Report reclaimUnderPressure(Consumer<String> log) {
        try {
            Path state = JkDirs.state();
            long goal = shortfall(JkCacheConfig.DiskSpace.probe(state));
            if (goal <= 0) return Report.NONE;
            Report r = reclaim(
                    state,
                    inUse(WorkerLeases.engine(), OTHER_PROCESSES),
                    goal,
                    false,
                    DELETE,
                    RECORDERS,
                    Clock.SYSTEM.millis());
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

    /** {@code jk storage clean}: every cache not in use, whatever its age or version, and every stale temp. */
    public static Report clear(boolean dryRun) {
        return clear(dryRun, OTHER_PROCESSES);
    }

    static Report clear(boolean dryRun, CommandLines others) {
        return reclaim(
                JkDirs.state(),
                inUse(WorkerLeases.engine(), others),
                Long.MAX_VALUE,
                dryRun,
                DELETE,
                RECORDERS,
                Clock.SYSTEM.millis());
    }

    /** Bytes {@code space} lacks to be off {@link DiskGuard}'s critical line; 0 when it is not critical. */
    static long shortfall(JkCacheConfig.@Nullable DiskSpace space) {
        if (space == null || !DiskGuard.critical(space)) return 0;
        return Math.max(1, DiskGuard.criticalLine(space.totalBytes()) - space.freeBytes());
    }

    /**
     * Stale trainer temps, then caches under {@code state} not in {@code inUse}, oldest mtime first,
     * until {@code goalBytes} are freed.
     */
    static Report reclaim(
            Path state,
            Set<Path> inUse,
            long goalBytes,
            boolean dryRun,
            Deleter deleter,
            Recorders recorders,
            long nowMillis) {
        List<Entry> candidates = new ArrayList<>();
        for (Path dir : dirs(state)) {
            try {
                PathUtil.forEachChild(dir, (p, attrs) -> {
                    if (!attrs.isRegularFile()) return true;
                    String name = p.getFileName().toString();
                    long mtime = attrs.lastModifiedTime().toMillis();
                    if (name.endsWith(".aot") && !inUse.contains(normal(p))) {
                        candidates.add(entry(dir, p, attrs.lastModifiedTime(), attrs.size()));
                    } else if (staleTemp(name, mtime, nowMillis, recorders)) {
                        candidates.add(new Entry(p, null, Long.MIN_VALUE, attrs.size()));
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
                // A file the OS will not delete now (a virus scanner holding it, a permission): the next pass tries
                // again.
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
     * What must stay: the caches live workers of this engine were started with ({@code workers},
     * the engine's lease ledger), the one this engine maps (its own JVM flags), those it is
     * recording, the engine cache for its own jar and JDK (mapped by the next engine of this
     * install even when this one started before it was recorded), and any cache {@code others}
     * names in {@code -XX:AOTCache=} (another engine of the home and its workers, where the OS
     * shows their arguments).
     */
    static Set<Path> inUse(WorkerLeases.Ledger workers, CommandLines others) {
        Set<Path> held = new HashSet<>();
        for (Path p : workers.mappedCaches()) held.add(normal(p));
        held.addAll(mappedBy(ManagementFactory.getRuntimeMXBean().getInputArguments()));
        for (List<String> args : others.read()) held.addAll(mappedBy(args));
        for (Path p : WorkerAotCache.training()) held.add(normal(p));
        Path own = ownEngineCache();
        if (own != null) held.add(normal(own));
        return held;
    }

    /**
     * The startup caches of this home {@code command} maps, each touched so its mtime says it was
     * mapped now: what {@link JobWorkers} lists on a worker's lease as it starts it. A cache outside
     * {@code <state>/aot} and {@code <state>/engine-aot} (an application's own, under its target)
     * is neither listed nor touched.
     */
    static Set<Path> mapping(List<String> command) {
        Set<Path> mapped = mappedBy(command);
        if (mapped.isEmpty()) return mapped;
        List<Path> ours =
                dirs(JkDirs.state()).stream().map(StartupCaches::normal).toList();
        Set<Path> out = new HashSet<>();
        for (Path p : mapped) {
            if (ours.contains(p.getParent())) out.add(p);
        }
        touch(out);
        return out;
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

    /** Starting a process with a cache touches it; a sweep says so again for the ones still mapped. */
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
