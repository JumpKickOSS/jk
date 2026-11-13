// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.host.KeyedLocks;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The most recent recorded per-class test wall for a module.
 *
 * <p>An in-process buffer that has not been journaled yet wins. Otherwise each class takes its wall
 * from the newest run {@code metrics.toml} that recorded it (newest run first), and the harvested
 * ledger supplies the module only when no run file did. That is the history {@code auto} workers
 * read, so a run's walls are visible to the next one before harvest folds them.
 */
public final class RecentClassWalls {

    private record Key(Path home, Path root) {}

    /**
     * One project's merged walls as of {@code stamp}: every module's classes, each with the run it was
     * read from, and the run files merged, newest first. This is all a project keeps: a new run is
     * parsed and laid over it, and a pruned oldest run takes only the walls it alone supplied, so no
     * run's parse is held beside the merge (51 retained runs' parses were 1.5 MB on jk's tree,
     * JK-BSRC5).
     */
    private record Cached(long stamp, List<Source> sources, Map<String, Map<String, Wall>> byModule) {}

    /** A run's metrics file as it was when merged. */
    private record Source(Path metrics, long mtime, long size) {}

    /** A class's wall and the run file it came from. */
    private record Wall(long ms, Path from) {}

    private static final ConcurrentHashMap<Key, Cached> CACHE = new ConcurrentHashMap<>();

    private RecentClassWalls() {}

    /**
     * FQCN → wall-ms of the suite {@code step} runs, the tier the calling session selected. Empty
     * when this module has never recorded a class wall for it.
     */
    public static Map<String, Long> forModule(@Nullable Path moduleDir) {
        return forModule(moduleDir, SuiteSelection.current());
    }

    /** {@link #forModule(Path)} for the suite {@code step}. */
    public static Map<String, Long> forModule(@Nullable Path moduleDir, String step) {
        if (moduleDir == null) return Map.of();
        Map<String, Long> live = live(moduleDir, step);
        if (!live.isEmpty()) return live;
        try {
            Path module = moduleDir.toAbsolutePath().normalize();
            Path root = projectRoot(module);
            String rel = ModuleKeys.relative(module.toString(), root.toString()) + TaskNames.suiteScope(step);
            Map<String, Wall> recent =
                    merged(ProjectBuilds.projectHome(root), root).get(rel);
            if (recent != null && !recent.isEmpty()) {
                Map<String, Long> walls = new LinkedHashMap<>(recent.size() * 2);
                recent.forEach((fqcn, w) -> walls.put(fqcn, w.ms()));
                return Collections.unmodifiableMap(walls);
            }
            return BuildMetrics.classWallsForSession().forModule(module.toString(), step);
        } catch (RuntimeException e) {
            Log.debug("RecentClassWalls: " + moduleDir, e);
            return Map.of();
        }
    }

    /** Drop the run-file cache. Tests that repoint the state dir call this. */
    public static void clear() {
        CACHE.clear();
    }

