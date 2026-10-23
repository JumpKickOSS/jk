// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite that reads a file nothing declares: the run that finds it stores its marker under a key
 * that folds the file in, the next build replays it, so does a build after a clean, and an edit to
 * the file runs the suite again; once the suite stops reading the file, it keys it no more.
 */
@Tag("integration")
class SuiteDiscoveryE2eTest {

    @Test
    void a_file_a_suite_reads_keys_it_once_found(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("suite-discovery-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path data = Files.createDirectories(ws.resolve("data")).resolve("fixture.txt");
        Files.writeString(data, "one");
        Path m = Files.createDirectories(ws.resolve("m"));
        Files.writeString(m.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path test = Files.createDirectories(m.resolve("src/test/java/demo")).resolve("ReadsTest.java");
        Files.writeString(test, """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;

                class ReadsTest {
                    @Test
                    void reads() throws Exception {
                        assertEquals("one", Files.readString(Path.of("../data/fixture.txt")));
                    }
                }
                """);
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(m.resolve("src/main/java/demo/M.java"), "package demo; public class M {}\n");
        JkBuild build = JkBuildParser.parse(m.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(m, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        Steps first = build(m, cache);
        assertThat(first.status(TaskNames.RUN_TESTS)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(first.warnings)
                .as("keying what a suite reads is jk's own bookkeeping")
                .isEmpty();

        Steps second = build(m, cache);
        assertThat(second.status(TaskNames.RUN_TESTS))
                .as("nothing it reads moved: the module is not scheduled, or its suite replays")
                .isIn(null, TaskStatus.SKIPPED);

        PathUtil.deleteRecursively(m.resolve("target"));
        Steps afterClean = build(m, cache);
        assertThat(afterClean.status(TaskNames.RUN_TESTS))
                .as("a clean keeps what the suite was found to read: the same key, replayed")
                .isIn(null, TaskStatus.SKIPPED);

        Files.writeString(data, "two");
        Steps third = build(m, cache);
        assertThat(third.status(TaskNames.RUN_TESTS))
                .as("the file it reads changed")
                .isEqualTo(TaskStatus.FAIL);

        Files.writeString(
                test,
                Files.readString(test)
                        .replace(
                                "assertEquals(\"one\", Files.readString(Path.of(\"../data/fixture.txt\")));",
                                "assertEquals(1, 1);"));
        Steps rewritten = build(m, cache);
        assertThat(rewritten.status(TaskNames.RUN_TESTS))
                .as("its source changed")
                .isEqualTo(TaskStatus.SUCCESS);
        Steps settled = build(m, cache);
        assertThat(settled.status(TaskNames.RUN_TESTS))
                .as("the whole run that stopped reading the file keyed what it reads now")
                .isIn(null, TaskStatus.SKIPPED);
        Files.writeString(data, "three");
        Steps unread = build(m, cache);
        assertThat(unread.status(TaskNames.RUN_TESTS))
                .as("a file the suite no longer reads keys nothing")
                .isIn(null, TaskStatus.SKIPPED);
    }

    private static Steps build(Path dir, Path cache) {
        Steps steps = new Steps();
        WorkspaceResult ignored = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 0, null, false, false, 2, null, false, false), steps);
        return steps;
    }

    /** The run-tests step's status and the warnings the build raised. */
    private static final class Steps implements WorkspaceBuildListener {
        final List<String> warnings = new ArrayList<>();
        private @Nullable TaskStatus runTests;

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            return new BuildPlanListener() {
                @Override
                public void warn(String step, String code, String message) {
                    synchronized (Steps.this) {
                        warnings.add(message);
                    }
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    if (step.equals(TaskNames.RUN_TESTS)) {
                        synchronized (Steps.this) {
                            runTests = status;
                        }
                    }
                }
            };
        }

        synchronized @Nullable TaskStatus status(String step) {
            return runTests;
        }
    }
}
