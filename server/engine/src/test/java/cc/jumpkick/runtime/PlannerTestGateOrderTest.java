// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The run-tests step resolves its coverage tooling before it takes the process-wide serial test
 * gate. The order is what keeps a slow or failing JaCoCo fetch from parking every other module's
 * suite, and what keeps a failed fetch from leaving the gate held for good.
 */
class PlannerTestGateOrderTest {

    @Test
    void coverage_tooling_resolves_before_the_gate_is_taken() throws Exception {
        List<String> order = new ArrayList<>();
        String tools = PlannerTest.resolveBeforeGate(
                () -> {
                    order.add("resolve");
                    return "jacoco";
                },
                true,
                () -> order.add("gate"));
        assertThat(tools).isEqualTo("jacoco");
        assertThat(order).containsExactly("resolve", "gate");
    }

    @Test
    void a_failing_resolve_leaves_the_gate_free() {
        List<String> order = new ArrayList<>();
        assertThatThrownBy(() -> PlannerTest.resolveBeforeGate(
                        () -> {
                            throw new IOException("cannot fetch org.jacoco:org.jacoco.agent");
                        },
                        true,
                        () -> order.add("gate")))
                .isInstanceOf(IOException.class);
        assertThat(order)
                .as("the gate is never taken for a suite that will not start")
                .isEmpty();

        // The real gate too: its permit count is what every other module's suite waits on.
        int permits = BuildPlanner.TEST_GATE.availablePermits();
        assertThatThrownBy(() -> PlannerTest.resolveBeforeGate(
                        () -> {
                            throw new IOException("cannot fetch");
                        },
                        true,
                        PlannerTest::awaitTestGate))
                .isInstanceOf(IOException.class);
        assertThat(BuildPlanner.TEST_GATE.availablePermits()).isEqualTo(permits);
    }

    @Test
    void a_run_without_coverage_takes_only_the_gate_and_a_parallel_run_takes_nothing() throws Exception {
        List<String> order = new ArrayList<>();
        assertThat(PlannerTest.<String>resolveBeforeGate(null, true, () -> order.add("gate")))
                .isNull();
        assertThat(order).containsExactly("gate");

        order.clear();
        assertThat(PlannerTest.<String>resolveBeforeGate(null, false, () -> order.add("gate")))
                .isNull();
        assertThat(order).as("--parallel-tests: no serial gate").isEmpty();
    }

    @Test
    void a_suite_parked_at_a_held_gate_gives_up_when_cancelled_and_holds_nothing() {
        Semaphore gate = new Semaphore(1);
        gate.acquireUninterruptibly(); // another workspace's suite holds it
        AtomicInteger asked = new AtomicInteger();
        assertThatThrownBy(() -> PlannerTest.awaitGate(gate, () -> asked.incrementAndGet() >= 2))
                .isInstanceOf(CancellationException.class);
        assertThat(gate.availablePermits()).isZero();
        gate.release();
        PlannerTest.awaitGate(gate, () -> true);
        assertThat(gate.availablePermits())
                .as("a free gate is taken even when cancelled is asked")
                .isZero();
    }
}
