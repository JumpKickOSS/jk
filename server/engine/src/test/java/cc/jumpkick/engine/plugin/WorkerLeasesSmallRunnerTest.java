// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The ledger of a 2 vCPU / 7 GiB CI runner as flaq's first runs found it: a 5.7 GiB worker budget
 * under the {@code CI} bound, two kotlinc workers and two Java compiler workers left by the compile,
 * and a test JVM on the plan's 3.1 GiB heap. Before idle helpers stopped counting toward the bound
 * on what held heaps may grow to, such a fork waited ten minutes with "needs 3.5 GiB (4.0 GiB free of
 * the 5.7 GiB worker budget); held by kotlinc 1.3 GiB in 2 forks, java compiler worker 483 MiB in 2
 * forks" and gave up.
 */
class WorkerLeasesSmallRunnerTest {

    private static final long MIB = 1L << 20;
    private static final long BUDGET = 5870 * MIB;
    private static final long PLAN_XMX = 3187 * MIB;
    private static final List<String> PLANNED = List.of("java", "-Xmx3187m", "Main");

    private static JvmOptions.HeapChoice priced(long bytes) {
        return new JvmOptions.HeapChoice(false, PLAN_XMX, "", bytes);
    }

    private static WorkerLeases.Ledger ledger() {
        return new WorkerLeases.Ledger(() -> BUDGET, () -> 2, id -> false)
                .underCi(true)
                .waitAtMost(Duration.ofSeconds(3));
    }

    @Test
    void idle_compilers_do_not_hold_back_a_test_jvm_and_none_is_asked_to_exit() throws Exception {
        WorkerLeases.Ledger ledger = ledger();
        List<String> asked = new ArrayList<>();
        AtomicBoolean idle = new AtomicBoolean(true);
        List<WorkerLeases.Grant> residents = compilers(ledger, asked, idle);
        try {
            assertThat(WorkerLeases.format(BUDGET - ledger.snapshot().leasedBytes()))
                    .isEqualTo("4.0 GiB");
            // The kotlinc heaps (2 x 3.5 GiB) and this one's would pass the 8.6 GiB CI bound; idle, they count none.
            try (WorkerLeases.Grant granted = acquire(ledger, priced(0)).get(1, TimeUnit.SECONDS)) {
                assertThat(granted.bytes()).isEqualTo(WorkerLeases.jvmLease(PLAN_XMX));
            }
            assertThat(asked).as("idle workers asked to exit").isEmpty();
        } finally {
            for (WorkerLeases.Grant g : residents) g.close();
        }
    }

    @Test
    void a_compiler_at_work_still_counts_and_an_idle_one_is_asked_to_make_room() throws Exception {
        WorkerLeases.Ledger ledger = ledger();
        List<String> asked = new ArrayList<>();
        AtomicBoolean idle = new AtomicBoolean(false);
        List<WorkerLeases.Grant> residents = compilers(ledger, asked, idle);
        try {
            try (WorkerLeases.Grant granted = acquire(ledger, priced(0)).get(5, TimeUnit.SECONDS)) {
                assertThat(WorkerLeases.format(granted.bytes())).isEqualTo("3.5 GiB");
            }
            assertThat(asked).as("busy workers asked to exit after their run").containsExactly("kotlinc");
        } finally {
            for (WorkerLeases.Grant g : residents) g.close();
        }
    }

    @Test
    void a_cold_test_jvm_is_leased_at_its_kinds_price_not_its_heap() throws Exception {
        WorkerLeases.Ledger ledger = ledger();
        List<String> asked = new ArrayList<>();
        List<WorkerLeases.Grant> residents = compilers(ledger, asked, new AtomicBoolean(true));
        List<WorkerLeases.Grant> tests = new ArrayList<>();
        try {
            // Two cold suites at once, as two cores allow: each leases 640 MiB, the 512 MiB prior with margin.
            for (int i = 0; i < 2; i++)
                tests.add(acquire(ledger, priced(640 * MIB)).get(1, TimeUnit.SECONDS));
            assertThat(tests).allSatisfy(g -> assertThat(g.bytes()).isEqualTo(640 * MIB));
            assertThat(asked).isEmpty();
        } finally {
            for (WorkerLeases.Grant g : tests) g.close();
            for (WorkerLeases.Grant g : residents) g.close();
        }
    }

    private static CompletableFuture<WorkerLeases.Grant> acquire(WorkerLeases.Ledger ledger, JvmOptions.HeapChoice c) {
        CompletableFuture<WorkerLeases.Grant> grant = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                grant.complete(ledger.acquire(PLANNED, 1L, c));
            } catch (InterruptedException | RuntimeException e) {
                grant.completeExceptionally(e);
            }
        });
        return grant;
    }

    /** Two kotlinc workers on the plan's heap and two measured Java compiler workers, idle while {@code idle} holds. */
    private static List<WorkerLeases.Grant> compilers(
            WorkerLeases.Ledger ledger, List<String> asked, AtomicBoolean idle) throws InterruptedException {
        List<WorkerLeases.Grant> residents = new ArrayList<>();
        residents.add(resident(ledger, "kotlinc", 640 * MIB, asked, idle));
        residents.add(resident(ledger, "kotlinc", 691 * MIB, asked, idle));
        residents.add(measured(ledger, "java compiler worker", 242 * MIB, asked, idle));
        residents.add(measured(ledger, "java compiler worker", 241 * MIB, asked, idle));
        return residents;
    }

    /** A warm worker on the plan's heap, priced at what it is expected to hold. */
    private static WorkerLeases.Grant resident(
            WorkerLeases.Ledger ledger, String name, long price, List<String> asked, AtomicBoolean idle)
            throws InterruptedException {
        AtomicReference<WorkerLeases.Grant> self = new AtomicReference<>();
        self.set(ledger.acquireResident(PLANNED, priced(price), helper(name, asked, idle, self)));
        return self.get();
    }

    /** A Java compiler worker, leased at what it reports using. */
    private static WorkerLeases.Grant measured(
            WorkerLeases.Ledger ledger, String name, long bytes, List<String> asked, AtomicBoolean idle)
            throws InterruptedException {
        AtomicReference<WorkerLeases.Grant> self = new AtomicReference<>();
        self.set(ledger.acquireResidentBytes(bytes, helper(name, asked, idle, self)));
        return self.get();
    }

    private static LeaseResident helper(
            String name, List<String> asked, AtomicBoolean idle, AtomicReference<WorkerLeases.Grant> self) {
        return new LeaseResident(
                name,
                () -> {
                    synchronized (asked) {
                        asked.add(name);
                    }
                    self.get().close();
                },
                idle::get);
    }
}
