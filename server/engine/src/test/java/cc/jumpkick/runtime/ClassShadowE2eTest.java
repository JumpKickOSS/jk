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
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-class selection in shadow over a real suite of four classes. After an edit the suite runs whole,
 * as its stamp says; the shadow says which classes per-class selection would have run, and every
 * class whose outcome the edit changed (here: went red) is among them, while classes the edit cannot
 * reach are not. A class that loads a class by a computed name is keyed by its whole module.
 */
@Tag("integration")
class ClassShadowE2eTest {

    @Test
    void every_class_an_edit_turns_red_is_one_per_class_selection_would_have_run(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("class-shadow-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path data = Files.createDirectories(ws.resolve("data")).resolve("x.txt");
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
        Path main = Files.createDirectories(m.resolve("src/main/java/demo"));
        Path calc = main.resolve("Calc.java");
        Files.writeString(calc, "package demo; public class Calc { public int add(int a, int b) { return a + b; } }\n");
        Files.writeString(
                main.resolve("Other.java"), "package demo; public class Other { public int one() { return 1; } }\n");
        Path hidden = main.resolve("Hidden.java");
        Files.writeString(hidden, "package demo; public class Hidden { public String toString() { return \"h\"; } }\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        test(tests, "CalcTest", "assertEquals(3, new Calc().add(1, 2));");
        test(tests, "OtherTest", "assertEquals(1, new Other().one());");
        test(
                tests,
                "DataTest",
                "assertEquals(\"one\", java.nio.file.Files.readString(java.nio.file.Path.of(\"../data/x.txt\")));");
        test(
                tests,
                "ReflectTest",
                "String n = \"Hid\" + \"den\".toLowerCase();"
                        + " assertEquals(\"h\", Class.forName(\"demo.\" + n).getConstructor().newInstance().toString());");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "wouldRun", 0))
                .as("no class has a record yet")
                .isEqualTo(4);

        Files.writeString(calc, Files.readString(calc).replace("a + b", "a - b"));
        assertThat(build(m, cache)).isEqualTo(TaskStatus.FAIL);
        String body = last(m);
        Map<String, String> why = why(m);
        assertThat(Jsonl.strArray(body, "changed")).containsExactly("demo.CalcTest");
        assertThat(why).as("the class whose cone holds Calc").containsKey("demo.CalcTest");
        assertThat(why).as("no cone holds Calc").doesNotContainKeys("demo.OtherTest", "demo.DataTest");
        assertThat(why.get("demo.ReflectTest")).as("a computed class name").startsWith("module reflective");
        assertThat(Jsonl.strArray(body, "unsound")).isEmpty();

        Files.writeString(calc, Files.readString(calc).replace("a - b", "a + b"));
        Files.writeString(data, "two");
        assertThat(build(m, cache)).isEqualTo(TaskStatus.FAIL);
        String read = last(m);
        assertThat(Jsonl.strArray(read, "changed")).containsExactlyInAnyOrder("demo.CalcTest", "demo.DataTest");
        assertThat(why(m)).containsKeys("demo.CalcTest", "demo.DataTest").doesNotContainKey("demo.OtherTest");
        assertThat(Jsonl.strArray(read, "unsound")).isEmpty();

        Files.writeString(data, "one");
        Files.writeString(hidden, Files.readString(hidden).replace("\"h\"", "\"i\""));
        assertThat(build(m, cache)).isEqualTo(TaskStatus.FAIL);
        String reflect = last(m);
        assertThat(Jsonl.strArray(reflect, "changed")).contains("demo.ReflectTest");
        assertThat(why(m)).containsKey("demo.ReflectTest").doesNotContainKey("demo.OtherTest");
        assertThat(Jsonl.strArray(reflect, "unsound")).isEmpty();
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

    /** Why per-class selection would have run each class, by its record of the last run; a class it would replay is absent. */
    private static Map<String, String> why(Path module) {
        Map<String, String> out = new TreeMap<>();
        TestClassRecords.rows(TestClassRecords.file(module)).forEach((key, row) -> {
            String why = Jsonl.str(row, "wouldRun");
            if (why != null) out.put(key.substring(key.indexOf('|') + 1), why);
        });
        return out;
    }

    private static String last(Path module) throws Exception {
        List<String> lines = Files.readAllLines(ClassShadow.log(module));
        return lines.getLast();
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
