// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.SessionContext;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** No wait in the ledger is unbounded: a lease it can never hold is refused, any other one has a deadline. */
class WorkerLeasesDeadlineTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    /** A host with 800 MiB free and no pressure: enough to look healthy, too little to overbook into. */
    private static final OverbookSignals.Source LITTLE_FREE = () -> new OverbookSignals.Reading(true, 800 * MIB, 0, 0);

    @Test
    void a_pinned_heap_past_twice_the_budget_is_refused_at_once() throws Exception {
        SessionContext.where(SessionContext.installed().withJvm(PluginTuning.NONE), () -> {
            long budget = 4 * GIB;
            WorkerLeases.Ledger ledger = ledger(() -> budget, LITTLE_FREE);
            List<String> command = List.of("java", "-Xmx1000000g", "-version");
            try (WorkerLeases.Grant held = ledger.acquireBytes(3 * GIB, true, 3L)) {
                CompletableFuture<WorkerLeases.Grant> refused = acquire(ledger, command, 7L);
                Throwable cause = failure(refused, 2);
                assertThat(cause).isInstanceOf(WorkerLeases.LeaseRefused.class);
                assertThat(cause.getMessage())
                        .contains("-Xmx1000000g")
                        .contains("4.0 GiB worker budget")
                        .contains("8.0 GiB");
                assertThat(ledger.queued()).isZero();
                assertThat(ledger.snapshot().leasedBytes()).isEqualTo(held.bytes());
            }
            return null;
        });
    }

    @Test
    void a_queued_lease_is_refused_once_the_budget_falls_below_half_of_it() throws Exception {
        AtomicLong budget = new AtomicLong(4 * GIB);
        WorkerLeases.Ledger ledger = ledger(budget::get, OverbookSignals.off());
        try (WorkerLeases.Grant held = ledger.acquireBytes(GIB, false, 3L)) {
            CompletableFuture<WorkerLeases.Grant> waiting = acquireBytes(ledger, 4 * GIB, 7L);
            awaitQueued(ledger, 1);
            budget.set(GIB);
            Throwable cause = failure(waiting, 2);
            assertThat(cause).isInstanceOf(WorkerLeases.LeaseRefused.class);
            assertThat(cause.getMessage()).contains("4.0 GiB").contains("1.0 GiB worker budget");
            assertThat(ledger.queued()).isZero();
        }
    }

    @Test
    void a_lease_that_fits_but_stays_blocked_fails_at_its_deadline_naming_the_memory() throws Exception {
        WorkerLeases.Ledger ledger = ledger(() -> 4 * GIB, LITTLE_FREE).waitAtMost(Duration.ofMillis(300));
        try (WorkerLeases.Grant held = ledger.acquireBytes(3 * GIB, true, 3L)) {
            long started = System.nanoTime();
            CompletableFuture<WorkerLeases.Grant> waiting = acquireBytes(ledger, 2 * GIB, 7L);
            Throwable cause = failure(waiting, 5);
            assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(300));
            assertThat(cause).isInstanceOf(WorkerLeases.LeaseRefused.class);
            assertThat(cause.getMessage())
                    .startsWith("job #7 waited 0.3s for memory and gave up")
                    .contains("needs 2.0 GiB (1.0 GiB free of the 4.0 GiB worker budget)")
                    .contains("held by job #3 3.0 GiB");
            assertThat(ledger.queued()).isZero();
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(held.bytes());
        }
        try (WorkerLeases.Grant after = ledger.acquireBytes(2 * GIB, true, 8L)) {
            assertThat(after.bytes()).isEqualTo(2 * GIB);
        }
    }

    @Test
    void a_lease_granted_before_its_deadline_is_not_failed() throws Exception {
        WorkerLeases.Ledger ledger = ledger(() -> 4 * GIB, LITTLE_FREE).waitAtMost(Duration.ofSeconds(30));
        WorkerLeases.Grant held = ledger.acquireBytes(3 * GIB, true, 3L);
        CompletableFuture<WorkerLeases.Grant> waiting = acquireBytes(ledger, 2 * GIB, 7L);
        awaitQueued(ledger, 1);
        held.close();
        try (WorkerLeases.Grant grant = waiting.get(5, TimeUnit.SECONDS)) {
            assertThat(grant.bytes()).isEqualTo(2 * GIB);
        }
    }

    private static Throwable failure(CompletableFuture<WorkerLeases.Grant> future, int seconds) throws Exception {
        try {
            WorkerLeases.Grant grant = future.get(seconds, TimeUnit.SECONDS);
            grant.close();
            throw new AssertionError("granted " + grant.bytes() + " bytes");
        } catch (ExecutionException e) {
            return Objects.requireNonNull(e.getCause());
        }
    }

    private static CompletableFuture<WorkerLeases.Grant> acquire(
            WorkerLeases.Ledger ledger, List<String> command, @Nullable Long requestId) {
        return run(() -> ledger.acquire(command, requestId));
    }

    private static CompletableFuture<WorkerLeases.Grant> acquireBytes(
            WorkerLeases.Ledger ledger, long bytes, @Nullable Long requestId) {
        return run(() -> ledger.acquireBytes(bytes, true, requestId));
    }

    private static CompletableFuture<WorkerLeases.Grant> run(Lease lease) {
        CompletableFuture<WorkerLeases.Grant> out = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                out.complete(lease.get());
            } catch (InterruptedException | RuntimeException e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    @FunctionalInterface
    private interface Lease {
        WorkerLeases.Grant get() throws InterruptedException;
    }

    private static WorkerLeases.Ledger ledger(LongSupplier budget, OverbookSignals.Source signals) {
        return new WorkerLeases.Ledger(budget, () -> 8, id -> false, signals);
    }

    private static void awaitQueued(WorkerLeases.Ledger ledger, int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.queued() < n) {
            if (System.nanoTime() > deadline) throw new AssertionError("queued " + ledger.queued());
            Thread.sleep(5);
        }
    }
}
