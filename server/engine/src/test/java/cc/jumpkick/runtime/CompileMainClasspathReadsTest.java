// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.task.ActionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * compile-java keys its freshness stamp, its action-cache reweigh and its compile on the same
 * classpath lines, and every one of those lines fingerprints a classpath entry — a sibling's
 * classes tree is a whole walk. On a stamp miss the step reads the classpath once and hands the
 * lines on, rather than reading it again for each.
 */
class CompileMainClasspathReadsTest {

    @Test
    void a_compile_main_that_misses_its_stamp_reads_its_classpath_once(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                group   = "t"
                name    = "app"
                version = "0.0.1"
                java    = 25
                """);
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Path cache = tmp.resolve("cache");
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();

        AtomicLong atStart = new AtomicLong(-1);
        AtomicLong reads = new AtomicLong(-1);
        BuildPlan plan = BuildPlanner.coreBuilder(TestInputs.of(project, cache).withSkipTests(true))
                .build();
        plan.addListener(new BuildPlanListener() {
            @Override
            public void stepStart(String step, @Nullable String group, int ticks) {
                if (TaskNames.COMPILE_JAVA.equals(step)) atStart.set(ActionKey.javacClasspathReads());
            }

            @Override
            public void stepFinish(
                    String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                if (TaskNames.COMPILE_JAVA.equals(step)) reads.set(ActionKey.javacClasspathReads() - atStart.get());
            }
        });
        BuildPlanResult result = plan.run();

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        BuildLayout layout = BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
        assertThat(layout.classesDir().resolve("demo/App.class")).isRegularFile();
        assertThat(reads.get())
                .as("classpath readings in one compile-java that compiled")
                .isEqualTo(1);
    }
}
