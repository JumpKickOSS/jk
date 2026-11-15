// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.JkDirs;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first build of a checkout, then the same build again with nothing changed: the second
 * compiles nothing and runs no suite, though the first build's suites read files nothing declares
 * and git rewrote its index while they ran.
 */
@Tag("integration")
class UnchangedRebuildE2eTest {

    private static final List<String> WORK =
            List.of(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_TEST, TaskNames.RUN_TESTS);

    @Test
    void a_second_build_of_an_unchanged_checkout_runs_nothing(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("unchanged-rebuild-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path git = Files.createDirectories(ws.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("index"), "one");
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["a", "b"]
                """);
        module(ws, "a", "", """
                class ATest {
                    @Test
                    void passes() {
                        assertEquals(1, 1);
                    }
                }
                """);
        // Reads what a git-aware test reads, and swaps the index as `git status` does mid-run.
        module(ws, "b", "a = { workspace = true }", """
                class BTest {
                    @Test
                    void reads_the_checkout() throws Exception {
                        assertEquals("ref: refs/heads/main\\n", Files.readString(Path.of("../.git/HEAD")));
                        Path lock = Path.of("../.git/index.lock");
                        Files.writeString(lock, "two");
                        Files.move(lock, Path.of("../.git/index"), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                """);
        lock(ws, cache);

        Steps first = build(ws, cache);
        assertThat(first.status("b", TaskNames.RUN_TESTS)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(first.warnings)
                .as("git's own files moving is no input moving")
                .isEmpty();

        Steps second = build(ws, cache);
        for (String module : List.of("a", "b")) {
            for (String step : WORK) {
                assertThat(second.status(module, step))
                        .as(module + " " + step + " on the second build; ran: " + second.ran())
                        .isIn(null, TaskStatus.SKIPPED);
            }
        }
    }

    @Test
    void a_suite_that_writes_beside_its_sources_is_told_why_it_runs_again(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("unchanged-rebuild-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["c"]
                """);
        Path c = module(ws, "c", "", """
                class CTest {
                    @Test
                    void reports_outside_target() throws Exception {
                        assertEquals("data", Files.readString(Path.of("data.txt")));
                        Files.createDirectories(Path.of("build"));
                        Files.writeString(Path.of("build", "report.txt"), "ran");
                    }
                }
                """);
        Files.writeString(c.resolve("data.txt"), "data");
        lock(ws, cache);

        Steps first = build(ws, cache);
        assertThat(first.status("c", TaskNames.RUN_TESTS)).isEqualTo(TaskStatus.SUCCESS);
        assertThat(first.warnings)
                .as("the run's own write moved the listing it is keyed on")
                .contains("an input of run-tests changed while it ran: c/build; the next build runs it again");

        Steps second = build(ws, cache);
        assertThat(second.status("c", TaskNames.RUN_TESTS)).isEqualTo(TaskStatus.SUCCESS);
        Steps third = build(ws, cache);
        assertThat(third.status("c", TaskNames.RUN_TESTS))
                .as("the listing held still through the second run")
                .isIn(null, TaskStatus.SKIPPED);
    }

    @Test
    void a_suite_that_reads_jks_own_state_is_warned_and_not_keyed_on_it(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("unchanged-rebuild-cache");
        Path endpoint = Files.createDirectories(JkDirs.state().resolve("engine"))
                .resolve("e2e-" + Long.toHexString(System.nanoTime()) + ".endpoint");
        Files.writeString(endpoint, "k.gen1.sock\n");
        try {
            Path ws = Files.createDirectories(tmp.resolve("ws"));
            Files.writeString(ws.resolve("jk.toml"), """
                    group   = "com.example"
                    name    = "ws"
                    version = "1.0.0"
                    java    = 25

                    [workspace]
                    modules = ["d"]
                    """);
            module(ws, "d", "", """
                    class DTest {
                        @Test
                        void reads_the_engine_endpoint() throws Exception {
                            Files.readString(Path.of("%s"));
                        }
                    }
                    """.formatted(endpoint.toString().replace('\\', '/')));
            lock(ws, cache);

            Steps first = build(ws, cache);
            assertThat(first.status("d", TaskNames.RUN_TESTS)).isEqualTo(TaskStatus.SUCCESS);
            assertThat(first.notes).as("a read of jk's own state is named").anySatisfy(n -> assertThat(n)
                    .startsWith("tests read jk's own state, not keyed: engine/e2e-"));

            // A new engine generation takes over the endpoint.
            Files.writeString(endpoint, "k.gen2.sock\n");
            Steps second = build(ws, cache);
            assertThat(second.status("d", TaskNames.RUN_TESTS))
                    .as("an engine takeover is no input of the suite; ran: " + second.ran())
                    .isIn(null, TaskStatus.SKIPPED);
        } finally {
            Files.deleteIfExists(endpoint);
        }
    }

    /** A module {@code name} under {@code ws} with one main class and the test class {@code test}. */
    private static Path module(Path ws, String name, String dependency, String test) throws IOException {
        Path m = Files.createDirectories(ws.resolve(name));
        Files.writeString(m.resolve("jk.toml"), """
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                javadoc = false

                [dependencies]
                %s

                [test-dependencies]
                junit-jupiter = "6.1.3"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(name, dependency));
        Path main = Files.createDirectories(m.resolve("src/main/java/demo/" + name));
        Files.writeString(main.resolve("M.java"), "package demo." + name + "; public class M {}\n");
        Path tests = Files.createDirectories(m.resolve("src/test/java/demo/" + name));
        String cls = test.substring(test.indexOf("class ") + 6, test.indexOf(' ', test.indexOf("class ") + 6));
        Files.writeString(tests.resolve(cls + ".java"), """
                package demo.%s;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardCopyOption;
                import org.junit.jupiter.api.Test;

                %s""".formatted(name, test));
        return m;
    }

    private static void lock(Path ws, Path cache) throws Exception {
        BuildPlan lock = LockPlans.lockBuildPlan(ws, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    private static Steps build(Path ws, Path cache) {
        Steps steps = new Steps();
        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false), steps);
        assertThat(result.success()).as("build: " + result.errors()).isTrue();
        return steps;
    }

    /** Each module's step statuses, keyed {@code module/step}, every warning, and the {@link InputGuard#MOVED} ones. */
    private static final class Steps implements WorkspaceBuildListener {
        final List<String> warnings = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        private final Map<String, TaskStatus> statuses = new TreeMap<>();

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            String name = module.dir().getFileName().toString();
            return new BuildPlanListener() {
                @Override
                public void warn(String step, String code, String message) {
                    synchronized (Steps.this) {
                        notes.add(message);
                        if (code.equals(InputGuard.MOVED)) warnings.add(message);
                    }
                }

                @Override
                public void stepFinish(
                        String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                    synchronized (Steps.this) {
                        statuses.put(name + "/" + step, status);
                    }
                }
            };
        }

        synchronized @Nullable TaskStatus status(String module, String step) {
            return statuses.get(module + "/" + step);
        }

        synchronized Map<String, TaskStatus> ran() {
            Map<String, TaskStatus> ran = new TreeMap<>(statuses);
            ran.values().removeIf(s -> s == TaskStatus.SKIPPED);
            return ran;
        }
    }
}
