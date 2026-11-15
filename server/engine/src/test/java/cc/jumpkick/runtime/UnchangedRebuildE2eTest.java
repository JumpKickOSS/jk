// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.KeyAuditMode;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.task.InputGuard;
import cc.jumpkick.task.KeyAudit;
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
import java.util.UUID;
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

        // Audited, explained, built again: no key moved, nothing forecast, nothing run.
        SecondBuild.assertSecondBuildRunsNothing(ws, cache);
    }

    @Test
    void a_guard_suite_on_its_modules_own_fixtures_is_keyed_once_they_are_compiled(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("unchanged-rebuild-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["guards", "g"]
                """);
        // The guard suite library, as a member: what jk's own checkout compiles its guard suites on.
        Files.createDirectories(ws.resolve("guards/src/main/java/rules"));
        Files.writeString(ws.resolve("guards/jk.toml"), """
                group   = "cc.jumpkick"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """.formatted(GuardSuiteLibrary.ARTIFACT));
        Files.writeString(ws.resolve("guards/src/main/java/rules/Rule.java"), "package rules; public class Rule {}\n");
        Path g = module(ws, "g", "", """
                class GTest {
                    @Test
                    void passes() {
                        assertEquals(1, 1);
                    }
                }
                """);
        Files.writeString(g.resolve("jk.toml"), Files.readString(g.resolve("jk.toml")) + "\n[test]\nfixtures = true\n");
        Path fixtures = Files.createDirectories(g.resolve("src/fixtures/java/demo/g"));
        Files.writeString(
                fixtures.resolve("Fx.java"),
                "package demo.g; public final class Fx { public static final String N = \"" + UUID.randomUUID()
                        + "\"; }\n");
        Path guard = Files.createDirectories(g.resolve("src/guard/java/demo/g"));
        // Compiles, and judges nothing: the lane's verdict on it is not what this test is about.
        Files.writeString(guard.resolve("GuardCheck.java"), "package demo.g; final class GuardCheck {}\n");
        lock(ws, cache);

        // compile-guard's classpath names target/test-fixtures-classes: keyed before compile-test-fixtures
        // wrote it, the key read an empty tree and moved once the build had filled it.
        SecondBuild.assertSecondBuildRunsNothing(ws, cache);
    }

    @Test
    void a_skip_tests_build_compiles_the_sibling_fixtures_a_guard_suite_module_compiles_against(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("unchanged-rebuild-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["guards", "a", "b"]
                """);
        Files.createDirectories(ws.resolve("guards/src/main/java/rules"));
        Files.writeString(ws.resolve("guards/jk.toml"), """
                group   = "cc.jumpkick"
                name    = "%s"
                version = "1.0.0"
                java    = 25
                javadoc = false
                """.formatted(GuardSuiteLibrary.ARTIFACT));
        Files.writeString(ws.resolve("guards/src/main/java/rules/Rule.java"), "package rules; public class Rule {}\n");
        String test = """
                class XTest {
                    @Test
                    void passes() {
                        assertEquals(1, 1);
                    }
                }
                """;
        Path a = module(ws, "a", "", test);
        Files.writeString(a.resolve("jk.toml"), Files.readString(a.resolve("jk.toml")) + "\n[test]\nfixtures = true\n");
        Files.writeString(
                Files.createDirectories(a.resolve("src/fixtures/java/demo/a")).resolve("AFx.java"),
                "package demo.a; public final class AFx { public static final String N = \"a\"; }\n");
        Path b = module(ws, "b", "", test);
        Files.writeString(
                b.resolve("jk.toml"),
                Files.readString(b.resolve("jk.toml"))
                                .replace(
                                        "[test-dependencies]\n",
                                        "[test-dependencies]\na = { workspace = true, fixtures = true }\n")
                        + "\n[test]\nfixtures = true\n");
        // b's own fixtures compile against a's: under --skip-tests a's fixtures must be there too.
        Files.writeString(
                Files.createDirectories(b.resolve("src/fixtures/java/demo/b")).resolve("BFx.java"),
                "package demo.b; public final class BFx { public static final String N = demo.a.AFx.N; }\n");
        Files.writeString(
                Files.createDirectories(b.resolve("src/guard/java/demo/b")).resolve("GuardCheck.java"),
                "package demo.b; final class GuardCheck {}\n");
        lock(ws, cache);

        WorkspaceResult result = WorkspaceExecute.buildWorkspace(
                new WorkspaceRequest(ws, cache, null, 0, null, true, false, 2, null, false, false),
                new WorkspaceBuildListener() {});
        assertThat(result.success())
                .as("--skip-tests build: " + result.errors())
                .isTrue();
    }

    @Test
    void under_the_key_audit_a_suite_that_writes_beside_its_sources_fails_naming_what_moved(@TempDir Path tmp)
            throws Exception {
        Path cache = TestCaches.dir("unchanged-rebuild-cache");
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "ws"
                version = "1.0.0"
                java    = 25

                [workspace]
                modules = ["d"]
                """);
        Path d = module(ws, "d", "", """
                class DTest {
                    @Test
                    void reports_outside_target() throws Exception {
                        assertEquals("data", Files.readString(Path.of("data.txt")));
                        Files.createDirectories(Path.of("build"));
                        Files.writeString(Path.of("build", "report.txt"), "ran");
                    }
                }
                """);
        Files.writeString(d.resolve("data.txt"), "data");
        lock(ws, cache);

        List<String> moved = new ArrayList<>();
        WorkspaceResult result = SessionContext.where(
                Session.defaults().withCacheDir(cache).withKeyAudit(KeyAuditMode.STRICT),
                () -> WorkspaceExecute.buildWorkspace(
                        new WorkspaceRequest(ws, cache, null, 0, null, false, false, 2, null, false, false),
                        new WorkspaceBuildListener() {
                            @Override
                            public void onModuleWarning(
                                    Path dir, String module, String step, String code, String message) {
                                if (code.equals(KeyAudit.MOVED)) moved.add(step + ": " + message);
                            }
                        }));
        assertThat(result.success())
                .as("a strict audit fails the build a key moved in")
                .isFalse();
        assertThat(result.errors()).anyMatch(e -> e.startsWith("JK_KEY_AUDIT=strict: "));
        assertThat(moved)
                .as("the suite's stamp keyed the module directory the suite wrote into")
                .anySatisfy(m -> assertThat(m)
                        .startsWith(TaskNames.RUN_TESTS + ": the key of " + TaskNames.RUN_TESTS + " moved")
                        .contains("build"));
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
        // A nonce: content no earlier run stored, so a key build 2 takes differently cannot hit.
        Files.writeString(
                main.resolve("M.java"),
                "package demo." + name + "; public class M { static final String NONCE = \"" + UUID.randomUUID()
                        + "\"; }\n");
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
