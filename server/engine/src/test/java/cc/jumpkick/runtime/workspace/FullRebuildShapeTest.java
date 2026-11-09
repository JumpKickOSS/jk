// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.base.ScheduleBias;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A full build, for the ETA's learned correction, is one that starts from nothing. A module that
 * compiles whole over outputs it already has is an incremental build's shape.
 */
class FullRebuildShapeTest {

    @Test
    void only_a_compile_with_nothing_to_compile_against_starts_from_scratch() {
        assertThat(BuildEta.fromScratch("full compile · 12 sources · no zinc analysis"))
                .isTrue();
        assertThat(BuildEta.fromScratch(
                        "full compile · 3 sources · no prior compile record · processors: a.Gen (gen.jar)"))
                .isTrue();
        assertThat(BuildEta.fromScratch("full compile · 7 sources"))
                .as("a cold Kotlin compile")
                .isTrue();
        assertThat(BuildEta.fromScratch("full compile · 7 sources · no plan: lib.jar missing"))
                .isTrue();
        assertThat(BuildEta.fromScratch("full compile · 40 sources · aggregating annotation processors"))
                .isFalse();
        assertThat(BuildEta.fromScratch("full compile · 2 sources · dependency ABI changed (lib.jar)"))
                .isFalse();
    }

    @Test
    void a_cascade_compiling_whole_over_its_outputs_is_its_own_shape() {
        var whole = new TaskForecast.Task(
                "compile-java",
                TaskForecast.Status.FULL,
                "full compile · 9 sources · aggregating annotation processors",
                null);
        var scratch = new TaskForecast.Task(
                "compile-java", TaskForecast.Status.FULL, "full compile · 9 sources · no zinc analysis", null);
        var partial =
                new TaskForecast.Task("compile-java", TaskForecast.Status.PARTIAL, "compile · 1 source changed", null);
        assertThat(BuildEta.buildShape(plan(whole, whole, partial))).isEqualTo(ScheduleBias.Shape.WHOLE);
        assertThat(BuildEta.buildShape(plan(scratch, scratch, whole))).isEqualTo(ScheduleBias.Shape.FULL);
        assertThat(BuildEta.buildShape(plan(partial, partial, whole))).isEqualTo(ScheduleBias.Shape.INCREMENTAL);
    }

    private static ExplainPlan plan(TaskForecast.Task... compiles) {
        List<TaskForecast.Module> modules = new ArrayList<>();
        for (int i = 0; i < compiles.length; i++) {
            modules.add(new TaskForecast.Module(Path.of("/m" + i), "g:m" + i, List.of(compiles[i]), 1, 1, true, false));
        }
        return new ExplainPlan(modules, Map.of(), 1, List.of());
    }
}
