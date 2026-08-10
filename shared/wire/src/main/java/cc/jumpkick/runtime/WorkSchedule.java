// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Wall-time schedule model for workspace module costs — shared by ETA seed, residual {@code R(t)},
 * and (via the same admission rules) the live {@code WorkspaceScheduler}.
 *
 * <p>Admission policy matches the bounded live scheduler: among ready modules, admit the
 * <strong>first in declaration / topo order</strong> (not longest-first), with at most
 * {@code concurrency} in flight, and a module ready only when every dirty prereq has fully
 * finished.
 */
public final class WorkSchedule {

    private WorkSchedule() {}

    /**
     * Schedule wall cost in the same units as {@link ModuleWorkCost#weight()} (multiply by
     * ms-per-weight externally when those units are not already milliseconds).
     */
    public static long schedule(
            List<ModuleWorkCost> mods, int concurrency, boolean serial, boolean parallelTests) {
        if (mods == null || mods.isEmpty()) return 0;
        long serialSum = 0;
        long testSum = 0;
        for (ModuleWorkCost m : mods) {
            serialSum += m.weight();
            testSum += m.testWeight();
        }
        if (serial || concurrency <= 1) return serialSum;
        long scheduled = listSchedule(mods, Math.max(1, concurrency));
        long testFloor = parallelTests ? 0 : testSum;
        return Math.max(scheduled, testFloor);
    }

    /**
     * Rolling-window list schedule: first-ready admission (list order), full prereq completion,
     * at most {@code concurrency} in flight.
     */
    static long listSchedule(List<ModuleWorkCost> mods, int concurrency) {
        if (mods == null || mods.isEmpty()) return 0;
        int slots = Math.max(1, concurrency);
        Map<Path, ModuleWorkCost> byDir = new HashMap<>();
        // LinkedHashSet preserves the caller’s order — same as live WorkspaceScheduler’s unit list.
        Set<Path> remaining = new LinkedHashSet<>();
        for (ModuleWorkCost m : mods) {
            if (m == null || m.dir() == null) continue;
            byDir.put(m.dir(), m);
            remaining.add(m.dir());
        }
        if (byDir.isEmpty()) return 0;

        Map<Path, Long> doneAt = new HashMap<>();
        record Flight(long finish, Path dir) {}
        PriorityQueue<Flight> inFlight = new PriorityQueue<>(java.util.Comparator.comparingLong(Flight::finish));
        long t = 0;
        int free = slots;
        Set<Path> dirtyDirs = byDir.keySet();

        while (!remaining.isEmpty() || !inFlight.isEmpty()) {
            while (free > 0 && !remaining.isEmpty()) {
                Path next = null;
                for (Path d : remaining) {
                    ModuleWorkCost m = byDir.get(d);
                    if (!prereqsDone(m, dirtyDirs, doneAt)) continue;
                    next = d; // first ready in order
                    break;
                }
                if (next == null) break;
                remaining.remove(next);
                long fin = t + Math.max(0, byDir.get(next).weight());
                inFlight.add(new Flight(fin, next));
                free--;
            }
            if (inFlight.isEmpty()) {
                long extra = 0;
                for (Path d : remaining) extra += Math.max(0, byDir.get(d).weight());
                return t + extra;
            }
            Flight done = inFlight.poll();
            t = done.finish();
            doneAt.put(done.dir(), t);
            free++;
        }
        return t;
    }

    private static boolean prereqsDone(ModuleWorkCost m, Set<Path> dirtyDirs, Map<Path, Long> doneAt) {
        if (m.prereqs() == null) return true;
        for (Path p : m.prereqs()) {
            if (!dirtyDirs.contains(p)) continue; // clean prereq — already built
            if (!doneAt.containsKey(p)) return false;
        }
        return true;
    }

    /** Copy costs into a mutable list (defensive). */
    public static List<ModuleWorkCost> copy(List<ModuleWorkCost> mods) {
        if (mods == null || mods.isEmpty()) return List.of();
        return new ArrayList<>(mods);
    }
}
