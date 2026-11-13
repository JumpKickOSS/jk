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

/** A step whose forks waited 5 s or more for memory records one summed line when it finishes. */
class MemoryNotesTest {

    @Test
    void two_long_memory_waits_become_one_line_and_a_short_one_writes_nothing() {
        List<String> output = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        TaskContext ctx = recording(output, warnings);
        StepScope.run(ctx, () -> {
            MemoryNotes.recordWait(4_900_000_000L, 0);
            MemoryNotes.recordWait(6_000_000_000L, 0);
            MemoryNotes.recordWait(12_000_000_000L, 0);
        });
        assertThat(output).containsExactly("waited 6s for memory", "waited 12s for memory");
        String phrase = MemoryNotes.waitedPhrase(18_000_000_000L);
        if (WorkerContainment.leaseBudget().source() == WorkerContainment.BudgetSource.OVERRIDE) {
            phrase = phrase + " (" + WorkerContainment.BUDGET_ENV + ")";
        }
        assertThat(warnings).containsExactly("memory-wait " + phrase);
        StepScope.run(ctx, () -> {});
        assertThat(warnings).hasSize(1);
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
