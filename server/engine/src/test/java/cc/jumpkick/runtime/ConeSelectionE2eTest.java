// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-class selection on, over a real suite of three classes: after a body edit of one main class
 * the run runs the class whose cone holds it and the class keyed by its whole module, and replays
 * the other; an edit that breaks the class still fails the run.
 */
@Tag("integration")
class ConeSelectionE2eTest {

    @Test
    void an_edit_runs_the_classes_that_reach_it_and_replays_the_rest(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("cone-selection-cache");
        Path m = Files.createDirectories(tmp.resolve("ws/m"));
        Files.writeString(m.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"

                [test]
                cache = "cone"
                """);
        Path main = Files.createDirectories(m.resolve("src/main/java/demo"));
        Path calc = main.resolve("Calc.java");
        Files.writeString(calc, "package demo; public class Calc { public int add(int a, int b) { return a + b; } }\n");
        Files.writeString(
                main.resolve("Other.java"), "package demo; public class Other { public int one() { return 1; } }\n");
        Files.writeString(
                main.resolve("Hidden.java"),
                "package demo; public class Hidden { public String toString() { return \"h\"; } }\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        test(tests, "CalcTest", "assertEquals(3, new Calc().add(1, 2));");
        test(tests, "OtherTest", "assertEquals(1, new Other().one());");
        test(
                tests,
                "ReflectTest",
                "String n = \"Hid\" + \"den\".toLowerCase();"
                        + " assertEquals(\"h\", Class.forName(\"demo.\" + n).getConstructor().newInstance().toString());");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("no class has a record yet")
                .isEqualTo(3);

        Files.writeString(calc, Files.readString(calc).replace("a + b", "b + a"));
        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("CalcTest, whose cone holds Calc, and ReflectTest, keyed by its module; OtherTest replayed")
                .isEqualTo(2);
        assertThat(Jsonl.strArray(last(m), "unsound")).isEmpty();

        Files.writeString(calc, Files.readString(calc).replace("b + a", "a - b"));
        assertThat(build(m, cache))
                .as("the class the edit reaches runs and fails")
                .isEqualTo(TaskStatus.FAIL);
    }

    private static void test(Path dir, String name, String body) throws Exception {
        Files.writeString(dir.resolve(name + ".java"), """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class %s {
                    @Test
                    void t() throws Exception {
                        %s
                    }
                }
                """.formatted(name, body));
    }

    private static String last(Path module) throws Exception {
        return Files.readAllLines(ClassShadow.log(module)).getLast();
    }

    private static @Nullable TaskStatus build(Path dir, Path cache) {
        TaskStatus[] status = new TaskStatus[1];
        WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 0, null, false, false, 2, null, false, false),
                new WorkspaceBuildListener() {
                    @Override
                    public BuildPlanListener onModuleStart(ModulePlan module) {
                        return new BuildPlanListener() {
                            @Override
                            public void stepFinish(
                                    String step,
                                    @Nullable String group,
                                    TaskStatus s,
                                    Duration duration,
                                    Duration waited) {
                                if (step.equals(TaskNames.RUN_TESTS)) status[0] = s;
                            }
                        };
                    }
                });
        return status[0];
    }
}
