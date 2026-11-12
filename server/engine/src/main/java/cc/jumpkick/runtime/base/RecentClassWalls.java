// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.host.KeyedLocks;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    private record Cached(long stamp, Map<String, Map<String, Long>> byModule) {}

    private static final ConcurrentHashMap<Key, Cached> CACHE = new ConcurrentHashMap<>();

    private RecentClassWalls() {}

    /** FQCN → wall-ms. Empty when this module has never recorded a class wall. */
    public static Map<String, Long> forModule(@Nullable Path moduleDir) {
        if (moduleDir == null) return Map.of();
        Map<String, Long> live = live(moduleDir);
        if (!live.isEmpty()) return live;
        try {
            Path module = moduleDir.toAbsolutePath().normalize();
            Path root = projectRoot(module);
            String rel = ModuleKeys.relative(module.toString(), root.toString());
            Map<String, Long> recent =
                    merged(ProjectBuilds.projectHome(root), root).get(rel);
            if (recent != null && !recent.isEmpty()) return Map.copyOf(recent);
            return BuildMetrics.classWallsForSession().forModule(module.toString());
        } catch (RuntimeException e) {
            Log.debug("RecentClassWalls: " + moduleDir, e);
            return Map.of();
        }
    }

    /** Drop the run-file cache. Tests that repoint the state dir call this. */
    public static void clear() {
        CACHE.clear();
        RUNS.clear();
    }

    private static Map<String, Long> live(Path moduleDir) {
        RunMeasures measures = RunMeasures.current();
        if (measures == null) return Map.of();
        Map<String, Long> direct = measures.classWalls(moduleDir.toString());
        if (!direct.isEmpty()) return direct;
        try {
            return measures.classWalls(moduleDir.toAbsolutePath().normalize().toString());
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
     * Every module's recent walls under {@code root}, scanned once per change of the runs directory.
     * Each build adds a run, so the first lookup of every build misses; every module of a prepare
     * asks at once, and before this was single-flight each of them scanned every retained run's
     * metrics — 223 regex scans of up to 50 files side by side, the CPU pool's first 0.3 s of a
     * Helidon build. One caller scans; the others wait for its answer.
     */
    private static Map<String, Map<String, Long>> merged(Path home, Path root) {
        Long stamp = stamp(home);
        Key key = new Key(home, root);
        if (stamp == null) return scan(home, root);
        Cached cached = CACHE.get(key);
        if (cached != null && cached.stamp == stamp) return cached.byModule;
        try (KeyedLocks.Held held = SCANNING.hold(key)) {
            cached = CACHE.get(key);
            if (cached != null && cached.stamp == stamp) return cached.byModule;
            Map<String, Map<String, Long>> walls = scan(home, root);
            CACHE.put(key, new Cached(stamp, walls));
            return walls;
        }
    }

    /** One lock per key: the single flight of {@link #merged}. */
    private static final KeyedLocks<Key> SCANNING = new KeyedLocks<>();

    /**
     * Each run's parsed walls by its metrics file, kept while the file's size and mtime hold: a run's
     * metrics are written once, so a new build parses its own run and reads the rest from here.
     */
    private record RunWalls(long mtime, long size, Map<String, Map<String, Long>> byModule) {}

    private static final ConcurrentHashMap<Path, RunWalls> RUNS = new ConcurrentHashMap<>();

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

    private static Map<String, Map<String, Long>> scan(Path home, Path root) {
        Map<String, Map<String, Long>> merged = new LinkedHashMap<>();
        Set<Path> live = new HashSet<>();
        for (Path run : ProjectBuilds.listRuns(home)) {
            Path checkout = ProjectBuilds.runCheckout(run);
            if (checkout != null && !ProjectBuilds.sameCheckout(checkout, root)) continue;
            Path metrics = run.resolve(ProjectBuilds.METRICS);
            Map<String, Map<String, Long>> walls = runWalls(metrics);
            if (walls == null) continue;
            live.add(metrics);
            // Newest run first: the first run to record a class is the one it keeps.
            for (Map.Entry<String, Map<String, Long>> module : walls.entrySet()) {
                Map<String, Long> into = merged.computeIfAbsent(module.getKey(), d -> new LinkedHashMap<>());
                for (Map.Entry<String, Long> wall : module.getValue().entrySet()) {
                    into.putIfAbsent(wall.getKey(), wall.getValue());
                }
            }
        }
        // A pruned run's parse goes with it.
        RUNS.keySet().removeIf(p -> p.startsWith(home) && !live.contains(p));
        return merged;
    }

    /** {@code metrics}' walls by module, parsed once per write; null when there is no such file. */
    private static @Nullable Map<String, Map<String, Long>> runWalls(Path metrics) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(metrics, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        if (!attrs.isRegularFile()) return null;
        long mtime = attrs.lastModifiedTime().toMillis();
        RunWalls known = RUNS.get(metrics);
        if (known != null && known.mtime() == mtime && known.size() == attrs.size()) return known.byModule();
        Map<String, Map<String, Long>> byModule = new LinkedHashMap<>();
        try {
            String text = Files.readString(metrics, StandardCharsets.UTF_8);
            PARSES.incrementAndGet();
            MetricsFile.scan(text, (section, key, value) -> {}, (dir, fqcn, ms) -> {
                if (!(ms > 0) || dir == null || fqcn == null || fqcn.isBlank()) return;
                byModule.computeIfAbsent(dir, d -> new LinkedHashMap<>()).putIfAbsent(fqcn, Math.round(ms));
            });
        } catch (IOException e) {
            Log.debug("RecentClassWalls: unreadable " + metrics, e);
            return null;
        }
        RUNS.put(metrics, new RunWalls(mtime, attrs.size(), byModule));
        return byModule;
    }
}
