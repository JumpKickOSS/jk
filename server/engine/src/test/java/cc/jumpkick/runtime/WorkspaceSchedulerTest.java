// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Whitebox tests for {@link WorkspaceScheduler}: the unbounded batch-per-level path and the bounded
 * rolling-window path (including the strict-serial {@code cap = 1} used by {@code -j1}).
 */
class WorkspaceSchedulerTest {

    private static Path p(String s) {
        return Path.of(s);
    }

    /**
     * A diamond DAG: {@code a} → {b, c} → {@code d}. Returns edges keyed by unit dir → its prereq
     * dirs, matching {@link BuildGraph.Result#edges()}'s convention.
     */
    private static Map<Path, Set<Path>> diamondEdges() {
        return Map.of(
                p("a"), Set.of(),
                p("b"), Set.of(p("a")),
                p("c"), Set.of(p("a")),
                p("d"), Set.of(p("b"), p("c")));
    }

    private static List<String> diamondUnits() {
        return List.of("a", "b", "c", "d");
    }

    /** Runs the scheduler tracking peak concurrency + completion order; every unit succeeds. */
    private record Trace(int peakConcurrency, List<String> completionOrder, int sinkCalls, int maxBatch) {}

    private static Trace trace(int maxConcurrency) {
        return trace(maxConcurrency, false);
    }

