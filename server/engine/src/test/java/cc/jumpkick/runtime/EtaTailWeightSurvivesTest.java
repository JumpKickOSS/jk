// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A module's packaging tail has to reach {@link WorkSchedule}, not just exist in
 * {@link EffortWeights.ModuleCost}.
 *
 * <p>It did not. The tail was populated in both cost builders and then stripped at three
 * chokepoints on the way to the scheduler — twice in {@code orderCostsLikeUnits} (the LIVE build's
 * ETA) and once in the cascade re-wrap — because each hand-rolled the conversion through a
 * four-argument constructor whose fifth argument defaults to zero. Nothing failed: a zero tail
 * prices a module as the SUM of its steps rather than its longer branch, so the only symptom was a
 * quietly larger estimate, on the exact path a user sees.
 *
 * <p>The lesson these tests encode is that the defect was in the *conversion*, so they assert
 * across it rather than on either side of it.
 */
class EtaTailWeightSurvivesTest {

    private static final Path CLI = Path.of("/cli");

    /** cli-shaped: 6 units of compile, a 10-unit suite, a 37-unit native-image. */
    private static EffortWeights.ModuleCost cliShaped() {
        return new EffortWeights.ModuleCost(CLI, Set.of(), 53, 10, 37);
    }

    @Test
    void the_scheduler_dto_carries_the_tail() {
        ModuleWorkCost dto = cliShaped().toWorkCost();
        assertThat(dto.tailWeight()).isEqualTo(37);
        // prefix 6 + max(10, 37) = 43, not the 53 sum.
        assertThat(WorkSchedule.moduleWall(dto)).isEqualTo(43);
    }

    /** Several tails hang off one jar and run together, so the branch is the longest, not the sum. */
    @Test
    void several_tails_price_as_the_longest_not_the_sum() {
        EffortWeights.ModuleCost cost = EffortWeights.costFromRunningSteps(
                CLI,
                Set.of(),
                List.of(TaskNames.NATIVE_IMAGE, TaskNames.PACKAGE_ASSEMBLY, TaskNames.PACKAGE_SOURCES),
                BuildMetrics.load(Path.of("/no/such/metrics.json")),
                null,
                List.of(),
                Map.of(),
                1);
        int nativeAlone = EffortWeights.costFromRunningSteps(
                        CLI,
                        Set.of(),
                        List.of(TaskNames.NATIVE_IMAGE),
                        BuildMetrics.load(Path.of("/no/such/metrics.json")),
                        null,
                        List.of(),
                        Map.of(),
                        1)
                .tailWeight();
        assertThat(cost.tailWeight()).as("longest tail, not Σ tails").isEqualTo(nativeAlone);
        assertThat(cost.weight()).as("the sum still counts every step").isGreaterThan(nativeAlone);
    }

    @Test
    void list_conversion_carries_the_tail() {
        List<ModuleWorkCost> dtos = EffortWeights.toWorkCosts(List.of(cliShaped()));
        assertThat(dtos).singleElement().extracting(ModuleWorkCost::tailWeight).isEqualTo(37);
    }

    /**
     * The live workspace ETA orders costs to match the scheduled units before pricing them. Both
     * arms of that ordering — matched and leftover — dropped the tail.
     */
    @Test
    void ordering_costs_for_the_live_eta_carries_the_tail() {
        // Leftover arm: no units at all, so every cost falls through to the tail of the method.
        List<ModuleWorkCost> leftover = BuildEta.orderCostsLikeUnits(List.of(), List.of(cliShaped()));
        assertThat(leftover)
                .as("the leftover arm must not strip the tail either")
                .singleElement()
                .extracting(ModuleWorkCost::tailWeight)
                .isEqualTo(37);
        assertThat(WorkSchedule.moduleWall(leftover.getFirst())).isEqualTo(43);
    }

    /**
     * The cascade re-wrap adds recheck cost to a module that is mostly cache hits. It has to add to
     * the total without forgetting what the total is made of.
     */
    @Test
    void adding_cascade_recheck_cost_carries_the_tail() {
        EffortWeights.ModuleCost heavier = cliShaped().withWeight(cliShaped().weight() + 5);
        assertThat(heavier.weight()).isEqualTo(58);
        assertThat(heavier.testWeight()).isEqualTo(10);
        assertThat(heavier.tailWeight()).isEqualTo(37);
        // prefix is now 11, so the wall grows by exactly the recheck cost and no more.
        assertThat(WorkSchedule.moduleWall(heavier.toWorkCost())).isEqualTo(48);
    }

    /**
     * The reason the bug was invisible: dropping the tail does not throw, it inflates. Pinning the
     * direction and the size means a future re-wrap that loses it fails with a number a reader can
     * act on rather than a vague "estimate changed".
     */
    @Test
    void dropping_the_tail_inflates_the_estimate_rather_than_failing() {
        EffortWeights.ModuleCost withTail = cliShaped();
        EffortWeights.ModuleCost withoutTail =
                new EffortWeights.ModuleCost(CLI, Set.of(), withTail.weight(), withTail.testWeight());

        long priced = WorkSchedule.moduleWall(withTail.toWorkCost());
        long inflated = WorkSchedule.moduleWall(withoutTail.toWorkCost());

        assertThat(priced).isEqualTo(43);
        assertThat(inflated).isEqualTo(53); // the serial sum
        assertThat(inflated - priced).isEqualTo(Math.min(withTail.testWeight(), withTail.tailWeight()));
    }
}
