// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The forecast and the executor have to describe the same build. Each case here pins one place they
 * did not, every one of which showed up as the same symptom — a suite priced for a runner count no
 * build would hand it.
 */
class EtaForecastParityTest {

    private static TaskForecast.Task step(String name, boolean cached) {
        return new TaskForecast.Task(name, cached ? TaskForecast.Status.CACHED : TaskForecast.Status.RUN, "", null);
    }

    private static TaskForecast.Module module(Path dir, List<TaskForecast.Task> steps) {
        return new TaskForecast.Module(dir, "ex:" + dir.getFileName(), steps, 1, 1, true, false);
    }

    /**
     * Width is the widest simultaneously-ready wave, not the count of dirty modules. A spine edit
     * makes a whole dependency chain dirty, and the executor still runs it one module at a time —
     * so each module gets the whole machine, not a thirteenth of it.
     */
    @Test
    void a_dependency_chain_is_one_module_wide() {
        Path a = Path.of("/w/a");
        Path b = Path.of("/w/b");
        Path c = Path.of("/w/c");
        Map<Path, Set<Path>> chain = Map.of(a, Set.of(), b, Set.of(a), c, Set.of(b));

        assertThat(BuildGraph.maxReadyWidth(Set.of(a, b, c), chain))
                .as("three dirty modules, but never two at once")
                .isEqualTo(1);
    }

    @Test
    void independent_modules_are_as_wide_as_they_are_many() {
        Path a = Path.of("/w/a");
        Path b = Path.of("/w/b");
        Path c = Path.of("/w/c");
        Map<Path, Set<Path>> none = Map.of(a, Set.of(), b, Set.of(), c, Set.of());

        assertThat(BuildGraph.maxReadyWidth(Set.of(a, b, c), none)).isEqualTo(3);
    }

    /** Edges that leave the dirty set are not barriers: a clean prereq is already built. */
    @Test
    void a_clean_prereq_does_not_serialize_the_dirty_set() {
        Path clean = Path.of("/w/clean");
        Path x = Path.of("/w/x");
        Path y = Path.of("/w/y");
        Map<Path, Set<Path>> edges = Map.of(x, Set.of(clean), y, Set.of(clean));

        assertThat(BuildGraph.maxReadyWidth(Set.of(x, y), edges)).isEqualTo(2);
    }

    /**
     * The population the machine is divided by. On an incremental build most of the tree is dirty
     * in the bookkeeping sense while forking no JVM at all, and counting those modules is what cut
     * every suite's runner share by an order of magnitude.
     */
    @Test
    void only_modules_with_real_work_claim_a_share_of_the_machine() {
        Path dir = Path.of("/w/m");

        assertThat(BuildEta.competesForMachine(
                        module(dir, List.of(step(TaskNames.COMPILE_JAVA, false), step(TaskNames.RUN_TESTS, false)))))
                .as("compiles and tests")
                .isTrue();
        assertThat(BuildEta.competesForMachine(
                        module(dir, List.of(step(TaskNames.COMPILE_JAVA, true), step(TaskNames.RUN_TESTS, true)))))
                .as("every step a cache hit")
                .isFalse();
        assertThat(BuildEta.competesForMachine(module(dir, List.of(step(TaskNames.RESTORE_OUTPUTS, false)))))
                .as("restoring cached outputs is not competing for the machine")
                .isFalse();
        assertThat(BuildEta.competesForMachine(module(dir, List.of())))
                .as("no steps at all")
                .isFalse();
    }
}