    private static Map<String, Long> live(Path moduleDir, String step) {
        RunMeasures measures = RunMeasures.current();
        if (measures == null) return Map.of();
        Map<String, Long> direct = measures.classWalls(moduleDir.toString(), step);
        if (!direct.isEmpty()) return direct;
        try {
            return measures.classWalls(moduleDir.toAbsolutePath().normalize().toString(), step);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** The directory the journal records this module under: the workspace root, or the module itself. */
    static Path projectRoot(Path module) {
        try {
            return LockPaths.lockOwnerDir(module);
        } catch (RuntimeException e) {
            return module;
        }
    }

    /**
     * Every module's recent walls under {@code root}, brought up to date once per change of the runs
     * directory. Each build adds a run, so the first lookup of every build misses; every module of a
     * prepare asks at once, and before this was single-flight each of them scanned every retained
     * run's metrics — 223 regex scans of up to 50 files side by side, the CPU pool's first 0.3 s of a
     * Helidon build. One caller updates; the others wait for its answer.
     */
    private static Map<String, Map<String, Wall>> merged(Path home, Path root) {
        Long stamp = stamp(home);
        Key key = new Key(home, root);
        if (stamp == null) return scan(home, root, null).byModule();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.stamp == stamp) return cached.byModule;
        try (KeyedLocks.Held held = SCANNING.hold(key)) {
            cached = CACHE.get(key);
            if (cached != null && cached.stamp == stamp) return cached.byModule;
            Cached now = scan(home, root, cached);
            CACHE.put(key, new Cached(stamp, now.sources(), now.byModule()));
            return now.byModule();
        }
    }

    /** One lock per key: the single flight of {@link #merged}. */
    private static final KeyedLocks<Key> SCANNING = new KeyedLocks<>();

    /** How many metrics files {@link #scan} has parsed: what a test counts. */
    static final AtomicLong PARSES = new AtomicLong();

    /** Runs-directory mtime, or {@code null} when it cannot be read (then the scan is not cached). */
    private static @Nullable Long stamp(Path home) {
        try {
            Path runs = home.resolve(ProjectBuilds.RUNS);
            if (!Files.isDirectory(runs)) return 0L;
            return Files.getLastModifiedTime(runs).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The merge of {@code root}'s runs, newest first: each class keeps the wall of the newest run that
     * recorded it. From {@code before} when the runs only gained newer ones and lost their oldest,
     * which is what a build and run retention do: only the new runs are parsed, and only the walls
     * the pruned runs supplied are dropped (no older run is left to supply them). Any other change,
     * a run rewritten or one removed from the middle, merges every run again.
     */
    private static Cached scan(Path home, Path root, @Nullable Cached before) {
        List<Source> sources = new ArrayList<>();
        for (Path run : ProjectBuilds.listRuns(home)) {
            Path checkout = ProjectBuilds.runCheckout(run);
            if (checkout != null && !ProjectBuilds.sameCheckout(checkout, root)) continue;
            Source s = source(run.resolve(ProjectBuilds.METRICS));
            if (s != null) sources.add(s);
        }
        if (before != null) {
            Cached carried = carried(before, sources);
            if (carried != null) return carried;
        }
        Map<String, Map<String, Wall>> merged = new LinkedHashMap<>();
        List<Source> parsed = new ArrayList<>(sources.size());
        for (Source s : sources) {
            if (lay(merged, s, false)) parsed.add(s);
        }
        return new Cached(0, List.copyOf(parsed), merged);
    }

    /**
     * {@code before} carried to {@code now}: {@code now} is some new runs, then a run of {@code
     * before}'s own runs unchanged, with only its oldest missing. Null when it is anything else.
     */
    private static @Nullable Cached carried(Cached before, List<Source> now) {
        List<Source> old = before.sources();
        int kept = old.isEmpty() || now.isEmpty() ? -1 : now.indexOf(old.getFirst());
        if (kept < 0) return null;
        int keep = now.size() - kept;
        if (keep > old.size() || !old.subList(0, keep).equals(now.subList(kept, now.size()))) return null;
        Set<Path> pruned = new HashSet<>();
        for (Source s : old.subList(keep, old.size())) pruned.add(s.metrics());
        Map<String, Map<String, Wall>> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Wall>> module : before.byModule().entrySet()) {
            Map<String, Wall> walls = new LinkedHashMap<>();
            module.getValue().forEach((fqcn, w) -> {
                if (!pruned.contains(w.from())) walls.put(fqcn, w);
            });
            if (!walls.isEmpty()) merged.put(module.getKey(), walls);
        }
        List<Source> sources = new ArrayList<>(now.subList(kept, now.size()));
        // The new runs, oldest of them first, each over what came before it.
        for (int i = kept - 1; i >= 0; i--) {
            if (lay(merged, now.get(i), true)) sources.addFirst(now.get(i));
        }
        return new Cached(0, List.copyOf(sources), merged);
    }

    /** {@code metrics} as it is now; null when there is no such file. */
    private static @Nullable Source source(Path metrics) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(metrics, BasicFileAttributes.class);
            return attrs.isRegularFile()
                    ? new Source(metrics, attrs.lastModifiedTime().toMillis(), attrs.size())
                    : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Lays {@code s}'s walls into {@code merged}: over what is there when {@code newer}, else only
     * where nothing is. False when the file cannot be read.
     */
    private static boolean lay(Map<String, Map<String, Wall>> merged, Source s, boolean newer) {
        String text;
        try {
            text = Files.readString(s.metrics(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.debug("RecentClassWalls: unreadable " + s.metrics(), e);
            return false;
        }
        PARSES.incrementAndGet();
        Map<String, Map<String, Long>> run = new LinkedHashMap<>();
        MetricsFile.scan(text, (section, key, value) -> {}, (dir, fqcn, ms) -> {
            if (!(ms > 0) || dir == null || fqcn == null || fqcn.isBlank()) return;
            run.computeIfAbsent(dir, d -> new LinkedHashMap<>()).putIfAbsent(fqcn, Math.round(ms));
        });
        run.forEach((dir, walls) -> {
            Map<String, Wall> into = merged.computeIfAbsent(dir, d -> new LinkedHashMap<>());
            walls.forEach((fqcn, ms) -> {
                Wall w = new Wall(ms, s.metrics());
                if (newer) into.put(fqcn, w);
                else into.putIfAbsent(fqcn, w);
            });
        });
        return true;
    }
}
