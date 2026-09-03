// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Wall-time schedule model for workspace module costs — shared by ETA seed, residual {@code R(t)},
 * and (via the same admission rules) the live {@code WorkspaceScheduler}.
 *
 * <p>Admission policy matches the bounded live scheduler: among ready modules, admit the
 * <strong>first in declaration / topo order</strong> (not longest-first), with at most
 * {@code concurrency} in flight, and a module ready when every dirty prereq has published its
 * artifacts — not when every prereq has fully finished.
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
            // Even at -j1 a module's own wall is its longer branch: -j bounds how many MODULES run
            // at once, it does not put a module's suite and its packaging tail back in series.
            long tw = Math.max(0, m.testWeight());
            // Even at -j1 a module's own wall is prefix + its longer branch: -j bounds how many
            // MODULES run at once, it does not put a module's suite and its packaging tail back in
            // series. With no tail this is exactly m.weight(), as it was.
            serialSum += moduleWall(m);
            testSum += tw;
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

        // Phase-gated admission: dependents wait on the upstream ARTIFACT point (its weight minus
        // the run-tests slice — packaging does not gate on tests), never on the upstream's full
        // plan. The slot stays occupied until the module's own finish, which is now its longer
        // branch rather than the sum of its steps (see below).
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
                    ModuleWorkCost m = Objects.requireNonNull(byDir.get(d));
                    if (!prereqArtifactsReady(m, dirtyDirs, artifactAt, t)) continue;
                    next = d; // first ready in order
                    break;
                }
                if (next == null) break;
                remaining.remove(next);
                ModuleWorkCost m = Objects.requireNonNull(byDir.get(next));
                // A module's own wall is its LONGER branch, not the sum of its steps. Inside a
                // plan, `run-tests` is a leaf and packaging requires only the jar, so the suite
                // and the packaging tail (assembly, native-image, sources) run at the same time —
                // BuildPlan admits first-ready. Pricing a module with a 10 s suite and a 37 s
                // native-image at 47 s says the executor serializes them, which is the bug that
                // executor no longer has.
                long fin = t + moduleWall(m);
                // Dependents need the jar, which lands with the compile prefix — before either
                // branch. Without a known tail this degrades to "everything but the suite", the
                // pre-tail behaviour.
                long art = t + Math.max(0, m.weight() - Math.max(0, m.testWeight()) - Math.max(0, m.tailWeight()));
                artifactAt.put(next, art);
                if (art < fin) events.add(new Event(art, next, false));
                events.add(new Event(fin, next, true));
                end = Math.max(end, fin);
                free--;
                flying++;
            }
            if (events.isEmpty()) {
                long extra = 0;
                for (Path d : remaining)
                    extra += Math.max(0, Objects.requireNonNull(byDir.get(d)).weight());
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

    /**
     * One module's own wall: the compile prefix both branches share, plus whichever branch is
     * longer. {@code run-tests} is a plan leaf and the packaging tail requires only the jar, so
     * {@code BuildPlan} admits them together — pricing a module with a 10 s suite and a 37 s
     * native-image at 47 s would claim a serialization the executor no longer has.
     *
     * <p>A module with no tail prices at exactly {@code weight()}, so this is a no-op for the
     * ordinary compile-test-package module and only bites where a real tail exists. The tail weight
     * is the longest packaging tail, not their sum — assembly, minified, native-image and sources all
     * hang off the jar and run together. The prefix still carries {@code compile-test}, which the
     * tail branch never waits on: a known over-price, small next to a suite.
     */
    public static long moduleWall(ModuleWorkCost m) {
        long test = Math.max(0, m.testWeight());
        long tail = Math.max(0, m.tailWeight());
        long prefix = Math.max(0, Math.max(0, m.weight()) - test - tail);
        return prefix + Math.max(test, tail);
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
