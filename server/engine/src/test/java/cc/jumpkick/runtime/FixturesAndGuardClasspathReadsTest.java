// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import cc.jumpkick.testing.SysProps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * compile-test-fixtures and compile-guard key their action-cache reweigh and their compile on the
 * same classpath lines, and every line fingerprints a classpath entry: each step reads its
 * classpath once and hands the lines on, rather than reading it again for the compile.
 *
 * <p>The fixture locks against a loopback repository serving the JUnit Platform it is given, in a
 * store and local repository of its own: the fast tier reaches no remote, and what an earlier run
 * left in the host's shared pool or {@code ~/.m2} is not what makes it pass.
 */
@SysProps.TempRoots({"jk.env.JK_STORE_DIR", "jk.m2.local"})
class FixturesAndGuardClasspathReadsTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @BeforeEach
    void serveTheJUnitPlatform() {
        new MavenStub(http)
                .leaf("org.junit.jupiter", "junit-jupiter", "6.1.0")
                .leaf("org.junit.platform", "junit-platform-launcher", "6.1.0");
    }

    private static final String MANIFEST = """
            group   = "t"
            name    = "%s"
            version = "0.0.1"
            java    = 25
            javadoc = false
            """;

    @Test
    void a_compile_test_fixtures_reads_its_classpath_once(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), MANIFEST.formatted("app") + "\n[test]\nfixtures = true\n");
        write(app, "src/main/java/demo/App.java", "package demo; public class App {}\n");
        write(app, "src/fixtures/java/demo/AppFixture.java", "package demo; public class AppFixture {}\n");
        write(app, "src/test/java/demo/AppCheck.java", "package demo; class AppCheck { AppFixture f; }\n");
        Path cache = tmp.resolve("cache");
        lock(app, cache);

        long reads = readsIn(
                TaskNames.COMPILE_TEST_FIXTURES, TestInputs.of(app, cache).withSkipTests(true));

        assertThat(layout(app).testFixturesClassesDir().resolve("demo/AppFixture.class"))
                .isRegularFile();
        assertThat(reads)
                .as("classpath readings in one compile-test-fixtures that compiled")
                .isEqualTo(1);
    }

    @Test
    void a_compile_guard_reads_its_classpath_once(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), MANIFEST.formatted("ws") + """

                [workspace]
                modules = ["guards", "app"]
                """);
        Path guards = Files.createDirectories(ws.resolve("guards"));
        Files.writeString(
                guards.resolve("jk.toml"),
                MANIFEST.formatted(GuardSuiteLibrary.ARTIFACT).replace("\"t\"", "\"cc.jumpkick\""));
        // The library as a compiled workspace member: its classes tree, here empty.
        Files.createDirectories(guards.resolve("target/classes"));
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), MANIFEST.formatted("app"));
        write(app, "src/main/java/demo/App.java", "package demo; public class App {}\n");
        write(app, "src/guard/java/demo/AppRules.java", "package demo; public class AppRules {}\n");
        Path cache = tmp.resolve("cache");
        lock(app, cache);

        long reads = readsIn(TaskNames.COMPILE_GUARD, TestInputs.of(app, cache).withSkipTests(true));

        assertThat(layout(app).guardClassesDir().resolve("demo/AppRules.class")).isRegularFile();
        assertThat(reads)
                .as("classpath readings in one compile-guard that compiled")
                .isEqualTo(1);
    }

    private static void write(Path root, String rel, String body) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }

    private static BuildLayout layout(Path project) throws IOException {
        return BuildLayout.of(project, JkBuildParser.parse(project.resolve("jk.toml")));
    }

    private void lock(Path project, Path cache) throws IOException {
        BuildPlanResult lock = LockPlans.lockBuildPlan(
                        project, cache, http.base(), List.of(), true, false, ResolveObserver.NOOP, null)
                .run();
        assertThat(lock.errors()).isEmpty();
    }

    /** Builds {@code in} and counts the classpath readings between {@code step}'s start and finish. */
    private static long readsIn(String step, BuildPlanner.Inputs in) {
        AtomicLong atStart = new AtomicLong(-1);
        AtomicLong reads = new AtomicLong(-1);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        plan.addListener(new BuildPlanListener() {
            @Override
            public void stepStart(String name, @Nullable String group, int ticks) {
                if (step.equals(name)) atStart.set(ActionKey.javacClasspathReads());
            }

            @Override
            public void stepFinish(
                    String name, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                if (step.equals(name)) reads.set(ActionKey.javacClasspathReads() - atStart.get());
            }
        });
        BuildPlanResult result = plan.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        return reads.get();
    }
}
