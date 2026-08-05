// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuildPlanPruneTest {

    @Test
    void terminal_keeps_upstream_closure_only() {
        BuildPlan plan = BuildPlan.builder("t")
                .addTask(Task.builder("a").build())
                .addTask(Task.builder("b").requires("a").build())
                .addTask(Task.builder("c").requires("a").build())
                .addTask(Task.builder("d").requires("b", "c").build())
                .addTask(Task.builder("leaf").requires("a").build())
                .terminal("d")
                .build();
        assertThat(plan.steps().stream().map(Task::name).toList())
                .containsExactlyInAnyOrder("a", "b", "c", "d")
                .doesNotContain("leaf");
    }

    @Test
    void unknown_terminal_fails() {
        assertThatThrownBy(() -> BuildPlan.builder("t")
                        .addTask(Task.builder("a").build())
                        .terminal("missing")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void prune_preserves_original_order() {
        List<String> order = BuildPlan.pruneToTerminal(
                        List.of(
                                Task.builder("parse").build(),
                                Task.builder("compile").requires("parse").build(),
                                Task.builder("package").requires("compile").build()),
                        "package")
                .stream()
                .map(Task::name)
                .toList();
        assertThat(order).containsExactly("parse", "compile", "package");
    }
}
