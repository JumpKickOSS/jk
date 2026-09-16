// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.guard.eval.GuardSuites;
import cc.jumpkick.guard.extract.FactsIndexing;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build's guard lane and a standalone {@code jk guard} on one tree fork the same suite into
 * the same {@code target/guard/}: two runs at once take turns, and each leaves a whole report.
 */
// Out of the unit tier: two forked suite JVMs.
@Tag("integration")
class GuardSuiteRunnerConcurrencyTest {

    private static final String SUITE = """
            package fx;

            import cc.jumpkick.guard.api.Guard;
            import cc.jumpkick.guard.api.GuardSuite;
            import cc.jumpkick.guard.api.Violations;

            @GuardSuite
            final class TakesTurns {
                @Guard(id = "takes-turns", why = "two runs on one tree must not read each other's half-written report")
                void takesTurns(Violations v) {
                    v.population(1);
                }
            }
            """;

    @Test
    void two_runs_on_one_tree_take_turns_and_each_reports_every_guard(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("tree"));
        Files.writeString(root.resolve("jk.toml"), "group = \"t\"\nname = \"m\"\nversion = \"0.0.1\"\njava = 25\n");
        JkBuild build = JkBuildParser.parse(root.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(root, build);
        Path guardClasses = layout.guardClassesDir();
        compileSuite(tmp.resolve("suite-src"), guardClasses);
        Path facts = FactsIndexing.indexPath(layout.buildDir(), "guard");
        FactsIndexing.ensure(guardClasses, facts);
        GuardSuiteRunner.Inputs in = new GuardSuiteRunner.Inputs(
                root,
                "",
                root,
                layout,
                Path.of(System.getProperty("java.home")),
                testClasspath(),
                TestCaches.dir("guard-suite-concurrency"),
                List.of(facts),
                List.of(),
                List.of(guardClasses),
                false);

        CountDownLatch go = new CountDownLatch(1);
        Callable<List<String>> run = () -> {
            go.await();
            return GuardSuiteRunner.run(in, List.of(root));
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> lane = pool.submit(run);
            Future<List<String>> standalone = pool.submit(run);
            go.countDown();
            assertThat(lane.get(5, TimeUnit.MINUTES)).as("the lane's run").isEmpty();
            assertThat(standalone.get(5, TimeUnit.MINUTES))
                    .as("the standalone run")
                    .isEmpty();
        } finally {
            pool.shutdownNow();
        }

        Path report = GuardSuites.report(BuildLayout.moduleTargetDir(root, root));
        Map<String, Object> lines = GuardSuites.readReport(report);
        assertThat(lines).containsOnlyKeys("takes-turns");
        assertThat(String.valueOf(lines.get("takes-turns"))).doesNotContain("threw");
        assertThat(report.resolveSibling(GuardSuiteRunner.LOCK_FILE)).exists();
    }

    /** The suite compiled against this JVM's classpath, into the fixture's guard classes directory. */
    private static void compileSuite(Path src, Path out) throws Exception {
        Path file = Files.createDirectories(src.resolve("fx")).resolve("TakesTurns.java");
        Files.writeString(file, SUITE);
        Files.createDirectories(out);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(
                null,
                null,
                null,
                "-d",
                out.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "-proc:none",
                file.toString());
        assertThat(rc).as("javac").isZero();
    }

    /** This JVM's classpath: the guard API, its JUnit extension and the Jupiter engine are all on it. */
    private static List<Path> testClasspath() {
        return Classpaths.split(System.getProperty("java.class.path"));
    }
}
