// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The step names the JVM flags its test JVMs were started with, once per distinct set. */
class TestJvmFlagsTest {

    private static List<String> command(String tmp, String xmx) {
        return List.of(
                "/jdk/bin/java",
                "-Xmx" + xmx,
                "-Djava.io.tmpdir=" + tmp,
                WorkerGc.FLAG_PREFIX + tmp + "/gc.log",
                "-Djk.plugin.class=cc.jumpkick.test.TestRunner",
                "-cp",
                "/a.jar:/b.jar",
                "cc.jumpkick.plugin.process.PluginMain",
                "--scan-classpath=/classes");
    }

    @Test
    void a_line_names_the_flags_before_the_classpath_and_workers_that_differ_only_by_paths_share_it() {
        List<String> output = new ArrayList<>();
        TaskContext ctx = recording(output);
        StepScope.open(ctx);
        try {
            TestJvmFlags.note(ctx, command("/t/w0", "1g"));
            TestJvmFlags.note(ctx, command("/t/w1", "1g"));
            TestJvmFlags.note(ctx, command("/t/w0", "2g"));
        } finally {
            StepScope.close();
        }
        assertThat(output)
                .containsExactly(
                        TestJvmFlags.PREFIX + "-Xmx1g -Djava.io.tmpdir=/t/w0 " + WorkerGc.FLAG_PREFIX
                                + "/t/w0/gc.log -Djk.plugin.class=cc.jumpkick.test.TestRunner",
                        TestJvmFlags.PREFIX + "-Xmx2g -Djava.io.tmpdir=/t/w0 " + WorkerGc.FLAG_PREFIX
                                + "/t/w0/gc.log -Djk.plugin.class=cc.jumpkick.test.TestRunner");
    }

    @Test
    void the_runner_recognizes_a_test_jvm() {
        assertThat(WorkerRss.describe(command("/t", "1g"))).isEqualTo(WorkerRss.TEST_JVM);
    }

    private static TaskContext recording(List<String> output) {
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
            public void warn(String code, String message) {}

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
