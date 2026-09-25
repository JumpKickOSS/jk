// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.MetricsFile;
import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    }

    private static Map<String, Long> live(Path moduleDir) {
        Map<String, Long> direct = TestClassWalls.get(moduleDir.toString());
        if (!direct.isEmpty()) return direct;
        try {
            Path abs = moduleDir.toAbsolutePath().normalize();
            Map<String, Long> normalized = TestClassWalls.get(abs.toString());
            if (!normalized.isEmpty()) return normalized;
            return TestClassWalls.get(abs.toAbsolutePath().toString());
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

    private static Map<String, Map<String, Long>> merged(Path home, Path root) {
        Long stamp = stamp(home);
        Key key = new Key(home, root);
        if (stamp != null) {
            Cached cached = CACHE.get(key);
            if (cached != null && cached.stamp == stamp) return cached.byModule;
        }
        Map<String, Map<String, Long>> walls = scan(home, root);
        if (stamp != null) CACHE.put(key, new Cached(stamp, walls));
        return walls;
    }

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
        for (Path run : ProjectBuilds.listRuns(home)) {
            Path checkout = ProjectBuilds.runCheckout(run);
            if (checkout != null && !ProjectBuilds.sameCheckout(checkout, root)) continue;
            Path metrics = run.resolve(ProjectBuilds.METRICS);
            if (!Files.isRegularFile(metrics)) continue;
            try {
                String text = Files.readString(metrics, StandardCharsets.UTF_8);
                MetricsFile.scan(text, (section, key, value) -> {}, (dir, fqcn, ms) -> {
                    if (!(ms > 0) || dir == null || fqcn == null || fqcn.isBlank()) return;
                    merged.computeIfAbsent(dir, d -> new LinkedHashMap<>()).putIfAbsent(fqcn, Math.round(ms));
                });
            } catch (IOException e) {
                Log.debug("RecentClassWalls: unreadable " + metrics, e);
            }
        }
        return merged;
    }
}
