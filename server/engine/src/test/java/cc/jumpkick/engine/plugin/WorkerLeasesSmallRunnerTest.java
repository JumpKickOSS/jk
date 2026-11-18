// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The ledger of a 2 vCPU / 7 GiB CI runner as flaq's first runs found it: a 5.7 GiB
 * worker budget under the {@code CI} bound, two idle kotlinc workers and two idle Java compiler
 * workers left by the compile, and a test JVM on the plan's 3.1 GiB heap asking 3.5 GiB. 0.53.0
 * failed it after ten minutes with "needs 3.5 GiB (4.0 GiB free of the 5.7 GiB worker budget); held
 * by kotlinc 1.3 GiB in 2 forks, java compiler worker 483 MiB in 2 forks".
 */
class WorkerLeasesSmallRunnerTest {

    private static final long MIB = 1L << 20;
    private static final long BUDGET = 5870 * MIB;
    private static final long PLAN_XMX = 3187 * MIB;
    private static final List<String> PLANNED = List.of("java", "-Xmx3187m", "Main");

    private static JvmOptions.HeapChoice priced(long bytes) {
        return new JvmOptions.HeapChoice(false, PLAN_XMX, "", bytes);
    }

    @Test
    void a_test_jvm_that_fits_once_the_idle_compilers_leave_starts() throws Exception {
        WorkerLeases.Ledger ledger = new WorkerLeases.Ledger(() -> BUDGET, () -> 2, id -> false)
                .underCi(true)
                .waitAtMost(Duration.ofSeconds(3));
        List<String> asked = new ArrayList<>();
        List<WorkerLeases.Grant> residents = new ArrayList<>();
        try {
            residents.add(resident(ledger, "kotlinc", 640 * MIB, asked));
            residents.add(resident(ledger, "kotlinc", 691 * MIB, asked));
            residents.add(measured(ledger, "java compiler worker", 242 * MIB, asked));
            residents.add(measured(ledger, "java compiler worker", 241 * MIB, asked));
            assertThat(WorkerLeases.format(BUDGET)).isEqualTo("5.7 GiB");
            assertThat(WorkerLeases.format(BUDGET - ledger.snapshot().leasedBytes()))
                    .isEqualTo("4.0 GiB");

            CompletableFuture<WorkerLeases.Grant> test = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    test.complete(ledger.acquire(PLANNED, 1L, priced(0)));
                } catch (InterruptedException | RuntimeException e) {
                    test.completeExceptionally(e);
                }
            });
            try (WorkerLeases.Grant granted = test.get(5, TimeUnit.SECONDS)) {
                assertThat(WorkerLeases.format(granted.bytes())).isEqualTo("3.5 GiB");
                assertThat(granted.bytes()).isEqualTo(WorkerLeases.jvmLease(PLAN_XMX));
            }
            assertThat(asked).as("idle workers asked to exit").containsExactly("kotlinc");
        } finally {
            for (WorkerLeases.Grant g : residents) g.close();
        }
    }

    /** An idle warm worker on the plan's heap, priced at what it is expected to hold. */
    private static WorkerLeases.Grant resident(WorkerLeases.Ledger ledger, String name, long price, List<String> asked)
            throws InterruptedException {
        AtomicReference<WorkerLeases.Grant> self = new AtomicReference<>();
        self.set(ledger.acquireResident(PLANNED, priced(price), new WorkerLeases.Resident(name, () -> {
            synchronized (asked) {
                asked.add(name);
            }
            self.get().close();
        })));
        return self.get();
    }

    /** An idle Java compiler worker, leased at what it reports using. */
    private static WorkerLeases.Grant measured(WorkerLeases.Ledger ledger, String name, long bytes, List<String> asked)
            throws InterruptedException {
        AtomicReference<WorkerLeases.Grant> self = new AtomicReference<>();
        self.set(ledger.acquireResidentBytes(bytes, new WorkerLeases.Resident(name, () -> {
            synchronized (asked) {
                asked.add(name);
            }
            self.get().close();
        })));
        return self.get();
    }
}
