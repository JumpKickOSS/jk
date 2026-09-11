// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskForecastDirtyTest {

    @Test
    void bookkeeping_only_is_not_dirty() {
        var m = new TaskForecast.Module(
                Path.of("/m"),
                "g:a",
                List.of(
                        new TaskForecast.Task("parse-build", TaskForecast.Status.RUN, "", null),
                        new TaskForecast.Task("resolve-deps", TaskForecast.Status.RUN, "", null),
                        new TaskForecast.Task("write-stamp", TaskForecast.Status.RUN, "", null),
                        new TaskForecast.Task("compile-java", TaskForecast.Status.CACHED, "hit", "abcd"),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.CACHED, "hit", "efgh"),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "hit", "ijkl")),
                10,
                5,
                true,
                false);
        assertThat(m.dirty()).isFalse();
    }

    @Test
    void material_run_is_dirty() {
        var m = new TaskForecast.Module(
                Path.of("/m"),
                "g:a",
                List.of(
                        new TaskForecast.Task("parse-build", TaskForecast.Status.RUN, "", null),
                        new TaskForecast.Task("compile-java", TaskForecast.Status.CACHED, "hit", "abcd"),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.RUN, "stale", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "hit", "ijkl")),
                10,
                5,
                true,
                false);
        assertThat(m.dirty()).isTrue();
    }

    @Test
    void resource_drift_is_dirty() {
        // copy-resources is emitted only on real drift — it must schedule the module or the
        // jar ships stale resource bytes while the build reports up-to-date.
        var m = new TaskForecast.Module(
                Path.of("/m"),
                "g:a",
                List.of(
                        new TaskForecast.Task("compile-java", TaskForecast.Status.CACHED, "hit", "abcd"),
                        new TaskForecast.Task("copy-resources", TaskForecast.Status.RUN, "resources changed", null),
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "hit", "ijkl")),
                10,
                5,
                true,
                false);
        assertThat(m.dirty()).isTrue();
    }

    @Test
    void test_resource_drift_is_dirty() {
        var m = new TaskForecast.Module(
                Path.of("/m"),
                "g:a",
                List.of(
                        new TaskForecast.Task("compile-java", TaskForecast.Status.CACHED, "hit", "abcd"),
                        new TaskForecast.Task(
                                "copy-test-resources", TaskForecast.Status.RUN, "test resources changed", null),
                        new TaskForecast.Task("run-tests", TaskForecast.Status.CACHED, "hit", "efgh")),
                10,
                5,
                true,
                false);
        assertThat(m.dirty()).isTrue();
    }

    /** The preflight scheduled it for a reason of its own; cached steps do not talk it out of that. */
    @Test
    void a_preflight_reason_alone_makes_the_module_dirty() {
        var m = new TaskForecast.Module(
                Path.of("/m"),
                "g:a",
                List.of(new TaskForecast.Task("compile-main", TaskForecast.Status.CACHED, "", "x")),
                1,
                0,
                true,
                false);
        assertThat(m.dirty()).isFalse();
        assertThat(m.withReason("rebuilt because the preflight could not read /m/jk-lock.toml (x)")
                        .dirty())
                .isTrue();
    }

    @Test
    void native_image_run_is_dirty() {
        var m = new TaskForecast.Module(
                Path.of("/m"),
                "g:a",
                List.of(
                        new TaskForecast.Task("package-jar", TaskForecast.Status.CACHED, "hit", "x"),
                        new TaskForecast.Task("native-image", TaskForecast.Status.RUN, "miss", null)),
                1,
                0,
                true,
                false);
        assertThat(m.dirty()).isTrue();
    }
}
