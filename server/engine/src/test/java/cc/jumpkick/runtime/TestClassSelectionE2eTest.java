// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --class} across a workspace: the patterns are judged once for the run. A module without
 * the class skips; the run fails only when no module matched; a standalone project fails on the
 * spot; a {@code [test] serial-tags} partition is judged with the rest of its suite.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class TestClassSelectionE2eTest {

    /** What each module's run-tests step said and how it ended, keyed by module dir. */
    private static final class Steps implements WorkspaceBuildListener {
        final Map<Path, TaskStatus> status = new ConcurrentHashMap<>();
        final Map<Path, String> label = new ConcurrentHashMap<>();
        final Map<Path, BuildPlan> plans = new ConcurrentHashMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            plans.put(module.dir(), module.plan());
            return new BuildPlanListener() {
                @Override
                public void label(String step, String text) {
                    if (TaskNames.RUN_TESTS.equals(step)) label.put(module.dir(), text);
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus s, Duration duration, Duration waited) {
                    if (TaskNames.RUN_TESTS.equals(step)) status.put(module.dir(), s);
                }
            };
        }

        @Nullable
        TestSummary tests(Path module) {
            BuildPlan plan = plans.get(module);
            return plan == null ? null : plan.get(BuildPlanner.TEST_RESULT).orElse(null);
        }
    }

    @Test
    void a_class_present_in_one_module_runs_there_and_the_other_module_skips(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Steps steps = new Steps();

        WorkspaceResult result = test(ws, tmp, List.of("AppTest"), 0, steps);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(steps.status.get(ws.resolve("lib"))).isEqualTo(TaskStatus.SKIPPED);
        assertThat(steps.label.get(ws.resolve("lib"))).isEqualTo("no classes matched --class AppTest — skipped");
        assertThat(steps.status.get(ws.resolve("app"))).isEqualTo(TaskStatus.SUCCESS);
        assertThat(steps.tests(ws.resolve("app")))
                .as("app ran exactly the one class")
                .extracting(TestSummary::total)
                .isEqualTo(1L);
        assertThat(steps.tests(ws.resolve("lib")))
                .as("a skipped module leaves no test result")
                .isNull();
    }

    @Test
    void a_pattern_matching_nothing_anywhere_fails_the_run_with_the_typo_message(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Steps steps = new Steps();

        WorkspaceResult result = test(ws, tmp, List.of("NoSuchTest"), 0, steps);

        assertThat(result.success()).isFalse();
        assertThat(result.exitCode()).isEqualTo(Exit.TESTS_FAILED);
        assertThat(result.errors()).containsExactly("no test classes matched --class NoSuchTest");
        assertThat(result.modules())
                .as("every module finished; the verdict is the run's")
                .allMatch(m -> m.success());
        assertThat(steps.status).containsEntry(ws.resolve("lib"), TaskStatus.SKIPPED);
        assertThat(steps.status).containsEntry(ws.resolve("app"), TaskStatus.SKIPPED);
    }

    @Test
    void serial_tagged_classes_alone_satisfy_the_pattern(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Steps steps = new Steps();

        // Two runners, so app's suite shards and the serial partition runs on its own trailing
        // worker; the pattern selects only the serial classes, leaving the sharded pool empty.
        WorkspaceResult result = test(ws, tmp, List.of("Serial*"), 2, steps);

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(steps.status.get(ws.resolve("lib"))).isEqualTo(TaskStatus.SKIPPED);
        assertThat(steps.tests(ws.resolve("app")))
                .extracting(TestSummary::total)
                .isEqualTo(2L);
    }

    @Test
    void a_standalone_project_fails_on_the_spot(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path lib = ws.resolve("lib");

        Standalone missing = standalone(lib, tmp, List.of("NoSuchTest"));
        assertThat(missing.result().success()).isFalse();
        assertThat(missing.plan().get(BuildPlanner.TEST_RESULT))
                .as("the typo is the module's one test failure, as it always was")
                .hasValueSatisfying(tests -> assertThat(tests.failures())
                        .singleElement()
                        .extracting(f -> f.message())
                        .isEqualTo("no test classes matched --class NoSuchTest"));

        Standalone present = standalone(lib, tmp, List.of("LibTest"));
        assertThat(present.result().success()).isTrue();
        assertThat(present.plan().get(BuildPlanner.TEST_RESULT))
                .hasValueSatisfying(tests -> assertThat(tests.total()).isEqualTo(1L));
    }

    private static WorkspaceResult test(Path ws, Path tmp, List<String> classes, int workers, Steps steps)
            throws Exception {
        Path cache = tmp.resolve("cache");
        Session session = Session.defaults()
                .withTestSelection(TestSelection.DEFAULT.withClasses(classes))
                .withRequestedTestWorkers(workers);
        WorkspaceRequest request = new WorkspaceRequest(
                        ws, cache, null, workers, null, false, false, 2, null, false, false)
                .withTestOnly(true);
        return SessionContext.where(session, () -> WorkspaceExecute.buildWorkspace(request, steps));
    }

    /** A module's plan run on its own — no sibling modules, as a standalone project has none. */
    private record Standalone(BuildPlan plan, BuildPlanResult result) {}

    private static Standalone standalone(Path module, Path tmp, List<String> classes) throws Exception {
        Session session = Session.defaults().withTestSelection(TestSelection.DEFAULT.withClasses(classes));
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                module,
                tmp.resolve("cache"),
                module.resolve("jk.toml"),
                module.resolve("jk-lock.toml"),
                module,
                1,
                1,
                null,
                null,
                /* skipTests */ false,
                false,
                /* testOnly */ true,
                false,
                Set.of(),
                session);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        return new Standalone(plan, SessionContext.where(session, plan::run));
    }

    /**
     * lib has one plain test class; app depends on lib and carries one plain class plus two
     * {@code serial}-tagged ones behind {@code [test] serial-tags}. Locked once at the root, with
     * the members redirected to the root lock.
     */
    private static Path workspace(Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """);

        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(lib.resolve("src/com/example"));
        Files.writeString(lib.resolve("src/com/example/Lib.java"), """
                package com.example;
                public final class Lib {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        Files.createDirectories(lib.resolve("test/src/com/example"));
        Files.writeString(lib.resolve("test/src/com/example/LibTest.java"), testClass("LibTest", null));

        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [dependencies]
                lib = { workspace = true }

                [test]
                serial-tags = ["serial"]

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(app.resolve("src/com/example"));
        Files.writeString(app.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int useLib(int n) { return Lib.twice(n); }
                }
                """);
        Path appTests = Files.createDirectories(app.resolve("test/src/com/example"));
        Files.writeString(appTests.resolve("AppTest.java"), testClass("AppTest", null));
        Files.writeString(appTests.resolve("SerialOneTest.java"), testClass("SerialOneTest", "serial"));
        Files.writeString(appTests.resolve("SerialTwoTest.java"), testClass("SerialTwoTest", "serial"));

        JkBuild root = JkBuildParser.parse(ws.resolve("jk.toml"));
        BuildPlan lock =
                LockPlans.lockBuildPlan(ws, root, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("workspace lock").isTrue();
        Files.copy(ws.resolve("jk-lock.toml"), lib.resolve("jk-lock.toml"));
        Files.copy(ws.resolve("jk-lock.toml"), app.resolve("jk-lock.toml"));
        return ws;
    }

    private static String testClass(String name, @Nullable String tag) {
        String annotation = tag == null ? "" : "@org.junit.jupiter.api.Tag(\"" + tag + "\")\n";
        return """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                %sclass %s {
                    @Test void twice() { assertEquals(4, Lib.twice(2)); }
                }
                """.formatted(annotation, name);
    }
}
