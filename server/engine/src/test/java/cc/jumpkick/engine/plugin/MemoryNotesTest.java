// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A step that waited more than once records one summed line when it finishes. */
class MemoryNotesTest {

    @Test
    void two_grants_become_one_line_and_a_short_wait_is_only_counted() {
        List<String> output = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        TaskContext ctx = recording(output, warnings);
        StepScope.open(ctx);
        try {
            WorkerLeases.recordWait(ctx, 200_000_000L);
            WorkerLeases.recordWait(ctx, 2_000_000_000L);
            WorkerLeases.recordWait(ctx, 1_000_000_000L);
        } finally {
            StepScope.close();
        }
        assertThat(output).containsExactly("waited 2s for memory", "waited 1s for memory");
        String phrase = WorkerLeases.waitedPhrase(3_000_000_000L);
        if (WorkerContainment.leaseBudget().source() == WorkerContainment.BudgetSource.OVERRIDE) {
            phrase = phrase + " (" + WorkerContainment.BUDGET_ENV + ")";
        }
        assertThat(warnings).containsExactly("memory-wait " + phrase);
        StepScope.open(ctx);
        StepScope.close();
        assertThat(warnings).hasSize(1);
    }

    /** A wait whose step never closes its scope goes with the step, not with the engine. */
    @Test
    void a_wait_on_a_step_that_never_closes_does_not_outlive_the_step() throws Exception {
        int before = MemoryNotes.held();
        MemoryNotes.add(recording(new ArrayList<>(), new ArrayList<>()), 2_000_000_000L);
        assertThat(MemoryNotes.held()).isEqualTo(before + 1);

        for (int i = 0; i < 50 && MemoryNotes.held() > before; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(MemoryNotes.held()).isEqualTo(before);
    }

    private static TaskContext recording(List<String> output, List<String> warnings) {
        return new TaskContext() {
            @Override
            public void progress(int delta) {}

            @Override
            public void updateTicks(int additional) {}

            @Override
            public void label(@Nullable String description) {}

            @Override
            public void output(@Nullable String line) {
                if (line != null) output.add(line);
            }

            @Override
            public void waited(Duration blocked) {}

            @Override
            public void warn(String code, String message) {
                warnings.add(code + " " + message);
            }

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
}
