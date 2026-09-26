// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The byte ledger: order, clamp, cancel, and release. */
class WorkerLeasesTest {

    @AfterEach
    void reset() {
        StepScope.close();
    }

    @Test
    void overhead_is_the_larger_of_the_floor_and_twelve_percent() {
        assertThat(WorkerLeases.overheadBytes(128L << 20)).isEqualTo(WorkerLeases.OVERHEAD_FLOOR_BYTES);
        assertThat(WorkerLeases.overheadBytes(4L << 30))
                .isEqualTo((long) ((4L << 30) * WorkerLeases.OVERHEAD_FRACTION));
        assertThat(WorkerLeases.jvmLease(512L << 20)).isEqualTo((512L << 20) + WorkerLeases.OVERHEAD_FLOOR_BYTES);
    }

    @Test
    void a_user_pinned_oversized_heap_is_not_rewritten_and_leases_the_whole_budget() throws Exception {
        SessionContext.where(SessionContext.installed().withJvm(PluginTuning.NONE), () -> {
            long budget = 200L << 20;
            WorkerLeases.Ledger ledger = ledger(budget, 4);
            List<String> command = List.of("java", "-Xmx1000000g", "-version");
            assertThat(JvmOptions.userPinnedHeap(command)).isTrue();
            assertThat(JvmOptions.userPinnedHeap(List.of("javac", "-J-Xmx1000000g")))
                    .isTrue();
            assertThat(JvmOptions.userPinnedHeap(List.of("java", "-XX:MaxRAMPercentage=95")))
                    .isTrue();
            assertThat(JvmOptions.HeapChoice.inspect(command).label()).isEqualTo("-Xmx1000000g");
            try (WorkerLeases.Grant held = ledger.acquireBytes(budget / 2, false, null)) {
                CompletableFuture<WorkerLeases.Grant> waiting = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        waiting.complete(ledger.acquire(command, null));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        waiting.completeExceptionally(e);
                    }
                });
                awaitQueued(ledger, 1);
                assertThat(waiting).isNotDone();
                held.close();
                try (WorkerLeases.Grant grant = waiting.get(5, TimeUnit.SECONDS)) {
                    assertThat(grant.userPinned()).isTrue();
                    assertThat(grant.overBudget()).isTrue();
                    assertThat(grant.clamped()).isFalse();
                    assertThat(grant.bytes()).isEqualTo(budget);
                    assertThat(JobWorkers.launchCommand(command, grant)).containsExactlyElementsOf(command);
                }
            }
            SessionContext.where(
                    SessionContext.installed().withJvm(new PluginTuning(40.0, null, null, List.of())), () -> {
                        List<String> ram = List.of("java", "-version");
                        assertThat(JvmOptions.userPinnedHeap(ram)).isTrue();
                        assertThat(JvmOptions.HeapChoice.inspect(ram).label()).isEqualTo("--ram-percent 40");
                        return null;
                    });
            return null;
        });
    }

    @Test
    void a_planned_oversized_heap_is_clamped_to_the_budget() throws Exception {
        SessionContext.where(SessionContext.installed().withJvm(PluginTuning.NONE), () -> {
            JvmOptions.notePlannedHeap("-Xmx1000001m");
            try {
                long budget = 200L << 20;
                WorkerLeases.Ledger ledger = ledger(budget, 4);
                List<String> command = List.of("java", "-Xmx1000001m", "-version");
                assertThat(JvmOptions.userPinnedHeap(command)).isFalse();
                try (WorkerLeases.Grant grant = ledger.acquire(command, null)) {
                    assertThat(grant.userPinned()).isFalse();
                    assertThat(grant.clamped()).isTrue();
                    assertThat(grant.xmxBytes()).isLessThan(WorkerLeases.parseXmx(command));
                    assertThat(WorkerLeases.jvmLease(grant.xmxBytes())).isLessThanOrEqualTo(budget);
                    assertThat(grant.bytes()).isLessThanOrEqualTo(budget);
                    List<String> launched = JobWorkers.launchCommand(command, grant);
                    assertThat(WorkerLeases.parseXmx(launched)).isEqualTo(grant.xmxBytes());
                    assertThat(launched).doesNotContain("-Xmx1000001m");
                }
            } finally {
                JvmOptions.forgetPlannedHeapForTests("-Xmx1000001m");
            }
            return null;
        });
    }

    @Test
    void a_lease_larger_than_the_budget_is_clamped_to_it() {
        long budget = 200L << 20;
        long fitted = WorkerLeases.clampXmx(2L << 30, budget);
        assertThat(fitted).isLessThan(2L << 30);
        assertThat(WorkerLeases.jvmLease(fitted)).isLessThanOrEqualTo(budget);
        List<String> rewritten =
                WorkerLeases.rewriteHeap(List.of("java", "-Xmx2g", "-Xms1g", "-XX:SoftMaxHeapSize=1500m"), fitted);
        assertThat(WorkerLeases.parseXmx(rewritten)).isLessThanOrEqualTo(fitted);
        assertThat(rewritten).noneMatch(arg -> arg.contains("1500m") || arg.contains("-Xms1g"));
    }

    @Test
    void waiters_are_granted_in_arrival_order() throws Exception {
        WorkerLeases.Ledger ledger = ledger(100, 4);
        try (WorkerLeases.Grant held = ledger.acquireBytes(60, false, null)) {
            List<Integer> order = new ArrayList<>();
            List<CompletableFuture<WorkerLeases.Grant>> waiting = new ArrayList<>();
            // Virtual threads, not the common pool: a pool of one (the test JVM's processor
            // count) cannot run a second acquire while the first is blocked in the ledger.
            for (int i = 0; i < 3; i++) {
                int id = i;
                CompletableFuture<WorkerLeases.Grant> grant = new CompletableFuture<>();
                Thread.ofVirtual().start(() -> {
                    try {
                        WorkerLeases.Grant lease = ledger.acquireBytes(60, false, null);
                        synchronized (order) {
                            order.add(id);
                        }
                        grant.complete(lease);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        grant.completeExceptionally(e);
                    }
                });
                waiting.add(grant);
                awaitQueued(ledger, i + 1);
            }
            held.close();
            for (CompletableFuture<WorkerLeases.Grant> grant : waiting)
                grant.get(5, TimeUnit.SECONDS).close();
            assertThat(order).containsExactly(0, 1, 2);
        }
    }

    @Test
    void cancelling_a_request_drops_its_queued_lease() throws Exception {
        WorkerLeases.Ledger ledger = ledger(50, 4);
        try (WorkerLeases.Grant held = ledger.acquireBytes(50, false, null)) {
            CompletableFuture<WorkerLeases.Grant> waiting = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    waiting.complete(ledger.acquireBytes(50, false, 7L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    waiting.completeExceptionally(e);
                }
            });
            awaitQueued(ledger, 1);
            ledger.cancelRequest(7);
            assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            assertThat(ledger.queued()).isZero();
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(held.bytes());
        }
        assertThat(ledger.snapshot().leasedBytes()).isZero();
    }

    @Test
    void a_jvm_waits_for_a_core_even_when_memory_is_free() throws Exception {
        WorkerLeases.Ledger ledger = ledger(1L << 30, 1);
        try (WorkerLeases.Grant held = ledger.acquireBytes(1, true, null)) {
            CompletableFuture<WorkerLeases.Grant> second = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    second.complete(ledger.acquireBytes(1, true, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    second.completeExceptionally(e);
                }
            });
            Thread.sleep(150);
            assertThat(second).isNotDone();
            held.close();
            second.get(5, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void a_wait_is_shown_in_the_steps_output_and_leaves_its_label_alone() throws Exception {
        WorkerLeases.Ledger ledger = ledger(40, 4);
        AtomicReference<String> label = new AtomicReference<>("compiling");
        List<String> output = new CopyOnWriteArrayList<>();
        AtomicInteger waitedCalls = new AtomicInteger();
        StepScope.open(recording(label, output, waitedCalls));
        try (WorkerLeases.Grant held = ledger.acquireBytes(40, false, 3L)) {
            CompletableFuture<WorkerLeases.Grant> waiting = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                StepScope.open(recording(label, output, waitedCalls));
                try {
                    waiting.complete(ledger.acquireBytes(40, false, 3L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    waiting.completeExceptionally(e);
                } finally {
                    StepScope.close();
                }
            });
            awaitQueued(ledger, 1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (output.stream().noneMatch(l -> l.startsWith("waiting for memory: need"))) {
                if (System.nanoTime() > deadline) throw new AssertionError("no waiting line in " + output);
                Thread.sleep(5);
            }
            held.close();
            waiting.get(5, TimeUnit.SECONDS).close();
            assertThat(ledger.waitedNanos(3L)).isPositive();
            assertThat(label.get())
                    .as("the step keeps its own label through the wait and the grant")
                    .isEqualTo("compiling");
        }
    }

    /** Does not watch {@link JobWorkers} shutdown tombstones; those are process-wide. */
    private static WorkerLeases.Ledger ledger(long capacityBytes, int cpu) {
        return new WorkerLeases.Ledger(() -> capacityBytes, () -> cpu, id -> false);
    }

    private static TaskContext recording(
            AtomicReference<String> label, List<String> output, AtomicInteger waitedCalls) {
        return new TaskContext() {
            @Override
            public void progress(int delta) {}

            @Override
            public void updateTicks(int additional) {}

            @Override
            public void label(@Nullable String description) {
                label.set(description == null ? "" : description);
            }

            @Override
            public void output(@Nullable String line) {
                if (line != null) output.add(line);
            }

            @Override
            public void waited(Duration blocked) {
                waitedCalls.incrementAndGet();
            }

            @Override
            public void warn(String code, String message) {}

            @Override
            public void error(String code, String message) {}

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public <T> void put(BuildPlanKey<T> key, T value) {}

            @Override
            public <T> Optional<T> get(BuildPlanKey<T> key) {
                return Optional.empty();
            }

            @Override
            public <T> T require(BuildPlanKey<T> key) {
                throw new IllegalStateException(key.toString());
            }
        };
    }

    private static void awaitQueued(WorkerLeases.Ledger ledger, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.queued() < n) {
            if (System.nanoTime() > deadline) throw new AssertionError("queued " + ledger.queued());
            Thread.sleep(5);
        }
    }
}
