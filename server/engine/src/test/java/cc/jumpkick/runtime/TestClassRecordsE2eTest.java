// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
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
 * A real suite of two classes: the class records say which class read the fixture, looked up the
 * property and loaded the main class, and the other did none of it; once the reading class goes red,
 * the re-run under the same inputs leaves the green class out, and build history says it was
 * replayed.
 */
@Tag("integration")
class TestClassRecordsE2eTest {

    @Test
    void each_class_is_recorded_with_what_it_did_and_a_class_left_out_is_said_replayed(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("test-class-records-cache");
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
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        Files.writeString(tests.resolve("ReadsTest.java"), """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;

                class ReadsTest {
                    @Test
                    void reads() throws Exception {
                        assertEquals("absent", System.getProperty("demo.flag", "absent"));
                        assertEquals(1, new M().one());
                        assertEquals("one", Files.readString(Path.of("../data/fixture.txt")));
                    }
                }
                """);
        Files.writeString(tests.resolve("PlainTest.java"), """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class PlainTest {
                    @Test
                    void plain() {
                        assertEquals(2, 1 + 1);
                    }
                }
                """);
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(
                m.resolve("src/main/java/demo/M.java"),
                "package demo; public class M { public int one() { return 1; } }\n");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        Steps first = build(m, cache);
        assertThat(first.runTests).isEqualTo(TaskStatus.SUCCESS);
        String reads = row(m, "ReadsTest");
        String plain = row(m, "PlainTest");
        assertThat(Jsonl.bool(reads, "attributed", false)).isTrue();
        assertThat(Jsonl.strArray(reads, "reads"))
                .as("outside the module, the file by its path")
                .singleElement()
                .satisfies(p -> assertThat(p.replace('\\', '/')).endsWith("/data/fixture.txt"));
        assertThat(reads).contains("demo.flag").contains("\"demo.M\"");
        assertThat(Jsonl.strArray(plain, "reads"))
                .as("the other class read no fixture")
                .isEmpty();
        assertThat(plain).doesNotContain("demo.flag").doesNotContain("\"demo.M\"");
        assertThat(first.classes)
                .as("both ran")
                .noneMatch(TestClassResult::replayed)
                .hasSize(2);

        Files.writeString(data, "two");
        Steps red = build(m, cache);
        assertThat(red.runTests).isEqualTo(TaskStatus.FAIL);
        Steps again = build(m, cache);
        assertThat(again.runTests).as("a red suite runs again").isEqualTo(TaskStatus.FAIL);
        assertThat(again.classes)
                .as("the class that passed under these inputs is left out, and said replayed")
                .anyMatch(c -> c.className().equals("demo.PlainTest") && c.replayed() && c.replayedFrom() > 0)
                .anyMatch(c -> c.className().equals("demo.ReadsTest") && !c.replayed());
        assertThat(Jsonl.str(row(m, "ReadsTest"), "outcome")).isEqualTo("failed");
    }

    /** The module's class record of {@code simpleName}, in package {@code demo}. */
    private static String row(Path module, String simpleName) throws Exception {
        for (String line : Files.readAllLines(TestClassRecords.file(module))) {
            if (Jsonl.strArray(line, "container").equals(List.of("demo", simpleName))) return line;
        }
        throw new AssertionError("no record of demo." + simpleName);
    }

    private static Steps build(Path dir, Path cache) {
        Steps steps = new Steps();
        WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 0, null, false, false, 2, null, false, false), steps);
        return steps;
    }

    /** The run-tests step's status and the class rows the build said. */
    private static final class Steps implements WorkspaceBuildListener {
        final List<TestClassResult> classes = new ArrayList<>();
        volatile @Nullable TaskStatus runTests;

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            return new BuildPlanListener() {
                @Override
                public void testClass(String step, TestClassResult result) {
                    synchronized (Steps.this) {
                        classes.add(result);
                    }
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    if (step.equals(TaskNames.RUN_TESTS)) runTests = status;
                }
            };
        }
    }
}
