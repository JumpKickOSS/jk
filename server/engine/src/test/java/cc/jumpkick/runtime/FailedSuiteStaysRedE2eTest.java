// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.util.TestHomes;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A suite that fails under {@code -r} with no input changed stays red: the plain builds after it
 * run the suite again and fail, however the run failed (a failing test, a failing {@code @AfterAll},
 * a JVM that dies after reporting a test or before reporting any, a run whose read recording will
 * not read back and so settles under no key), until a run passes. The failure is switched
 * on through a file in the test sandbox root, which nothing keys, so every build computes the same
 * keys as the green build before it.
 */
@Tag("integration")
class FailedSuiteStaysRedE2eTest {

    private @Nullable Path toggle;

    @AfterEach
    void removeToggle() throws Exception {
        if (toggle != null) Files.deleteIfExists(toggle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fail", "afterall", "exit", "exit-early", "corrupt"})
    void a_suite_that_failed_under_redo_runs_again_and_fails_until_it_passes(String mode, @TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("failed-suite-red-cache");
        toggle = Files.createDirectories(TestHomes.root()).resolve("failed-suite-" + UUID.randomUUID());
        Files.writeString(toggle, "pass");
        Path ws = Files.createDirectories(tmp.resolve("ws")).toRealPath();
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        module(ws, "a", """
                package demo;

                import static org.junit.jupiter.api.Assertions.fail;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.MethodOrderer;
                import org.junit.jupiter.api.Order;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.TestMethodOrder;

                @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
                class ModeTest {
                    static String mode() throws Exception {
                        return Files.readString(Path.of("%s")).strip();
                    }

                    @BeforeAll
                    static void before() throws Exception {
                        if (mode().equals("exit-early")) Runtime.getRuntime().halt(7);
                    }

                    @Test
                    @Order(1)
                    void passes() {}

                    @Test
                    @Order(2)
                    void obeys() throws Exception {
                        if (mode().equals("exit")) Runtime.getRuntime().halt(7);
                        if (mode().equals("corrupt")) {
                            Files.writeString(Path.of("target/.jk/suite-reads/torn.jfr"), "not a recording");
                            fail("asked to fail with a recording that will not read");
                        }
                        if (mode().equals("fail")) fail("asked to fail");
                    }

                    @AfterAll
                    static void after() throws Exception {
                        if (mode().equals("afterall")) throw new IllegalStateException("asked to fail after all");
                    }
                }
                """.formatted(toggle.toString().replace("\\", "\\\\")));
        module(ws, "b", """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class PlainTest {
                    @Test
                    void adds() {
                        assertEquals(2, 1 + 1);
                    }
                }
                """);
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();

        Run green = build(ws, cache, false);
        assertThat(green.success).as("green first").isTrue();
        Run replayed = build(ws, cache, false);
        assertThat(replayed.runTests("a")).isIn(null, TaskStatus.SKIPPED);

        Files.writeString(toggle, mode);
        Run forced = build(ws, cache, true);
        assertThat(forced.success)
                .as("-r runs the suite, which fails (" + mode + ")")
                .isFalse();

        for (int i = 1; i <= 2; i++) {
            Run after = build(ws, cache, false);
            assertThat(after.success)
                    .as(
                            "plain build %d after the red -r build: nothing changed, so it is still red; -r ran %s, this ran %s",
                            i, forced.runTests, after.runTests)
                    .isFalse();
            assertThat(after.runTests("a"))
                    .as("the failed suite runs again (%s)", mode)
                    .isEqualTo(TaskStatus.FAIL);
            assertThat(after.runTests("b"))
                    .as("the module whose suite passed stays settled")
                    .isIn(null, TaskStatus.SKIPPED);
        }

        Files.writeString(toggle, "pass");
        Run fixed = build(ws, cache, false);
        assertThat(fixed.success).as("the suite passes again").isTrue();
        assertThat(fixed.runTests("a")).isEqualTo(TaskStatus.SUCCESS);
        Run settled = build(ws, cache, false);
        assertThat(settled.success).isTrue();
        assertThat(settled.runTests("a"))
                .as("once green, the next build replays it")
                .isIn(null, TaskStatus.SKIPPED);
    }

    private static void module(Path ws, String name, String test) throws Exception {
        Path m = Files.createDirectories(ws.resolve(name));
        Files.writeString(m.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(name));
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(
                m.resolve("src/main/java/demo/M" + name + ".java"), "package demo; public class M" + name + " {}\n");
        Path dir = Files.createDirectories(m.resolve("src/test/java/demo"));
        String cls = test.substring(test.indexOf("class ") + 6, test.indexOf(" {", test.indexOf("class ")));
        Files.writeString(dir.resolve(cls + ".java"), test);
    }

    /** One workspace build, under {@code -r} when {@code redo}, as the engine's job runs it. */
    private static Run build(Path ws, Path cache, boolean redo) throws Exception {
        Session session = Session.defaults()
                .withConfig(JkConfig.empty().withRebuild(redo))
                .withCacheDir(cache);
        Run run = new Run();
        AtomicReference<WorkspaceResult> result = new AtomicReference<>();
        SessionContext.runWhere(
                session,
                () -> result.set(Request.detached()
                        .with(SettledReads.SLOT, new SettledReads())
                        .call(() -> WorkspaceExecute.buildWorkspace(
                                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                                run))));
        run.success = result.get().success();
        return run;
    }

    /** Each module's run-tests status, by module name, and whether the build succeeded. */
    private static final class Run implements WorkspaceBuildListener {
        final Map<String, TaskStatus> runTests = new ConcurrentHashMap<>();
        boolean success;

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    if (step.equals(TaskNames.RUN_TESTS)) runTests.put(name, status);
                }
            };
        }

        @Nullable
        TaskStatus runTests(String module) {
            return runTests.get(module);
        }
    }
}