    /**
     * {@code rendezvousMidLevel} makes {@code b} and {@code c} — the diamond's only level that may
     * run at once — wait for each other before releasing their slots. A peak of 2 is then a fact,
     * not a bet that both threads get scheduled inside the same hold window on a loaded host.
     */
    private static Trace trace(int maxConcurrency, boolean rendezvousMidLevel) {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> completed = Collections.synchronizedList(new ArrayList<>());
        int[] sinkCalls = {0};
        int[] maxBatch = {0};
        WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    if (rendezvousMidLevel && ("b".equals(unit) || "c".equals(unit))) {
                        awaitInFlight(inFlight, 2);
                    }
                    try {
                        Thread.sleep(40); // hold the slot: over-admission is only visible as overlap
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inFlight.decrementAndGet();
                    completed.add(unit);
                    return unit; // non-null result, but the sink returns null so the schedule continues
                },
                (justCompleted, results, remaining) -> {
                    sinkCalls[0]++;
                    maxBatch[0] = Math.max(maxBatch[0], justCompleted.size());
                    assertThat(results).hasSameSizeAs(justCompleted);
                    return null;
                },
                maxConcurrency);
        return new Trace(peak.get(), new ArrayList<>(completed), sinkCalls[0], maxBatch[0]);
    }

    /** Park until {@code inFlight} reaches {@code target}; on timeout the peak assertion reports it. */
    private static void awaitInFlight(AtomicInteger inFlight, int target) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (inFlight.get() < target && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void cancel_drains_in_flight_units_before_returning() {
        // : cancel(true) settled the futures instantly while suppliers kept running, so
        // module-finish events could land AFTER workspace-finish. The cancel path must wait
        // (bounded) for in-flight tasks to settle before run() returns.
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean slowFinished = new AtomicBoolean();
        List<String> lifecycle = Collections.synchronizedList(new ArrayList<>());
        // Determinism: "fast" only flips cancel once "slow" is genuinely in flight — a
        // not-yet-started "slow" would be (correctly) no-op'd by the admission gate instead.
        CountDownLatch slowStarted = new CountDownLatch(1);
        AtomicBoolean cancelledWhileSlowRan = new AtomicBoolean();
        Object result = WorkspaceScheduler.run(
                List.of("fast", "slow"),
                WorkspaceSchedulerTest::p,
                Map.of(p("fast"), Set.of(), p("slow"), Set.of()),
                unit -> {
                    if ("fast".equals(unit)) {
                        try {
                            cancelledWhileSlowRan.set(slowStarted.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        cancelled.set(true);
                        return unit;
                    }
                    slowStarted.countDown();
                    try {
                        // Elapsed time IS the property: an early return can only be caught while
                        // "slow" is provably still running, and only the drain can close that gap.
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    slowFinished.set(true);
                    lifecycle.add("module-finish:slow");
                    return unit;
                },
                (justCompleted, results, remaining) -> null,
                2,
                cancelled::get);
        assertThat(result).isNull();
        assertThat(cancelledWhileSlowRan)
                .as("the fixture must cancel while \"slow\" is in flight, else nothing is drained")
                .isTrue();
        assertThat(slowFinished).isTrue();
        WorkspaceExecute.finish(
                new WorkspaceBuildListener() {
                    @Override
                    public void onWorkspaceFinish(WorkspaceResult result) {
                        lifecycle.add("workspace-finish");
                    }
                },
                new WorkspaceResult(false, 1, List.of(), List.of(), true));
        assertThat(lifecycle).containsExactly("module-finish:slow", "workspace-finish");
    }

    /** Assert every unit ran only after its prereqs finished (positional check on completion order). */
    private static void assertDependencyOrder(List<String> order) {
        assertThat(order).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("b"));
        assertThat(order.indexOf("a")).isLessThan(order.indexOf("c"));
        assertThat(order.indexOf("b")).isLessThan(order.indexOf("d"));
        assertThat(order.indexOf("c")).isLessThan(order.indexOf("d"));
    }

    @Test
    void dependents_admit_on_artifact_publish_not_completion() throws Exception {
        // : "up" publishes its artifacts mid-task, then keeps "testing" until released.
        // "down" (depends on up) must start after the publish but before up completes.
        CountDownLatch upPublished = new CountDownLatch(1);
        CountDownLatch downStarted = new CountDownLatch(1);
        CountDownLatch releaseUp = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        WorkspaceScheduler.PhasedUnitTask<String, String> task = (unit, artifactsReady) -> {
            order.add("start:" + unit);
            if (unit.equals("up")) {
                // Latch before the publish, not after: artifactsReady is what admits "down", so a
                // countDown afterwards races the dependent it just released.
                upPublished.countDown();
                artifactsReady.run();
                try {
                    // Hold "up" open (its test phase) until "down" has demonstrably started.
                    assertThat(downStarted.await(10, TimeUnit.SECONDS))
                            .as("dependent must start while upstream is still running")
                            .isTrue();
                    assertThat(releaseUp.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("finish:up");
            } else {
                // Admission contract: never before the publish.
                assertThat(upPublished.getCount()).isZero();
                downStarted.countDown();
                // Record before the release, for the same reason as the publish above: releaseUp is
                // what lets "up" append its own finish, so "down" must already be on record.
                order.add("finish:down");
                releaseUp.countDown();
            }
            return unit;
        };
        WorkspaceScheduler.run(
                List.of("up", "down"),
                WorkspaceSchedulerTest::p,
                Map.of(p("up"), Set.of(), p("down"), Set.of(p("up"))),
                task,
                (justCompleted, results, remaining) -> null,
                4,
                () -> false);
        assertThat(order).startsWith("start:up", "start:down");
        assertThat(order.indexOf("finish:down")).isLessThan(order.indexOf("finish:up"));
    }

    @Test
    void a_unit_that_never_publishes_unblocks_dependents_on_completion() {
        // Compile/package failure (or no package steps): completion publishes implicitly so
        // dependents run and fail accurately instead of wedging the schedule.
        List<String> started = Collections.synchronizedList(new ArrayList<>());
        WorkspaceScheduler.PhasedUnitTask<String, String> task = (unit, artifactsReady) -> {
            started.add(unit);
            return unit; // never calls artifactsReady
        };
        Object result = WorkspaceScheduler.run(
                List.of("up", "down"),
                WorkspaceSchedulerTest::p,
                Map.of(p("up"), Set.of(), p("down"), Set.of(p("up"))),
                task,
                (justCompleted, results, remaining) -> null,
                1,
                () -> false);
        assertThat(result).isNull();
        assertThat(started).containsExactly("up", "down");
    }

    @Test
    void bounded_admission_prefers_the_longest_remaining_chain() {
        // : a spine (root -> s1 -> s2 -> s3) plus three independent leaves, declared
        // leaves-first. With cap=1 the old first-ready scan ran the leaves before the spine;
        // critical-path-first admission must start the spine as soon as it is ready.
        List<String> units = List.of("leaf1", "leaf2", "leaf3", "root", "s1", "s2", "s3");
        Map<Path, Set<Path>> edges = Map.of(
                p("leaf1"), Set.of(),
                p("leaf2"), Set.of(),
                p("leaf3"), Set.of(),
                p("root"), Set.of(),
                p("s1"), Set.of(p("root")),
                p("s2"), Set.of(p("s1")),
                p("s3"), Set.of(p("s2")));
        List<String> started = Collections.synchronizedList(new ArrayList<>());
        WorkspaceScheduler.run(
                units,
                WorkspaceSchedulerTest::p,
                edges,
                unit -> {
                    started.add(unit);
                    return unit;
                },
                (justCompleted, results, remaining) -> null,
                1);
        // The spine's interior runs before any leaf: heights are root=3 > s1=2 > s2=1; s3 has
        // no dependents (height 0) so it legitimately ties with the leaves — critical-path
        // scheduling only guarantees the chain is never BLOCKED behind height-0 work.
        assertThat(started.subList(0, 3)).containsExactly("root", "s1", "s2");
        assertThat(started).startsWith("root", "s1", "s2");
        assertThat(started.indexOf("s3")).isGreaterThan(started.indexOf("s2"));
    }

    @Test
    void dependent_chain_height_scores_the_spine() {
        List<String> units = diamondUnits();
        Map<Path, Integer> h = WorkspaceScheduler.dependentChainHeight(
                units, WorkspaceSchedulerTest::p, diamondEdges(), Set.of(p("a"), p("b"), p("c"), p("d")));
        assertThat(h.get(p("a"))).isEqualTo(2);
        assertThat(h.get(p("b"))).isEqualTo(1);
        assertThat(h.get(p("c"))).isEqualTo(1);
        assertThat(h.get(p("d"))).isEqualTo(0);
    }

    @Test
    void cap1_runs_strictly_serial_in_dependency_order() {
        Trace t = trace(1);
        assertThat(t.peakConcurrency()).isEqualTo(1); // never two modules at once
        assertDependencyOrder(t.completionOrder());
        // Bounded cadence: the sink is called once per completed unit.
        assertThat(t.sinkCalls()).isEqualTo(4);
        assertThat(t.maxBatch()).isEqualTo(1);
    }

    @Test
    void cap2_bounds_concurrency_at_two_and_respects_order() {
        Trace t = trace(2);
        assertThat(t.peakConcurrency()).isBetween(1, 2); // b and c may overlap, but never three
        assertDependencyOrder(t.completionOrder());
        assertThat(t.sinkCalls()).isEqualTo(4); // still one sink call per unit under a cap
        assertThat(t.maxBatch()).isEqualTo(1);
    }

    @Test
    void unbounded_batches_per_level_and_preserves_order() {
        Trace t = trace(0, true);
        assertThat(t.peakConcurrency()).isEqualTo(2); // the {b, c} level runs both at once
        assertDependencyOrder(t.completionOrder());
        // Batch-per-level cadence: levels [a], [b, c], [d] → 3 sink calls, one batch of size 2.
        assertThat(t.sinkCalls()).isEqualTo(3);
        assertThat(t.maxBatch()).isEqualTo(2);
    }

    @Test
    void bounded_fails_fast_and_stops_admitting() {
        List<String> ran = Collections.synchronizedList(new ArrayList<>());
        String stop = WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> {
                    ran.add(unit);
                    return unit;
                },
                (justCompleted, results, remaining) ->
                        justCompleted.contains("a") ? "FAILED" : null, // stop after the root
                1);
        assertThat(stop).isEqualTo("FAILED");
        // Serial + fail-fast: nothing past the failing unit's dependents should have started.
        assertThat(ran).containsExactly("a");
    }

    @Test
    void bounded_stops_admitting_when_cancelled() {
        AtomicInteger started = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        String stop = WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> {
                    started.incrementAndGet();
                    if ("a".equals(unit)) cancelled.set(true);
                    return unit;
                },
                (justCompleted, results, remaining) -> null,
                1,
                cancelled::get);
        assertThat(stop).isNull();
        assertThat(started.get()).isEqualTo(1);
    }

    @Test
    void unbounded_does_not_start_the_next_level_when_cancelled() {
        AtomicInteger started = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        String stop = WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> {
                    started.incrementAndGet();
                    if ("a".equals(unit)) cancelled.set(true);
                    return unit;
                },
                (justCompleted, results, remaining) -> null,
                0,
                cancelled::get);
        assertThat(stop).isNull();
        assertThat(started.get()).isEqualTo(1);
    }

    @Test
    void unbounded_fails_fast() {
        String stop = WorkspaceScheduler.run(
                diamondUnits(),
                WorkspaceSchedulerTest::p,
                diamondEdges(),
                unit -> unit,
                (justCompleted, results, remaining) -> justCompleted.contains("a") ? "FAILED" : null,
                0);
        assertThat(stop).isEqualTo("FAILED");
    }

    /** A↔B cycle: neither unit is ever ready → must throw, not spin. */
    private static Map<Path, Set<Path>> cycleEdges() {
        return Map.of(p("a"), Set.of(p("b")), p("b"), Set.of(p("a")));
    }

    @Test
    void unbounded_cycle_throws_naming_stuck_units() {
        assertThatThrownBy(() -> WorkspaceScheduler.run(
                        List.of("a", "b"),
                        WorkspaceSchedulerTest::p,
                        cycleEdges(),
                        unit -> unit,
                        (justCompleted, results, remaining) -> null,
                        0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsatisfiable")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }

    @Test
    void bounded_cycle_throws_naming_stuck_units() {
        assertThatThrownBy(() -> WorkspaceScheduler.run(
                        List.of("a", "b"),
                        WorkspaceSchedulerTest::p,
                        cycleEdges(),
                        unit -> unit,
                        (justCompleted, results, remaining) -> null,
                        2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsatisfiable")
                .hasMessageContaining("a")
                .hasMessageContaining("b");
    }
}
