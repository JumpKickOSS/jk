// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * A test JVM that drives engines holds their forks too, which lease from its own ledger alone: it
     * leases the price its whole tree was learned at, past its own heap's lease, never past the
     * budget, and with no JVM slot.
     */
    @Test
    void a_jvm_that_drives_engines_leases_its_price_past_its_heaps_lease() throws Exception {
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 8 * GIB, () -> 8, id -> false);
        try (WorkerLeases.Grant grant = ledger.acquireWithoutSlot(COMMAND, null, priced(3 * GIB))) {
            assertThat(grant.bytes()).isEqualTo(3 * GIB).isGreaterThan(WorkerLeases.jvmLease(2 * GIB));
            assertThat(grant.xmxBytes()).as("its heap is its own").isEqualTo(2 * GIB);
            assertThat(ledger.snapshot().runningJvms()).as("no slot").isZero();
        }
        try (WorkerLeases.Grant grant = ledger.acquireWithoutSlot(COMMAND, null, priced(320 * MIB))) {
            assertThat(grant.bytes()).as("below its heap's lease").isEqualTo(320 * MIB);
        }
        try (WorkerLeases.Grant grant = ledger.acquireWithoutSlot(COMMAND, null, priced(16 * GIB))) {
            assertThat(grant.bytes()).as("at most the budget").isEqualTo(8 * GIB);
        }
    }

    /**
     * A test JVM of the engine's own suite runs its engines in-process: it does their work itself, so
     * it takes a JVM slot, and their forks are still its own, so it leases its whole tree's price.
     */
    @Test
    void a_jvm_that_runs_engines_in_process_leases_its_price_and_a_slot() throws Exception {
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> 8 * GIB, () -> 8, id -> false);
        try (WorkerLeases.Grant grant = ledger.acquireRunningEngines(COMMAND, null, priced(3 * GIB))) {
            assertThat(grant.bytes()).isEqualTo(3 * GIB).isGreaterThan(WorkerLeases.jvmLease(2 * GIB));
            assertThat(grant.xmxBytes()).as("its heap is its own").isEqualTo(2 * GIB);
            assertThat(ledger.snapshot().runningJvms()).as("a slot").isEqualTo(1);
        }
        try (WorkerLeases.Grant grant = ledger.acquireRunningEngines(COMMAND, null, priced(320 * MIB))) {
            assertThat(grant.bytes()).as("below its heap's lease").isEqualTo(320 * MIB);
            assertThat(ledger.snapshot().runningJvms()).as("a slot").isEqualTo(1);
        }
        try (WorkerLeases.Grant grant = ledger.acquireRunningEngines(COMMAND, null, priced(16 * GIB))) {
            assertThat(grant.bytes()).as("at most the budget").isEqualTo(8 * GIB);
        }
    }

    /**
     * Idle warm compilers whose -Xmx leases fill the heap ceiling, while their prices leave most of
     * the budget free, exit for a fork that fits the budget: the head of the line is not left to give
     * up after ten minutes with memory free.
     */
    @Test
    void idle_residents_whose_heaps_fill_the_ceiling_exit_for_a_fork_whose_price_fits() throws Exception {
        long budget = 4 * GIB;
        WorkerLeases.Ledger ledger =
                new WorkerLeases.Ledger(() -> budget, () -> 8, id -> false).waitAtMost(Duration.ofSeconds(3));
        int fit = (int) (OverbookSignals.ceiling(budget) / WorkerLeases.jvmLease(2 * GIB));
        AtomicInteger asked = new AtomicInteger();
        List<WorkerLeases.Grant> residents = new ArrayList<>();
        try {
            for (int i = 0; i < fit; i++) {
                AtomicReference<WorkerLeases.Grant> self = new AtomicReference<>();
                self.set(ledger.acquireResident(COMMAND, priced(256 * MIB), new LeaseResident("kotlinc", () -> {
                    asked.incrementAndGet();
                    self.get().close();
                })));
                residents.add(self.get());
            }
            assertThat(ledger.snapshot().leasedBytes())
                    .isEqualTo(fit * 256 * MIB)
                    .isLessThan(budget / 2);

            CompletableFuture<WorkerLeases.Grant> next = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    next.complete(ledger.acquire(COMMAND, 231L, priced(160 * MIB)));
                } catch (InterruptedException | RuntimeException e) {
                    next.completeExceptionally(e);
                }
            });
            try (WorkerLeases.Grant granted = next.get(5, TimeUnit.SECONDS)) {
                assertThat(granted.bytes()).isEqualTo(160 * MIB);
            }
            assertThat(asked).as("residents asked to exit").hasValue(1);
        } finally {
            for (WorkerLeases.Grant g : residents) g.close();
        }
    }

    /**
     * A fork refused because the held forks' heaps fill the ceiling, not for want of budget, gives
     * up naming that, not only the budget that is free; one behind it names the first in line it
     * waited on.
     */
    @Test
    void a_fork_short_of_heap_room_gives_up_naming_it_not_only_the_free_budget() throws Exception {
        long budget = 4 * GIB;
        WorkerLeases.Ledger ledger =
                new WorkerLeases.Ledger(() -> budget, () -> 8, id -> false).waitAtMost(Duration.ofMillis(400));
        long heap = WorkerLeases.jvmLease(2 * GIB);
        int fit = (int) (OverbookSignals.ceiling(budget) / heap);
        List<WorkerLeases.Grant> held = new ArrayList<>();
        try {
            for (int i = 0; i < fit; i++) held.add(ledger.acquire(COMMAND, 3L, priced(256 * MIB)));
            String line = "needs 160 MiB with a " + WorkerLeases.format(heap) + " heap ceiling ("
                    + WorkerLeases.format(budget - fit * 256 * MIB) + " free of the 4.0 GiB worker budget, but the"
                    + " held workers' heaps may grow to " + WorkerLeases.format(fit * heap) + " of the "
                    + WorkerLeases.format(OverbookSignals.ceiling(budget)) + " allowed together); held by job #3";
            assertThatThrownBy(() -> ledger.acquire(COMMAND, 7L, priced(160 * MIB)))
                    .isInstanceOf(WorkerLeases.LeaseRefused.class)
                    .hasMessageStartingWith("job #7 waited")
                    .hasMessageContaining("for memory and gave up: " + line);

            ledger.waitAtMost(Duration.ofSeconds(30));
            CompletableFuture<WorkerLeases.Grant> first = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    first.complete(ledger.acquire(COMMAND, 7L, priced(160 * MIB)));
                } catch (InterruptedException | RuntimeException e) {
                    first.completeExceptionally(e);
                }
            });
            awaitQueued(ledger);
            ledger.waitAtMost(Duration.ofMillis(300));
            assertThatThrownBy(() -> ledger.acquire(COMMAND, 8L, priced(64 * MIB)))
                    .isInstanceOf(WorkerLeases.LeaseRefused.class)
                    .hasMessageStartingWith("job #8 waited")
                    .hasMessageContaining("gave up: needs 64 MiB behind job #7, first in line, which " + line);
            held.removeFirst().close();
            first.get(5, TimeUnit.SECONDS).close();
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
