// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

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
