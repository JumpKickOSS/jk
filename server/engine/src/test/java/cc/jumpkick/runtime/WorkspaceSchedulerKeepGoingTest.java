// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.WorkspaceScheduler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The two settings of the sink's verdict, at the level that decides it.
 *
 * <p>{@link WorkspaceScheduler.LevelSink#after} stops the schedule on a non-null return. Fail-fast
 * returns the first failing outcome; {@code --continue} returns null and lets the graph finish, so
 * an unrelated module is no longer hidden behind someone else's red test.
 */
class WorkspaceSchedulerKeepGoingTest {

    private static Path p(String s) {
        return Path.of(s);
    }

    /** Three independent units — nothing depends on anything, so only the sink decides. */
    private static final List<String> UNITS = List.of("a", "bad", "c");

    private static final Map<Path, Set<Path>> NO_EDGES = Map.of(p("a"), Set.of(), p("bad"), Set.of(), p("c"), Set.of());

    /** Runs the three units serially; {@code bad} fails. Returns the units that actually ran. */
    private static List<String> runWith(boolean keepGoing) {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        WorkspaceScheduler.UnitTask<String, String> task = unit -> {
            ran.add(unit);
            return "bad".equals(unit) ? "FAILED" : "OK";
        };
        WorkspaceScheduler.LevelSink<String, String> sink = (justCompleted, results, remaining) -> {
            for (String r : results) {
                if ("FAILED".equals(r) && !keepGoing) return r;
            }
            return null;
        };
        String stopped =
                WorkspaceScheduler.run(UNITS, WorkspaceSchedulerKeepGoingTest::p, NO_EDGES, task, sink, 1, () -> false);
        // Fail-fast surfaces the failure to the caller; keep-going leaves the verdict to the
        // outcome list, which is exactly how WorkspaceFinalPhase.aggregate recomputes the exit code.
        assertThat(stopped).isEqualTo(keepGoing ? null : "FAILED");
        return ran;
    }

    @Test
    void fail_fast_stops_the_schedule_at_the_failure() {
        assertThat(runWith(false))
                .as("units queued behind the failure never run")
                .doesNotContain("c");
    }

    /**
     * Fail-fast with a sibling in flight: the sibling is stopped through the hook and its result
     * still reaches the sink before the schedule returns, so the record can hold it as stopped
     * rather than missing it or finding it by timing.
     */
    @Test
    void fail_fast_stops_the_in_flight_sibling_and_waits_for_its_result() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowReleased = new CountDownLatch(1);
        List<String> stopped = Collections.synchronizedList(new ArrayList<>());
        List<String> seenBySink = Collections.synchronizedList(new ArrayList<>());
        WorkspaceScheduler.PhasedUnitTask<String, String> task = (unit, publish) -> {
            if ("slow".equals(unit)) {
                slowStarted.countDown();
                try {
                    if (!slowReleased.await(10, TimeUnit.SECONDS)) return "TIMED_OUT";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "STOPPED";
            }
            try {
                if (!slowStarted.await(10, TimeUnit.SECONDS)) return "TIMED_OUT";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "FAILED";
        };
        WorkspaceScheduler.LevelSink<String, String> sink = (justCompleted, results, remaining) -> {
            seenBySink.addAll(results);
            return results.contains("FAILED") ? "FAILED" : null;
        };
        Map<Path, Set<Path>> edges = Map.of(p("bad"), Set.of(), p("slow"), Set.of());
        String verdict = WorkspaceScheduler.run(
                List.of("bad", "slow"),
                WorkspaceSchedulerKeepGoingTest::p,
                edges,
                task,
                sink,
                2,
                () -> false,
                u -> false,
                unit -> {
                    stopped.add(unit);
                    slowReleased.countDown();
                });
        assertThat(verdict).isEqualTo("FAILED");
        assertThat(stopped).as("only the sibling still in flight is stopped").containsExactly("slow");
        assertThat(seenBySink)
                .as("the stopped sibling's result reached the sink before the schedule returned")
                .containsExactlyInAnyOrder("FAILED", "STOPPED");
    }

    @Test
    void keep_going_finishes_every_independent_unit() {
        assertThat(runWith(true))
                .as("a module unrelated to the failure must not be hidden by it")
                .containsExactlyInAnyOrderElementsOf(UNITS);
    }
}
