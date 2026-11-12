// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Wall-time schedule model for workspace module costs — shared by ETA seed, residual {@code R(t)},
 * and (via the same admission rules) the live {@code WorkspaceScheduler}.
 *
 * <p>Admission policy matches the bounded live scheduler: among ready modules, admit the
 * <strong>first in declaration / topo order</strong> (not longest-first), with at most
 * {@code concurrency} in flight, and a module ready when every dirty prereq has published its
 * classes tree ({@link ModuleWorkCost#artifactPoint()}) — not when every prereq has fully finished,
 * and not when it has packaged.
 */
public final class WorkSchedule {

    private WorkSchedule() {}

    /**
     * Schedule wall cost in the same units as {@link ModuleWorkCost#weight()} (multiply by
     * ms-per-weight externally when those units are not already milliseconds).
     *
     * <p>{@code concurrency} is the executor's jobs budget as well as its module cap: the two are
     * one number on the live side, and a suite's runner share is that budget over the modules in
     * flight when the suite dispatches.
     */
    public static long schedule(List<ModuleWorkCost> mods, int concurrency, boolean serial, boolean parallelTests) {
        if (mods == null || mods.isEmpty()) return 0;
        long serialSum = 0;
        long testSum = 0;
        for (ModuleWorkCost m : mods) {
            // Even at -j1 a module's own wall is prefix + its longer branch: -j bounds how many
            // MODULES run at once, it does not put a module's suite and its packaging tail back in
            // series. Alone on the machine the suite gets the whole budget.
            long test = m.suiteAt(shareAt(concurrency, 1, m));
            serialSum += m.prefix() + Math.max(test, m.tailWeight());
            testSum += test;
        }
        if (serial || concurrency <= 1) return serialSum;
        long scheduled = listSchedule(mods, Math.max(1, concurrency));
        long testFloor = parallelTests ? 0 : testSum;
        return Math.max(scheduled, testFloor);
    }

    /**
     * Rolling-window list schedule: first-ready admission (list order), every dirty prereq past
     * its artifact point, at most {@code concurrency} in flight.
     *
     * <p>Linear in the graph up to the heaps' logarithm. The engine re-prices the residual schedule
     * on every module progress event under the build's lock, so a schedule that rescanned the
     * remaining list for each admission was cubic over a build: on a 223-module workspace it was a
     * third of the engine's samples and held module threads on that lock for seconds. A module's
     * unready dirty prereqs are counted instead, a prereq is ready once the clock reaches its
     * artifact point, and the ready modules are kept in list order, which picks exactly the module
     * the scan picked.
     */
    static long listSchedule(List<ModuleWorkCost> mods, int concurrency) {
        return listSchedule(mods, concurrency, null);
    }

    /**
     * Per module, the modules in flight when its suite dispatches in {@code mods}' schedule (itself
     * counted): what the executor divides the jobs budget by to pick the suite's test JVMs
     * ({@code TestWorkers.liveShare}). A module missing from the map never dispatched.
     */
    public static Map<Path, Integer> inFlightAtDispatch(List<ModuleWorkCost> mods, int concurrency) {
        Map<Path, Integer> inFlight = new HashMap<>();
        if (mods != null && !mods.isEmpty()) listSchedule(mods, Math.max(1, concurrency), inFlight);
        return inFlight;
    }

    private static long listSchedule(
            List<ModuleWorkCost> mods, int concurrency, @Nullable Map<Path, Integer> inFlightAtDispatch) {
        if (mods == null || mods.isEmpty()) return 0;
        int slots = Math.max(1, concurrency);
        // Caller order, first occurrence — same as live WorkspaceScheduler's unit list; a dir
        // listed twice is priced by its last cost.
        Map<Path, Integer> index = new LinkedHashMap<>();
        Map<Path, ModuleWorkCost> byDir = new HashMap<>();
        for (ModuleWorkCost m : mods) {
            if (m == null || m.dir() == null) continue;
            byDir.put(m.dir(), m);
            index.putIfAbsent(m.dir(), index.size());
        }
        int n = index.size();
        if (n == 0) return 0;
        ModuleWorkCost[] cost = new ModuleWorkCost[n];
        for (Map.Entry<Path, Integer> e : index.entrySet())
            cost[e.getValue()] = Objects.requireNonNull(byDir.get(e.getKey()));
        // A prereq outside the schedule is clean — already built — and waits on nothing.
        int[] unready = new int[n];
        List<List<Integer>> waiters = new ArrayList<>(n);
        for (int i = 0; i < n; i++) waiters.add(new ArrayList<>(2));
        for (int i = 0; i < n; i++) {
            Set<Path> prereqs = cost[i].prereqs();
            if (prereqs == null) continue;
            for (Path p : prereqs) {
                Integer j = index.get(p);
                if (j == null) continue;
                unready[i]++;
                waiters.get(j).add(i);
            }
        }
        BitSet ready = new BitSet(n);
        for (int i = 0; i < n; i++) if (unready[i] == 0) ready.set(i);
        boolean[] admitted = new boolean[n];
        // Artifact points not yet reached by the clock, soonest first.
        PriorityQueue<long[]> landing = new PriorityQueue<>(Comparator.comparingLong((long[] l) -> l[0]));

        // Phase-gated admission: dependents wait on the upstream's classes publish (its compile
        // prefix, or the whole non-suite non-tail prefix when the cost did not split it), never
        // on the upstream's full plan. The slot stays occupied until the module's own finish,
        // which is its longer branch rather than the sum of its steps (see below).
        // Three event kinds: an artifact landing (wakes admission, frees nothing), a suite
        // dispatching (prices the suite at the share the machine has to give right then), and a
        // flight finishing (frees the slot). Without artifact events a dependent could only start
        // at some unrelated module's finish, overestimating exactly the overlap this models.
        record Event(long at, int module, Kind kind) {}
        // Same instant: a finish frees its slot and leaves the in-flight count before a suite
        // reads its share, and an artifact admits its dependents before that read too — the
        // executor counts a module admitted at that moment as running.
        PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingLong(Event::at)
                .thenComparingInt(e -> e.kind().ordinal()));
        long t = 0;
        long end = 0;
        int free = slots;
        int flying = 0;
        int left = n;

        while (left > 0 || flying > 0) {
            while (free > 0) {
                int next = ready.nextSetBit(0); // first ready in order
                if (next < 0) break;
                ready.clear(next);
                admitted[next] = true;
                left--;
                ModuleWorkCost m = cost[next];
                // A module's own wall is its LONGER branch, not the sum of its steps. Inside a
                // plan, `run-tests` is a leaf and packaging requires only the jar, so the suite
                // and the packaging tail (assembly, native-image, sources) run at the same time —
                // BuildPlan admits first-ready. Pricing a module with a 10 s suite and a 37 s
                // native-image at 47 s says the executor serializes them, which is the bug that
                // executor no longer has.
                //
                // The suite is priced when the module reaches it, not now: the executor reads the
                // runner share when the suite dispatches, and on a wide build the modules that
                // reach their suites last find most of the machine free.
                long dispatch = t + m.prefix();
                // Dependents compile against the classes tree, which lands at the gate — before
                // test compile, packaging and either branch.
                long art = t + m.artifactPoint();
                if (art <= t) land(next, waiters, unready, ready);
                else landing.add(new long[] {art, next});
                // Always an event, even when the gate and the dispatch coincide: the dependents it
                // admits are in flight before this module's suite reads its share.
                events.add(new Event(art, next, Kind.ARTIFACT));
                events.add(new Event(dispatch, next, Kind.DISPATCH));
                free--;
                flying++;
            }
            if (events.isEmpty()) {
                long extra = 0;
                for (int i = 0; i < n; i++) if (!admitted[i]) extra += Math.max(0, cost[i].weight());
                return t + extra;
            }
            Event e = events.poll();
            t = e.at();
            while (!landing.isEmpty() && landing.peek()[0] <= t) {
                land((int) landing.poll()[1], waiters, unready, ready);
            }
            switch (e.kind()) {
                case ARTIFACT -> {}
                case DISPATCH -> {
                    ModuleWorkCost m = cost[e.module()];
                    if (inFlightAtDispatch != null) inFlightAtDispatch.put(m.dir(), flying);
                    long fin = t + Math.max(m.suiteAt(shareAt(slots, flying, m)), m.tailWeight());
                    events.add(new Event(fin, e.module(), Kind.FINISH));
                    end = Math.max(end, fin);
                }
                case FINISH -> {
                    free++;
                    flying--;
                }
            }
        }
        return end;
    }

    /** {@code module}'s classes landed: every module waiting on it has one prereq fewer. */
    private static void land(int module, List<List<Integer>> waiters, int[] unready, BitSet ready) {
        for (int w : waiters.get(module)) {
            if (--unready[w] == 0) ready.set(w);
        }
    }

    /** Declaration order is the tie-break order at one instant. */
    private enum Kind {
        FINISH,
        ARTIFACT,
        DISPATCH
    }

    /**
     * The runner share a suite dispatching now would get: the jobs budget over the modules in
     * flight, never below one, capped by the suite's classes — the executor's own late-share rule.
     */
    static int shareAt(int jobs, int inFlight, ModuleWorkCost m) {
        int share = Math.max(1, Math.max(1, jobs) / Math.max(1, inFlight));
        return TestSuiteScaling.effectiveRunners(share, m.suiteClasses());
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
        return m.prefix() + Math.max(m.testWeight(), m.tailWeight());
    }

    /** {@link #moduleWall} with the suite re-sharded for {@code runners}. */
    public static long moduleWallAt(ModuleWorkCost m, int runners) {
        return m.prefix() + Math.max(m.suiteAt(runners), m.tailWeight());
    }

    /** Copy costs into a mutable list (defensive). */
    public static List<ModuleWorkCost> copy(List<ModuleWorkCost> mods) {
        if (mods == null || mods.isEmpty()) return List.of();
        return new ArrayList<>(mods);
    }
}
