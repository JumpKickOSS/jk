// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.ModuleKeys;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.builds.ProjectStore;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.StatIdentity;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The most recent recorded per-class test wall for a module.
 *
 * <p>An in-process buffer that has not been journaled yet wins. Otherwise each class takes the wall
 * it ran in the newest run that ran it: its {@code last} in the project store ({@link
 * ProjectStore#CLASSES}). That is the history {@code auto} workers read, so a run's walls are
 * visible to the next one.
 */
public final class RecentClassWalls {

    /** One project's suites as last read: suite dir (as the store spells it) → class → last wall ms. */
    private static final StampedMemo<Path, StatIdentity, Map<String, Map<String, Long>>> MEMO = StampedMemo.bounded(8);

    /** Serializes misses: every module of a prepare asks at once, and one read serves them all. */
    private static final Object READING = new Object();

    /** How many stores {@link #read} has parsed: what a test counts. */
    static final AtomicLong PARSES = new AtomicLong();

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
            String suite = ModuleKeys.relative(module.toString(), root.toString()) + TaskNames.suiteScope(step);
            return walls(ProjectBuilds.projectHome(root)).getOrDefault(suite, Map.of());
        } catch (RuntimeException e) {
            Log.debug("RecentClassWalls: " + moduleDir, e);
            return Map.of();
        }
    }

    /** Drop the memo. Tests that repoint the state dir call this. */
    public static void clear() {
        MEMO.clear();
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

    private static Map<String, Map<String, Long>> walls(Path home) {
        StatIdentity stamp;
        try {
            stamp = StatIdentity.of(ProjectStore.file(home));
        } catch (IOException absent) {
            return Map.of();
        }
        synchronized (READING) {
            return MEMO.get(home, stamp, () -> read(home));
        }
    }

    private static Map<String, Map<String, Long>> read(Path home) {
        PARSES.incrementAndGet();
        Map<String, Map<String, Long>> out = new LinkedHashMap<>();
        ProjectStore.scan(home, null, (suite, fqcn, wall) -> {
            if (wall.last() > 0)
                out.computeIfAbsent(suite, s -> new LinkedHashMap<>()).put(fqcn, Math.round(wall.last()));
        });
        out.replaceAll((suite, walls) -> Collections.unmodifiableMap(walls));
        return Collections.unmodifiableMap(out);
    }
}
