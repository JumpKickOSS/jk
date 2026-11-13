// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.wire.runtime.StepCost;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A compile's price is carried to this build's worker only when its walls are another kind's (JK-D3785). */
class CompilerWarmUpTest {

    private static StepCost compile(String step, int weight) {
        return new StepCost(step, weight, StepCost.Kind.PRE, 0.05, 0, StepCost.Rung.REPLAY);
    }

    @Test
    void warm_walls_on_a_fresh_worker_gain_its_start_once_then_its_cold_jit() {
        CompilerWarmUp warmUp = new CompilerWarmUp(1.0);
        List<StepCost> priced = new ArrayList<>();
        for (int i = 0; i < CompilerWarmUp.EARLY + 3; i++) {
            priced.addAll(warmUp.apply(
                    List.of(compile("compile-java", 10), compile("compile-kotlin", 10)),
                    c -> CompilerWarmUp.Direction.ADD));
        }
        List<StepCost> javac =
                priced.stream().filter(c -> c.step().equals("compile-java")).toList();
        assertThat(javac.getFirst().weight()).as("the JVM's start, once").isEqualTo(13);
        assertThat(javac.subList(1, CompilerWarmUp.EARLY + 1))
                .allSatisfy(c -> assertThat(c.weight()).isEqualTo(20));
        assertThat(javac.subList(CompilerWarmUp.EARLY + 1, javac.size()))
                .allSatisfy(c -> assertThat(c.weight()).isEqualTo(11));
        assertThat(priced.stream().filter(c -> c.step().equals("compile-kotlin")))
                .as("another compiler's warmth is not this worker's")
                .allSatisfy(c -> assertThat(c.weight()).isEqualTo(10));
    }

    @Test
    void fresh_walls_on_a_lingering_worker_lose_the_warm_up_and_matching_walls_keep_their_price() {
        CompilerWarmUp warmUp = new CompilerWarmUp(1.0);
        List<StepCost> removed = warmUp.apply(
                List.of(compile("compile-java", 13), compile("compile-test", 20)),
                c -> CompilerWarmUp.Direction.REMOVE);
        assertThat(removed).extracting(StepCost::weight).containsExactly(10, 10);

        List<StepCost> same = List.of(compile("compile-java", 10), compile("compile-test", 10));
        assertThat(new CompilerWarmUp(1.0).apply(same, c -> CompilerWarmUp.Direction.NONE))
                .extracting(StepCost::weight)
                .containsExactly(10, 10);
    }

    @Test
    void a_build_is_priced_at_the_warmth_it_started_with() {
        CompilerWarmUp.warmthForTests(() -> 0);
        try {
            assertThat(CompilerWarmUp.warmth()).isZero();
            assertThat(CompilerWarmUp.startedWith(12, CompilerWarmUp::warmth))
                    .as("the warmth bound when the build started, not the worker's now")
                    .isEqualTo(12);
        } finally {
            CompilerWarmUp.warmthForTests(JavaCompilerHost::warmth);
        }
    }
}
