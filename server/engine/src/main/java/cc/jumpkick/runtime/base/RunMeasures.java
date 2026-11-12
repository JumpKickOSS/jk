// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.Request;
import cc.jumpkick.util.DirKeys;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/**
 * What one run measured for its {@code metrics.toml} beyond its step records: each module's
 * test-class walls and the runner count its suite ran with, and whether the run paid a compiler
 * worker's warm-up. Owned by the run's accumulator, reached through the request under {@link #SLOT},
 * and taken whole once when the run is journaled, whether or not history keeps it.
 *
 * <p>A runner count travels with the walls because a stored suite wall predicts nothing without the
 * concurrency it was produced at. A run that started a compiler worker keeps its compile walls out
 * of the metrics: they carry the worker's JVM and JIT warm-up (the early modules of a chain compile
 * 3–7x slower), and the warm-up is priced once, as the cold-worker shape's learned correction.
 *
 * <p>Modules are keyed by {@link DirKeys#key}, so the recorder's spelling of a module and the
 * journal's ({@code C:\ws} and {@code C:/ws} on Windows) are one module.
 */
public final class RunMeasures {

    /** The run's measures; a request without them measures nothing. */
    public static final Request.Slot<RunMeasures> SLOT = new Request.Slot<>("run-measures");

    private final ConcurrentHashMap<String, Map<String, Long>> classWalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> runners = new ConcurrentHashMap<>();
    private final AtomicBoolean coldWorker = new AtomicBoolean();

    /** The measures of the request bound now, or {@code null} off one. */
    public static @Nullable RunMeasures current() {
        Request request = Request.current();
        return request == null ? null : request.get(SLOT);
    }

    /** {@code moduleDir}'s suite ran these classes for these walls (FQCN → ms). */
    public void classWalls(String moduleDir, Map<String, Long> walls) {
        if (moduleDir == null || moduleDir.isBlank() || walls == null || walls.isEmpty()) return;
        Map<String, Long> copy = new LinkedHashMap<>();
        walls.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null && v > 0) copy.put(k, v);
        });
        if (!copy.isEmpty()) classWalls.put(key(moduleDir), Map.copyOf(copy));
    }

    /** {@code moduleDir}'s class walls this run, kept; empty when its suite has not run. */
    public Map<String, Long> classWalls(@Nullable String moduleDir) {
        if (moduleDir == null) return Map.of();
        return classWalls.getOrDefault(key(moduleDir), Map.of());
    }

    /** {@code moduleDir}'s suite ran on {@code count} runner JVMs. */
    public void runners(String moduleDir, int count) {
        if (moduleDir == null || moduleDir.isBlank() || count <= 0) return;
        runners.put(key(moduleDir), count);
    }

    /** A compiler worker was started for one of this run's compiles: the run pays its warm-up. */
    public void startedWorker() {
        coldWorker.set(true);
    }

    /** Record this run's compile walls after all: there is no warm history to protect. */
    public void keepCompileWalls() {
        coldWorker.set(false);
    }

    /** Whether this run started a compiler worker and keeps its compile walls out. */
    public boolean coldWorker() {
        return coldWorker.get();
    }

    /** Everything measured so far, moved into a new instance: these measures are left empty. */
    public RunMeasures take() {
        RunMeasures taken = new RunMeasures();
        for (String k : classWalls.keySet()) {
            Map<String, Long> v = classWalls.remove(k);
            if (v != null) taken.classWalls.put(k, v);
        }
        for (String k : runners.keySet()) {
            Integer v = runners.remove(k);
            if (v != null) taken.runners.put(k, v);
        }
        taken.coldWorker.set(coldWorker.getAndSet(false));
        return taken;
    }

    /** {@code moduleDir}'s class walls, removed; empty when none or already taken. */
    public Map<String, Long> takeClassWalls(@Nullable String moduleDir) {
        if (moduleDir == null) return Map.of();
        Map<String, Long> walls = classWalls.remove(key(moduleDir));
        return walls == null ? Map.of() : walls;
    }

    /** {@code moduleDir}'s runner count, removed; {@code 0} when none or already taken. */
    public int takeRunners(@Nullable String moduleDir) {
        if (moduleDir == null) return 0;
        Integer count = runners.remove(key(moduleDir));
        return count == null ? 0 : count;
    }

    /** True when nothing is measured. */
    public boolean isEmpty() {
        return classWalls.isEmpty() && runners.isEmpty() && !coldWorker.get();
    }

    private static String key(String moduleDir) {
        return Objects.requireNonNullElse(DirKeys.key(moduleDir), moduleDir);
    }
}
