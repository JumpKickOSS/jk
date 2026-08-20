// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    public static long schedule(List<ModuleWorkCost> mods, int concurrency, boolean serial, boolean parallelTests) {
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

        // Phase-gated admission (JK-2210/2211): dependents wait on the upstream ARTIFACT point
        // (weight minus the run-tests slice — packaging no longer gates on tests), never on the
        // upstream's full plan. The slot itself stays occupied for the full weight.
        Map<Path, Long> artifactAt = new HashMap<>();
        // Two event kinds: an artifact landing (wakes admission, frees nothing) and a flight
        // finishing (frees the slot). Without artifact events a dependent could only start at
        // some unrelated module's finish, overestimating exactly the overlap this models.
        record Event(long at, Path dir, boolean finish) {}
        PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingLong(Event::at));
        long t = 0;
        long end = 0;
        int free = slots;
        int flying = 0;
        Set<Path> dirtyDirs = byDir.keySet();

        while (!remaining.isEmpty() || flying > 0) {
            while (free > 0 && !remaining.isEmpty()) {
                Path next = null;
                for (Path d : remaining) {
                    ModuleWorkCost m = byDir.get(d);
                    if (!prereqArtifactsReady(m, dirtyDirs, artifactAt, t)) continue;
                    next = d; // first ready in order
                    break;
                }
                if (next == null) break;
                remaining.remove(next);
                ModuleWorkCost m = byDir.get(next);
                long fin = t + Math.max(0, m.weight());
                long art = t + Math.max(0, m.weight() - Math.max(0, m.testWeight()));
                artifactAt.put(next, art);
                if (art < fin) events.add(new Event(art, next, false));
                events.add(new Event(fin, next, true));
                end = Math.max(end, fin);
                free--;
                flying++;
            }
            if (events.isEmpty()) {
                long extra = 0;
                for (Path d : remaining) extra += Math.max(0, byDir.get(d).weight());
                return t + extra;
            }
            Event e = events.poll();
            t = e.at();
            if (e.finish()) {
                free++;
                flying--;
            }
        }
        return end;
    }

    private static boolean prereqArtifactsReady(
            ModuleWorkCost m, Set<Path> dirtyDirs, Map<Path, Long> artifactAt, long now) {
        if (m.prereqs() == null) return true;
        for (Path p : m.prereqs()) {
            if (!dirtyDirs.contains(p)) continue; // clean prereq — already built
            Long at = artifactAt.get(p);
            if (at == null || at > now) return false;
        }
        return true;
    }

    /** Copy costs into a mutable list (defensive). */
    public static List<ModuleWorkCost> copy(List<ModuleWorkCost> mods) {
        if (mods == null || mods.isEmpty()) return List.of();
        return new ArrayList<>(mods);
    }
}
