// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The estimate re-prices when a module it guessed at can be read off a sibling's outputs (JK-0PDBN). */
class ProvisionalWatchTest {

    private static final Path CORE = Path.of("/ws/core");
    private static final Path APP = Path.of("/ws/app");
    private static final Path CLI = Path.of("/ws/cli");

    private static TaskForecast.Module module(Path dir, String text) {
        return new TaskForecast.Module(
                dir,
                "g:" + dir.getFileName(),
                List.of(new TaskForecast.Task(TaskNames.COMPILE_JAVA, TaskForecast.Status.RUN, text, null)),
                1,
                0,
                true,
                false);
    }

    @Test
    void a_guessed_verdict_is_provisional_and_a_lookup_is_not() {
        assertThat(new TaskForecast.Task(
                                "compile-java",
                                TaskForecast.Status.RUN,
                                "likely recompile · API changed in core: Core.java (hint)",
                                null)
                        .provisional())
                .isTrue();
        assertThat(new TaskForecast.Task(
                                "compile-java", TaskForecast.Status.RUN, "recompile · dependency changed", null)
                        .provisional())
                .isTrue();
        assertThat(new TaskForecast.Task(
                                "compile-java", TaskForecast.Status.PARTIAL, "compile · 1 source changed", null)
                        .provisional())
                .isFalse();
    }

    @Test
    void a_module_priced_on_a_guess_waits_on_the_siblings_it_reads() {
        WorkspaceResourcePhase.ProvisionalWatch watch = new WorkspaceResourcePhase.ProvisionalWatch();
        watch.priced(new ExplainPlan(
                List.of(
                        module(CORE, "compile · 1 source changed"),
                        module(APP, "likely recompile · API changed in core: Core.java (hint)"),
                        module(CLI, "compile · 2 sources changed")),
                Map.of(APP, Set.of(CORE), CLI, Set.of(CORE)),
                2,
                List.of()));
        assertThat(watch.waitsOn(CORE)).as("app's verdict is a guess on core").isTrue();
        assertThat(watch.waitsOn(APP)).as("nothing guessed on app").isFalse();

        watch.priced(new ExplainPlan(
                List.of(module(APP, "compile · 3 sources changed")), Map.of(APP, Set.of(CORE)), 1, List.of()));
        assertThat(watch.waitsOn(CORE))
                .as("the re-pricing read core's outputs: nothing guessed")
                .isFalse();
    }
}
