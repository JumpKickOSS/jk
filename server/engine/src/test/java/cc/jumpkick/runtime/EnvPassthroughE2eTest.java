// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.CallerAsker;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.Session;
import cc.jumpkick.host.Os;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.workspace.ExplainReport;
import cc.jumpkick.runtime.workspace.WorkspaceExecute;
import cc.jumpkick.testing.SysProps;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test JVM reads the caller's whole environment, a variable at a time, and its suite's result is
 * keyed on what it read (JK-ZENNA). Each build here runs as a request from a client whose shell is
 * a map the test controls: the request carries nothing of it but what the suite was found to read
 * before, and the rest is asked of the "client" just in time, as the CLI answers on the job's
 * connection.
 */
@Tag("integration")
@ExtendWith(SysProps.class)
class EnvPassthroughE2eTest {

    private static final String MANIFEST = """
            group   = "com.example"
            name    = "m"
            version = "1.0.0"
            java    = 25

            [test-dependencies]
            junit-jupiter = "6.1.3"

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    /**
     * The owner's case: a test that fails unless {@code MUST_BE_TRUE} is {@code true}. Its green
     * result is taken again only where the variable is {@code true}: unset, {@code banana} and
     * {@code false} run it again (and it fails), {@code true} once more takes the green result.
     */
    @Test
    void a_green_result_holds_only_where_the_variable_it_read_has_the_same_value(@TempDir Path tmp) throws Exception {
        Path m = module(tmp, "MustBeTrueTest", """
                @Test
                void mustBeTrue() {
                    assertEquals("true", System.getenv("MUST_BE_TRUE"));
                }
                """);
        Shell shell = new Shell(Map.of("MUST_BE_TRUE", "true", "HOMELY", "a"));
        assertThat(shell.build(m).status()).as("first run, MUST_BE_TRUE=true").isEqualTo(TaskStatus.SUCCESS);
        assertThat(shell.asked)
                .as("the first run asks the client for the variable")
                .contains("MUST_BE_TRUE");

        assertThat(shell.build(m).status())
                .as("the same shell again: the green result holds")
                .isIn(null, TaskStatus.SKIPPED);

        assertThat(shell.without("MUST_BE_TRUE").build(m).status())
                .as("unset: not honoured, runs, fails")
                .isEqualTo(TaskStatus.FAIL);
        assertThat(shell.with("MUST_BE_TRUE", "banana").build(m).status())
                .as("banana: not honoured")
                .isEqualTo(TaskStatus.FAIL);
        assertThat(shell.with("MUST_BE_TRUE", "false").build(m).status())
                .as("false: not honoured")
                .isEqualTo(TaskStatus.FAIL);
        assertThat(shell.with("MUST_BE_TRUE", "true").build(m).status())
                .as("true again: the green result holds")
                .isIn(null, TaskStatus.SKIPPED);
        assertThat(shell.with("HOMELY", "b").build(m).status())
                .as("a variable the suite never read changed: still a hit")
                .isIn(null, TaskStatus.SKIPPED);
        List<String> forecast = shell.with("MUST_BE_TRUE", "pear").forecast(m);
        assertThat(forecast)
                .as("jk explain runs the suite again under a value it never read, and never says the value")
                .anySatisfy(step -> assertThat(step).startsWith(TaskNames.RUN_TESTS + " RUN"))
                .noneSatisfy(step -> assertThat(step).contains("pear"));
    }

    /** A test that passes while a variable is unset is keyed on its absence: setting it runs the suite again. */
    @Test
    void a_variable_read_as_unset_keys_its_absence(@TempDir Path tmp) throws Exception {
        Path m = module(tmp, "AbsentTest", """
                @Test
                void absent() {
                    assertEquals(null, System.getenv("SHOULD_BE_ABSENT"));
                }
                """);
        Shell shell = new Shell(Map.of("OTHER", "x"));
        assertThat(shell.build(m).status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(shell.build(m).status()).as("still unset").isIn(null, TaskStatus.SKIPPED);
        assertThat(shell.with("SHOULD_BE_ABSENT", "now").build(m).status())
                .as("set since: runs and fails")
                .isEqualTo(TaskStatus.FAIL);
    }

    /**
     * A suite that lists the environment's names (as Spring Boot binds {@code MY_APP_FOO}) and reads
     * one value is keyed on the names present and that value: a value it never read changing, or a
     * volatile name a new terminal brings, holds the result; a new name runs it again.
     */
    @Test
    void a_suite_that_lists_the_names_is_keyed_on_the_names_and_what_it_read(@TempDir Path tmp) throws Exception {
        Path m = module(tmp, "ListsTest", """
                @Test
                void lists() {
                    java.util.Map<String, String> env = System.getenv();
                    long apps = env.keySet().stream().filter(n -> n.startsWith("MY_APP_")).count();
                    assertEquals(1L, apps);
                    assertEquals("on", env.get("MY_APP_FOO"));
                }
                """);
        Shell shell = new Shell(Map.of("MY_APP_FOO", "on", "UNREAD", "1", "TERM_SESSION_ID", "s1"));
        assertThat(shell.build(m).status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(shell.with("UNREAD", "2").build(m).status())
                .as("a value it never read changed")
                .isIn(null, TaskStatus.SKIPPED);
        assertThat(shell.with("TERM_SESSION_ID", "s2").build(m).status())
                .as("another terminal's session id")
                .isIn(null, TaskStatus.SKIPPED);
        assertThat(shell.with("MY_APP_BAR", "x").build(m).status())
                .as("a new name the binding would pick up: runs, and fails")
                .isEqualTo(TaskStatus.FAIL);
    }

    /**
     * A program a test starts sees the caller's environment (what {@code FOO=bar jk test} promises a
     * script the test runs), and the suite is keyed on the whole environment less the volatile names.
     */
    @Test
    void a_program_a_test_starts_sees_the_callers_environment(@TempDir Path tmp) throws Exception {
        String command = Os.isWindows()
                ? "new ProcessBuilder(\"cmd\", \"/c\", \"echo %CHILD_VAR%\")"
                : "new ProcessBuilder(\"sh\", \"-c\", \"printf %s \\\"$CHILD_VAR\\\"\")";
        Path m = module(tmp, "ChildTest", """
                @Test
                void child() throws Exception {
                    Process p = COMMAND.redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes()).strip();
                    assertEquals(0, p.waitFor());
                    assertEquals("from-the-shell", out);
                }
                """.replace("COMMAND", command));
        Shell shell = new Shell(Map.of("CHILD_VAR", "from-the-shell", "PS1", "$ ", "OTHER", "1"));
        assertThat(shell.build(m).status())
                .as("the program read the shell's variable")
                .isEqualTo(TaskStatus.SUCCESS);
        assertThat(shell.with("PS1", "# ").build(m).status())
                .as("only a volatile name moved")
                .isIn(null, TaskStatus.SKIPPED);
        assertThat(shell.with("OTHER", "2").build(m).status())
                .as("any other value may reach the program: it runs again")
                .isEqualTo(TaskStatus.SUCCESS);
    }

    /**
     * On Windows a variable's name has no case: a test that asks for {@code Must_Be_True} reads the
     * shell's {@code MUST_BE_TRUE}, and the shell setting it under another spelling to another value
     * is the same variable changing.
     */
    @Test
    void on_windows_a_name_is_read_and_keyed_whatever_its_case(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Os.isWindows(), "Windows names have no case");
        Path m = module(tmp, "CaseTest", """
                @Test
                void anyCase() {
                    assertEquals("true", System.getenv("Must_Be_True"));
                }
                """);
        Shell shell = new Shell(Map.of("MUST_BE_TRUE", "true"));
        assertThat(shell.build(m).status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(shell.build(m).status()).isIn(null, TaskStatus.SKIPPED);
        assertThat(new Shell(Map.of("must_be_true", "false")).build(m).status())
                .as("the same variable, spelled otherwise, with another value")
                .isEqualTo(TaskStatus.FAIL);
        assertThat(new Shell(Map.of("Must_Be_True", "true")).build(m).status())
                .as("the same value under another spelling: the same key")
                .isIn(null, TaskStatus.SKIPPED);
    }

    /** A strict module ({@code [env] inherit = false}) keeps its test JVMs to the allow-list, as before. */
    @Test
    void a_strict_module_reads_nothing_it_does_not_declare(@TempDir Path tmp) throws Exception {
        Path m = module(tmp, "StrictTest", """
                @Test
                void strict() {
                    assertEquals(null, System.getenv("STRICT_VAR"));
                }
                """);
        Files.writeString(m.resolve("jk.toml"), MANIFEST + "\n[env]\ninherit = false\n");
        Shell shell = new Shell(Map.of("STRICT_VAR", "set"));
        Steps first = shell.build(m);
        assertThat(first.status()).as("the test JVM did not see it").isEqualTo(TaskStatus.SUCCESS);
        assertThat(shell.asked).as("nothing asked for its value").doesNotContain("STRICT_VAR");
        assertThat(first.warnings)
                .as("the strict module hears which variable its shell set and the test looked up")
                .anyMatch(w -> w.contains("STRICT_VAR") && w.contains("your shell sets"));
    }

    // ---- fixture ------------------------------------------------------------------------------------

    private static Path module(Path tmp, String test, String body) throws Exception {
        Path m = Files.createDirectories(tmp.resolve("ws").resolve("m"));
        Files.writeString(m.resolve("jk.toml"), MANIFEST);
        Files.writeString(
                Files.createDirectories(m.resolve("src/test/java/demo")).resolve(test + ".java"),
                """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class TEST {
                BODY}
                """.replace("TEST", test).replace("BODY", body.indent(4)));
        Files.createDirectories(m.resolve("src/main/java/demo"));
        Files.writeString(m.resolve("src/main/java/demo/M.java"), "package demo; public class M {}\n");
        Path cache = TestCaches.dir("env-passthrough-cache");
        BuildPlan lock = LockPlans.lockBuildPlan(m, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
        return m;
    }

    /**
     * A client's shell: a request carries none of it, and every question the job asks is answered
     * from it, as the CLI answers on the job's connection ({@code EnvAnswers}).
     */
    static final class Shell implements CallerAsker {
        final Map<String, String> vars;
        final List<String> asked = new ArrayList<>();
        final AtomicInteger questions = new AtomicInteger();

        Shell(Map<String, String> vars) {
            // A shell's names have no case on Windows, as the process environment's do not.
            this.vars = Os.isWindows() ? new TreeMap<>(String.CASE_INSENSITIVE_ORDER) : new TreeMap<>();
            this.vars.putAll(vars);
        }

        Shell with(String name, String value) {
            Map<String, String> next = new LinkedHashMap<>(vars);
            next.put(name, value);
            return new Shell(next);
        }

        Shell without(String name) {
            Map<String, String> next = new LinkedHashMap<>(vars);
            next.remove(name);
            return new Shell(next);
        }

        Steps build(Path m) throws Exception {
            Path cache = TestCaches.dir("env-passthrough-cache");
            // The PATH the request carries, so a program a test starts is found, and nothing else.
            Map<String, String> carried = new LinkedHashMap<>();
            String path = System.getenv("PATH");
            if (path != null) carried.put("PATH", path);
            RequestShell requestShell = RequestShell.of(carried, Set.of("PATH"), false, null);
            return Request.detached()
                    .withShell(requestShell)
                    .with(CallerAsker.SLOT, this)
                    .with(SettledReads.SLOT, new SettledReads())
                    .call(() -> {
                        Steps steps = new Steps();
                        WorkspaceResult ignored = WorkspaceExecute.buildWorkspace(
                                new WorkspaceRequest(m, cache, null, 0, null, false, false, 2, null, false, false),
                                steps);
                        return steps;
                    });
        }

        /** What {@code jk explain} forecasts for {@code m} under this shell: each step's name, status and text. */
        List<String> forecast(Path m) throws Exception {
            Path cache = TestCaches.dir("env-passthrough-cache");
            return Request.detached().with(CallerAsker.SLOT, this).call(() -> {
                ExplainReport report = ExplainReport.compute(
                        m,
                        JkBuildParser.parse(m.resolve("jk.toml")),
                        cache,
                        Session.defaults().withCacheDir(cache));
                return report.plan().modules().stream()
                        .flatMap(mod -> mod.steps().stream())
                        .map(step -> step.name() + " " + step.status() + " " + step.text())
                        .toList();
            });
        }

        @Override
        public synchronized @Nullable Map<String, String> values(Collection<String> names) {
            questions.incrementAndGet();
            Map<String, String> out = new LinkedHashMap<>();
            for (String n : names) {
                asked.add(n);
                String v = vars.get(n);
                if (v != null) out.put(n, v);
            }
            return out;
        }

        @Override
        public @Nullable Set<String> names() {
            Set<String> names = new TreeSet<>(vars.keySet());
            names.add("PATH");
            return names;
        }

        @Override
        public @Nullable Map<String, String> all() {
            Map<String, String> all = new LinkedHashMap<>(vars);
            String path = System.getenv("PATH");
            if (path != null) all.put("PATH", path);
            return all;
        }

        @Override
        public boolean lost() {
            return false;
        }
    }

    /** The run-tests step's status and the warnings the build raised. */
    static final class Steps implements WorkspaceBuildListener {
        final List<String> warnings = new ArrayList<>();
        private @Nullable TaskStatus runTests;

        @Override
        public BuildPlanListener onModuleStart(ModulePlan module) {
            return new BuildPlanListener() {
                @Override
                public void warn(String step, String code, String message) {
                    synchronized (Steps.this) {
                        warnings.add(code + " " + message);
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

        synchronized @Nullable TaskStatus status() {
            return runTests;
        }
    }
}
