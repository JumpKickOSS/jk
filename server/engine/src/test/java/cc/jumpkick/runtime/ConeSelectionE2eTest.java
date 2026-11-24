// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Request;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-class selection on, over a real suite of three classes: after a body edit of one main class
 * the forecast prices the class whose cone holds it and the run runs that class alone, replaying
 * the others; an edit that breaks the class still fails the run. A file a replayed class read keys
 * the suite still, and a suite that reached another host replays the classes that did not. A red
 * suite under an unmoved stamp is priced by the classes it did not pass.
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
        assertThat(build(m, cache, true)).as("compiled, no test run").isNull();
        TaskForecast.Module forecast = forecast(m, cache);
        assertThat(forecast.testCount()).isEqualTo(3);
        assertThat(forecast.rerunTests())
                .as("the forecast prices the class the run runs, as the run keys it")
                .isEqualTo(1);
        assertThat(forecast.steps())
                .filteredOn(t -> t.name().equals(TaskNames.RUN_TESTS))
                .singleElement()
                .satisfies(t -> assertThat(t.text()).endsWith("~1 test · 2 classes replayed"));
        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("CalcTest, whose cone holds Calc; OtherTest and ReflectTest, whose cone holds the Hidden"
                        + " it loads by name, replayed")
                .isEqualTo(1);
        assertThat(Jsonl.strArray(last(m), "unsound")).isEmpty();

        Files.writeString(calc, Files.readString(calc).replace("b + a", "a - b"));
        assertThat(build(m, cache))
                .as("the class the edit reaches runs and fails")
                .isEqualTo(TaskStatus.FAIL);
    }

    /**
     * A file only a replayed class reads keeps keying the suite: the run that replayed it read only
     * what the class it ran read, and had that replaced the suite's discovered inputs, an edit to the
     * file would have replayed the whole suite from its marker.
     */
    @Test
    void a_file_a_replayed_class_read_still_keys_the_suite(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("cone-selection-reads-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path data = Files.createDirectories(ws.resolve("data"));
        Path a = Files.writeString(data.resolve("a.txt"), "a");
        Files.writeString(data.resolve("b.txt"), "b");
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

                [test]
                cache = "cone"
                """);
        Path main = Files.createDirectories(m.resolve("src/main/java/demo"));
        Path other = main.resolve("Other.java");
        Files.writeString(
                main.resolve("Calc.java"), "package demo; public class Calc { public int one() { return 1; } }\n");
        Files.writeString(other, "package demo; public class Other { public int one() { return 1; } }\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        test(
                tests,
                "ATest",
                "assertEquals(1, new Calc().one());"
                        + " assertEquals(\"a\", java.nio.file.Files.readString(java.nio.file.Path.of(\"../data/a.txt\")));");
        test(
                tests,
                "BTest",
                "assertEquals(1, new Other().one());"
                        + " assertEquals(\"b\", java.nio.file.Files.readString(java.nio.file.Path.of(\"../data/b.txt\")));");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        Files.writeString(other, Files.readString(other).replace("return 1;", "return Integer.parseInt(\"1\");"));
        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("BTest, whose cone holds Other; ATest replayed")
                .isEqualTo(1);

        Files.writeString(a, "edited");
        assertThat(build(m, cache))
                .as("ATest read the file in the run before, so the edit runs it, and it fails")
                .isEqualTo(TaskStatus.FAIL);
    }

    @Test
    void a_class_keyed_by_the_program_it_starts_replays_while_runs_leave_it_out(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("cone-selection-cache");
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

                [test]
                cache = "cone"
                """);
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(m.resolve("src/main/java/demo/M.java"), "package demo; public class M {}\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        test(
                tests,
                "ReadsTest",
                "assertEquals(true, !java.nio.file.Files.readString(java.nio.file.Path.of(\"../data/fixture.txt\")).isEmpty());");
        test(tests, "ShellTest", "assertEquals(0, new ProcessBuilder(\"sh\", \"-c\", \"true\").start().waitFor());");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("no class has a record yet")
                .isEqualTo(2);

        // The first records are keyed once the run found the sh the suite starts.
        Files.writeString(data, "two");
        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.strArray(last(m), "wouldRunClasses"))
                .as("the class that read the file runs; the one keyed by its module and sh replays")
                .containsExactly("demo.ReadsTest");

        Files.writeString(data, "three");
        assertThat(build(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.strArray(last(m), "wouldRunClasses"))
                .as("the run that left ShellTest out still keys the sh it starts")
                .containsExactly("demo.ReadsTest");
    }

    @Test
    void a_suite_that_reached_another_host_is_priced_by_the_classes_the_run_runs(@TempDir Path tmp) throws Exception {
        String address = nonLoopbackAddress();
        Assumptions.assumeTrue(address != null, "this host has no address but loopback");
        Path cache = TestCaches.dir("cone-selection-network-cache");
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
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(m.resolve("src/main/java/demo/M.java"), "package demo; public class M {}\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        // Talks to this machine through an address that is not loopback: past the host, as far as
        // the recording can tell, and still hermetic.
        test(
                tests,
                "TalksTest",
                ("java.net.InetAddress at = java.net.InetAddress.getByName(\"%s\");"
                                + " try (var server = new java.net.ServerSocket(0, 1, at);"
                                + " var client = new java.net.Socket(at, server.getLocalPort());"
                                + " var accepted = server.accept()) {"
                                + " client.getOutputStream().write(1); assertEquals(1, accepted.getInputStream().read()); }")
                        .formatted(address));
        test(tests, "QuietTest", "assertEquals(2, 1 + 1);");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        assertThat(buildAsTheEngine(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("no class has a record yet")
                .isEqualTo(2);

        TaskForecast.Module forecast = forecast(m, cache);
        assertThat(forecast.steps())
                .filteredOn(t -> t.name().equals(TaskNames.RUN_TESTS))
                .singleElement()
                .satisfies(t -> assertThat(t.text())
                        .contains(TaskForecast.TESTS_USE_NETWORK)
                        .endsWith("~1 test · 1 class replayed"));
        assertThat(forecast.rerunTests())
                .as("the forecast prices the class that reached the host, as the run selects it")
                .isEqualTo(1);
        assertThat(buildAsTheEngine(m, cache)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("TalksTest runs again; QuietTest replays")
                .isEqualTo(1);
    }

    /**
     * A suite that went red under a stamp that has not moved is priced by the classes it did not
     * pass, which are all the run runs again: the classes that passed stand.
     */
    @Test
    void a_red_suite_under_the_same_stamp_is_priced_by_the_classes_it_did_not_pass(@TempDir Path tmp) throws Exception {
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
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(m.resolve("src/main/java/demo/M.java"), "package demo; public class M {}\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo"));
        test(tests, "PassTest", "assertEquals(2, 1 + 1);");
        test(tests, "OtherPassTest", "assertEquals(3, 1 + 2);");
        test(tests, "FailTest", "assertEquals(1, 2);");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        assertThat(build(m, cache)).isEqualTo(TaskStatus.FAIL);
        assertThat(Jsonl.longValue(last(m), "ran", 0)).isEqualTo(3);

        TaskForecast.Module forecast = forecast(m, cache);
        assertThat(forecast.rerunTests())
                .as("FailTest alone, as the run selects it")
                .isEqualTo(1);
        assertThat(forecast.steps())
                .filteredOn(t -> t.name().equals(TaskNames.RUN_TESTS))
                .singleElement()
                .satisfies(t -> assertThat(t.text())
                        .contains("~1 test · 2 classes replayed")
                        .endsWith(TaskForecast.LAST_RUN_FAILED));
        assertThat(build(m, cache)).isEqualTo(TaskStatus.FAIL);
        assertThat(Jsonl.longValue(last(m), "ran", 0))
                .as("FailTest runs again; PassTest and OtherPassTest stand")
                .isEqualTo(1);
    }

    /** An address of an interface that is up and not loopback, or null when there is none. */
    private static @Nullable String nonLoopbackAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                    return a.getHostAddress();
            }
        }
        return null;
    }

    /** As {@link #build}, under a request holding what its suites settle, as the engine's job runs every build. */
    private static @Nullable TaskStatus buildAsTheEngine(Path dir, Path cache) throws Exception {
        return Request.detached().with(SettledReads.SLOT, new SettledReads()).call(() -> build(dir, cache));
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

    private static TaskForecast.Module forecast(Path dir, Path cache) throws Exception {
        BuildGraph.Result graph = BuildGraph.resolve(dir, JkBuildParser.parse(dir.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return ForecastWalk.of(graph, JkStores.cacheCas(cache), actionCache, cache)
                .getFirst();
    }

    private static @Nullable TaskStatus build(Path dir, Path cache) {
        return build(dir, cache, false);
    }

    /** The build's run-tests status: null when it ran none ({@code skipTests}). */
    private static @Nullable TaskStatus build(Path dir, Path cache, boolean skipTests) {
        TaskStatus[] status = new TaskStatus[1];
        WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(dir, cache, null, 0, null, skipTests, false, 2, null, false, false),
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
