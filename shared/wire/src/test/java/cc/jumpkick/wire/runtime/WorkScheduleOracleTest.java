// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkSchedule#listSchedule} against the scan it replaced: the same admission, module for
 * module, on random graphs — any list order, prereqs outside the schedule, a dir listed twice, a
 * module that waits on itself, gates at zero and suites sharing the machine.
 */
class WorkScheduleOracleTest {

    @Test
    void counts_admit_exactly_what_the_scan_admitted() {
        Random rnd = new Random(4745);
        for (int round = 0; round < 3000; round++) {
            List<ModuleWorkCost> mods = randomGraph(rnd);
            int concurrency = 1 + rnd.nextInt(12);
            assertThat(WorkSchedule.listSchedule(mods, concurrency))
                    .as("round %d, %d modules, -j%d", round, mods.size(), concurrency)
                    .isEqualTo(scan(mods, concurrency));
        }
    }

    @Test
    void a_wide_workspace_prices_quickly() {
        // 1,000 modules, each on a few of the ones before it: the scan was quadratic per call.
        Random rnd = new Random(1);
        List<ModuleWorkCost> mods = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Set<Path> pre = new HashSet<>();
            for (int k = 0; k < 3 && i > 0; k++) pre.add(Path.of("m" + rnd.nextInt(i)));
            mods.add(new ModuleWorkCost(Path.of("m" + i), pre, 10 + rnd.nextInt(90), rnd.nextInt(50), 0, 5, 0, 0));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) WorkSchedule.listSchedule(mods, 24);
        assertThat((System.nanoTime() - start) / 1_000_000)
                .as("50 schedules, ms")
                .isLessThan(2_000);
    }

    private static List<ModuleWorkCost> randomGraph(Random rnd) {
        int n = 1 + rnd.nextInt(40);
        List<ModuleWorkCost> mods = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Set<Path> pre = new HashSet<>();
            int k = rnd.nextInt(4);
            for (int j = 0; j < k && i > 0; j++) pre.add(Path.of("m" + rnd.nextInt(i)));
            if (rnd.nextInt(10) == 0) pre.add(Path.of("clean" + rnd.nextInt(3)));
            if (rnd.nextInt(200) == 0) pre.add(Path.of("m" + i));
            int weight = rnd.nextInt(6) == 0 ? 0 : rnd.nextInt(100);
            int gate =
                    switch (rnd.nextInt(4)) {
                        case 0 -> ModuleWorkCost.UNKNOWN_GATE;
                        case 1 -> 0;
                        default -> rnd.nextInt(weight + 1);
                    };
            int test = rnd.nextInt(3) == 0 ? rnd.nextInt(80) : 0;
            int suiteWall = test > 0 && rnd.nextBoolean() ? rnd.nextInt(200) : 0;
            mods.add(new ModuleWorkCost(
                    Path.of("m" + i),
                    pre,
                    weight,
                    test,
                    rnd.nextInt(4) == 0 ? rnd.nextInt(60) : 0,
                    gate,
                    suiteWall,
                    suiteWall > 0 ? 1 + rnd.nextInt(20) : 0));
        }
        if (n > 2 && rnd.nextInt(8) == 0) mods.add(mods.get(rnd.nextInt(n)));
        // Topological order is not required of the caller; shuffle most rounds.
        if (rnd.nextInt(4) != 0) Collections.shuffle(mods, rnd);
        return mods;
    }

    /** The schedule as it was: rescan the remaining list for the first ready module per admission. */
    private static long scan(List<ModuleWorkCost> mods, int concurrency) {
        int slots = Math.max(1, concurrency);
        Map<Path, ModuleWorkCost> byDir = new HashMap<>();
        Set<Path> remaining = new LinkedHashSet<>();
        for (ModuleWorkCost m : mods) {
            byDir.put(m.dir(), m);
            remaining.add(m.dir());
        }
        Map<Path, Long> artifactAt = new HashMap<>();
        record Event(long at, Path dir, int kind) {} // 0 finish, 1 artifact, 2 dispatch
        PriorityQueue<Event> events =
                new PriorityQueue<>(Comparator.comparingLong(Event::at).thenComparingInt(Event::kind));
        long t = 0;
        long end = 0;
        int free = slots;
        int flying = 0;
        while (!remaining.isEmpty() || flying > 0) {
            while (free > 0 && !remaining.isEmpty()) {
                Path next = null;
                for (Path d : remaining) {
                    if (ready(Objects.requireNonNull(byDir.get(d)), byDir.keySet(), artifactAt, t)) {
                        next = d;
                        break;
                    }
                }
                if (next == null) break;
                remaining.remove(next);
                ModuleWorkCost m = Objects.requireNonNull(byDir.get(next));
                long art = t + m.artifactPoint();
                artifactAt.put(next, art);
                events.add(new Event(art, next, 1));
                events.add(new Event(t + m.prefix(), next, 2));
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
            if (e.kind() == 2) {
                ModuleWorkCost m = Objects.requireNonNull(byDir.get(e.dir()));
                long fin = t + Math.max(m.suiteAt(WorkSchedule.shareAt(slots, flying, m)), m.tailWeight());
                events.add(new Event(fin, e.dir(), 0));
                end = Math.max(end, fin);
            } else if (e.kind() == 0) {
                free++;
                flying--;
            }
        }
        return end;
    }

    private static boolean ready(ModuleWorkCost m, Set<Path> dirty, Map<Path, Long> artifactAt, long now) {
        for (Path p : Objects.requireNonNull(m.prereqs())) {
            if (!dirty.contains(p)) continue;
            Long at = artifactAt.get(p);
            if (at == null || at > now) return false;
        }
        return true;
    }
}
