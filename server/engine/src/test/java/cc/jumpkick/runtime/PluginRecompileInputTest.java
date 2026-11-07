// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.TaskContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A step's {@code recompile} input is told on the compile goal only when a compile wrote classes:
 * its cheaper path (Helidon's empty binding) is for classes just recompiled, and the pass {@code
 * jk dev} runs for an edit to resources alone must not trade a full transform for it.
 */
class PluginRecompileInputTest {

    @Test
    void a_compile_that_found_its_classes_up_to_date_is_not_a_recompile() {
        assertThat(PlannerPlugin.recompiled(context(Map.of(BuildPlanner.BUILD_OUTCOME, "up-to-date"))))
                .isFalse();
        assertThat(PlannerPlugin.recompiled(context(
                        Map.of(BuildPlanner.BUILD_OUTCOME, "up-to-date", BuildPlanner.KOTLIN_OUTCOME, "no-sources"))))
                .isFalse();
    }

    @Test
    void a_compile_that_wrote_classes_is_a_recompile() {
        assertThat(PlannerPlugin.recompiled(context(Map.of(BuildPlanner.BUILD_OUTCOME, "built"))))
                .isTrue();
        assertThat(PlannerPlugin.recompiled(context(
                        Map.of(BuildPlanner.BUILD_OUTCOME, "up-to-date", BuildPlanner.KOTLIN_OUTCOME, "compiled"))))
                .as("one language recompiled is enough")
                .isTrue();
    }

    @Test
    void no_compile_outcome_counts_as_a_recompile() {
        assertThat(PlannerPlugin.recompiled(context(Map.of()))).isTrue();
    }

    private static TaskContext context(Map<BuildPlanKey<String>, String> values) {
        Map<BuildPlanKey<?>, Object> state = new HashMap<>(values);
        return new TaskContext() {
            @Override
            public void progress(int delta) {}

            @Override
            public void updateTicks(int additional) {}

            @Override
            public void label(@Nullable String description) {}

            @Override
            public void output(@Nullable String line) {}

            @Override
            public void warn(String code, String message) {}

            @Override
            public void error(String code, String message) {}

            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public <T> void put(BuildPlanKey<T> key, T value) {
                state.put(key, value);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Optional<T> get(BuildPlanKey<T> key) {
                return Optional.ofNullable((T) state.get(key));
            }

            @Override
            public <T> T require(BuildPlanKey<T> key) {
                return get(key).orElseThrow();
            }
        };
    }
}
