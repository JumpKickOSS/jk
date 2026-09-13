// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The texts a consumer's compile step carries when a compile-scope dependency is rebuilding: the
 * dependencies' hints folded into one line, labelled as a hint, and the plain "dependency
 * changed" when no hint can be given.
 */
class TaskForecasterHintTextTest {

    private static final Path LIB = Path.of("/ws/lib").toAbsolutePath().normalize();
    private static final Path UTIL = Path.of("/ws/util").toAbsolutePath().normalize();

    private static TaskForecaster.ModuleHint hint(String module, SourceApiIndex.Kind kind, String... files) {
        return new TaskForecaster.ModuleHint(module, new SourceApiIndex.Hint(kind, List.of(files)));
    }

    @Test
    void body_only_dependencies_read_as_likely_up_to_date() {
        TaskForecaster.DepHint h = TaskForecaster.depHint(
                List.of(LIB, UTIL),
                Map.of(
                        LIB, hint("lib", SourceApiIndex.Kind.BODY_ONLY),
                        UTIL, hint("util", SourceApiIndex.Kind.BODY_ONLY)));
        assertThat(h.kind()).isEqualTo(SourceApiIndex.Kind.BODY_ONLY);
        assertThat(h.text()).isEqualTo("likely up to date · body-only edit in lib, util (hint)");
        assertThat(TaskForecaster.dependencyChanged(h)).isEqualTo(h.text());
    }

    @Test
    void an_api_change_wins_and_names_the_files() {
        TaskForecaster.DepHint h = TaskForecaster.depHint(
                List.of(LIB, UTIL),
                Map.of(
                        LIB, hint("lib", SourceApiIndex.Kind.API_CHANGED, "src/com/example/Lib.java"),
                        UTIL, hint("util", SourceApiIndex.Kind.BODY_ONLY)));
        assertThat(h.kind()).isEqualTo(SourceApiIndex.Kind.API_CHANGED);
        assertThat(h.text()).isEqualTo("likely recompile · API changed in lib: Lib.java (hint)");
    }

    @Test
    void an_unclassified_dependency_leaves_no_hint() {
        TaskForecaster.DepHint unknown =
                TaskForecaster.depHint(List.of(LIB, UTIL), Map.of(LIB, hint("lib", SourceApiIndex.Kind.BODY_ONLY)));
        assertThat(unknown).isEqualTo(TaskForecaster.DepHint.NONE);
        assertThat(TaskForecaster.dependencyChanged(unknown)).isEqualTo("recompile · dependency changed");
        assertThat(TaskForecaster.depHint(List.of(), Map.of())).isEqualTo(TaskForecaster.DepHint.NONE);
    }

    @Test
    void a_stamp_language_step_reads_its_hit_miss_and_hint_the_same_way() {
        TaskForecaster.DepHint body =
                TaskForecaster.depHint(List.of(LIB), Map.of(LIB, hint("lib", SourceApiIndex.Kind.BODY_ONLY)));
        TaskForecast.Task depDirtyHit =
                TaskForecaster.langCompileStep("compile-kotlin", true, "0123456789abcdef", 3, true, "", body);
        assertThat(depDirtyHit.status()).isEqualTo(TaskForecast.Status.RUN);
        assertThat(depDirtyHit.text()).isEqualTo(body.text());
        TaskForecast.Task hit = TaskForecaster.langCompileStep(
                "compile-kotlin", true, "0123456789abcdef", 3, false, "", TaskForecaster.DepHint.NONE);
        assertThat(hit.cached()).isTrue();
        assertThat(hit.key()).isEqualTo("01234567");
        TaskForecast.Task miss = TaskForecaster.langCompileStep(
                "compile-groovy",
                false,
                "k",
                2,
                false,
                "dependency ABI changed (lib.jar)",
                TaskForecaster.DepHint.NONE);
        assertThat(miss.status()).isEqualTo(TaskForecast.Status.FULL);
        assertThat(miss.text()).isEqualTo("full compile · 2 sources · dependency ABI changed (lib.jar)");
    }
}
