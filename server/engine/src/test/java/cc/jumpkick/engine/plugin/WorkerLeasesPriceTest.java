// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A planned fork is leased at the price it is expected to hold, not at its {@code -Xmx}; what the
 * held forks may grow to stays inside twice the budget.
 */
class WorkerLeasesPriceTest {

    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;
    private static final List<String> COMMAND = List.of("java", "-Xmx2g", "Main");

    private static JvmOptions.HeapChoice priced(long bytes) {
        return new JvmOptions.HeapChoice(false, 2 * GIB, "", bytes);
    }

    @Test
    void a_priced_fork_leases_its_price_and_keeps_its_heap() throws Exception {
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 8 * GIB, () -> 8, id -> false);
        try (WorkerLeases.Grant grant = ledger.acquire(COMMAND, null, priced(320 * MIB))) {
            assertThat(grant.bytes()).isEqualTo(320 * MIB);
            assertThat(grant.xmxBytes()).isEqualTo(2 * GIB);
            assertThat(grant.clamped()).isFalse();
            assertThat(ledger.snapshot().leasedBytes()).isEqualTo(320 * MIB);
        }
        try (WorkerLeases.Grant grant = ledger.acquire(COMMAND, null, priced(0))) {
            assertThat(grant.bytes()).as("unpriced").isEqualTo(WorkerLeases.jvmLease(2 * GIB));
        }
        try (WorkerLeases.Grant grant = ledger.acquire(COMMAND, null, priced(16 * GIB))) {
            assertThat(grant.bytes()).as("never above the heap's lease").isEqualTo(WorkerLeases.jvmLease(2 * GIB));
        }
    }

    @Test
    void forks_whose_heaps_could_grow_past_twice_the_budget_wait_though_their_prices_fit() throws Exception {
        long budget = 4 * GIB;
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> budget, () -> 8, id -> false);
        long ceiling = WorkerLeases.jvmLease(2 * GIB);
        int fit = (int) (OverbookSignals.ceiling(budget) / ceiling);
        List<WorkerLeases.Grant> held = new ArrayList<>();
        try {
            for (int i = 0; i < fit; i++) held.add(ledger.acquire(COMMAND, null, priced(256 * MIB)));
            assertThat(ledger.snapshot().leasedBytes())
                    .isEqualTo(fit * 256 * MIB)
                    .isLessThan(budget);

            CompletableFuture<WorkerLeases.Grant> next = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    next.complete(ledger.acquire(COMMAND, null, priced(256 * MIB)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    next.completeExceptionally(e);
                }
            });
            awaitQueued(ledger);
            assertThat(next).isNotDone();

            held.removeFirst().close();
            held.add(next.get(5, TimeUnit.SECONDS));
        } finally {
            for (WorkerLeases.Grant g : held) g.close();
        }
    }

    @Test
    void a_fork_is_priced_from_its_key_and_a_rerun_after_a_memory_failure_leases_its_whole_heap(@TempDir Path dir)
            throws Exception {
        LearnedHeaps heaps = new LearnedHeaps(dir);
        HeapScope.Key key = new HeapScope.Key(dir, "g:app", HeapScope.TEST, 25);
        heaps.noteResident(key, 400 * MIB);
        JvmOptions.HeapChoice planned = priced(0);
        assertThat(JobWorkers.priced(planned, heaps).priceBytes()).as("no key").isZero();
        HeapScope.fork(key, () -> {
            assertThat(JobWorkers.priced(planned, heaps).priceBytes()).isEqualTo(512 * MIB);
            HeapScope.fullLease(() -> {
                assertThat(JobWorkers.priced(planned, heaps).priceBytes())
                        .as("rerun")
                        .isZero();
                return null;
            });
            JvmOptions.HeapChoice pinned = new JvmOptions.HeapChoice(true, 2 * GIB, "-Xmx2g", 0);
            assertThat(JobWorkers.priced(pinned, heaps).priceBytes())
                    .as("a pin")
                    .isZero();
            return null;
        });
    }

    private static void awaitQueued(WorkerLeases.Ledger ledger) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ledger.queued() < 1) {
            if (System.nanoTime() > deadline) throw new AssertionError("nothing queued");
            Thread.sleep(5);
        }
    }
}
