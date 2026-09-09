// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane behaviour of the Zinc worker pool: several modules submitted at once are compiled by several
 * worker JVMs rather than queued behind one, and the pool stays inside its budget.
 */
@Tag("integration")
class JavaCompilerHostLanesTest {

    @AfterEach
    void clearSharedPlan() {
        JvmOptions.resetSharedPlanForTests();
        JavaCompilerHost.overrideLaneBudgetForTests(0);
    }

    @Test
    void concurrent_modules_run_on_separate_lanes(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        int modules = 4;
        List<ForkedJavac.Request> requests = new ArrayList<>();
        for (int i = 0; i < modules; i++) requests.add(moduleRequest(dir, worker, "m" + i));

        long job = 9101L;
        JobWorkers.open(job);
        int peak = 0;
        try {
            // JkThreads propagates the JobWorkers scope; a raw pool thread would fall back to
            // a one-shot fork and never reach the pool at all.
            List<Future<ForkedJavac.Result>> running = new ArrayList<>();
            for (ForkedJavac.Request req : requests) {
                running.add(JkThreads.io().submit(() -> ForkedJavac.compile(req)));
            }
            // Poll while the compiles are in flight; lanes are torn down with the job, not per item,
            // so the sample taken once every future is done still sees them. Stop there whatever the
            // peak: a pool that never fanned out must fail on the assertion below, not spin out the
            // whole deadline first.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                peak = Math.max(peak, JavaCompilerHost.laneCount(job));
                if (running.stream().allMatch(Future::isDone)) break;
                Thread.sleep(20);
            }
            for (Future<ForkedJavac.Result> f : running) {
                ForkedJavac.Result r = f.get(120, TimeUnit.SECONDS);
                assertThat(r.success()).as("%s", r.diagnostics()).isTrue();
            }
        } finally {
            JavaCompilerHost.end(job);
            JobWorkers.shutdownForRequest(job, 0L);
            JobWorkers.close();
        }

        assertThat(peak)
                .as("modules submitted together must compile on separate worker JVMs")
                .isGreaterThan(1);
        assertThat(peak).isLessThanOrEqualTo(JavaCompilerHost.laneBudget());
        for (int i = 0; i < modules; i++) {
            assertThat(dir.resolve("m" + i + "/classes/p/C.class")).isRegularFile();
        }
    }

    @Test
    void every_queued_module_compiles_when_lanes_are_capped_to_one(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        JavaCompilerHost.overrideLaneBudgetForTests(1);
        assertThat(JavaCompilerHost.laneBudget()).isEqualTo(1);

        long job = 9102L;
        JobWorkers.open(job);
        try {
            List<Future<ForkedJavac.Result>> running = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ForkedJavac.Request req = moduleRequest(dir, worker, "q" + i);
                running.add(JkThreads.io().submit(() -> ForkedJavac.compile(req)));
            }
            for (Future<ForkedJavac.Result> f : running) {
                assertThat(f.get(120, TimeUnit.SECONDS).success()).isTrue();
            }
            assertThat(JavaCompilerHost.laneCount(job))
                    .as("three modules queued together drained through the one lane the budget allows")
                    .isEqualTo(1);
        } finally {
            JavaCompilerHost.end(job);
            JobWorkers.shutdownForRequest(job, 0L);
            JobWorkers.close();
        }
        for (int i = 0; i < 3; i++) {
            assertThat(dir.resolve("q" + i + "/classes/p/C.class")).isRegularFile();
        }
    }

    @Test
    void a_broken_module_fails_alone_while_its_neighbours_compile(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        List<ForkedJavac.Request> good = new ArrayList<>();
        for (int i = 0; i < 3; i++) good.add(moduleRequest(dir, worker, "ok" + i));

        Path badRoot = dir.resolve("bad");
        Path badSrc = badRoot.resolve("src/p/C.java");
        Files.createDirectories(badSrc.getParent());
        Files.writeString(badSrc, "package p; public class C { int n() { return \"not an int\"; } }");
        ForkedJavac.Request bad = new ForkedJavac.Request(
                Path.of(System.getProperty("java.home")),
                worker,
                List.of(badSrc),
                List.of(),
                List.of(),
                badRoot.resolve("classes"),
                badRoot.resolve("gen"),
                21,
                List.of(),
                badRoot.resolve("zinc"));

        long job = 9104L;
        JobWorkers.open(job);
        try {
            List<Future<ForkedJavac.Result>> running = new ArrayList<>();
            running.add(JkThreads.io().submit(() -> ForkedJavac.compile(bad)));
            for (ForkedJavac.Request req : good) {
                running.add(JkThreads.io().submit(() -> ForkedJavac.compile(req)));
            }

            ForkedJavac.Result broken = running.getFirst().get(120, TimeUnit.SECONDS);
            assertThat(broken.success()).isFalse();
            assertThat(broken.diagnostics())
                    .as("the failure names the module that caused it, not a lane neighbour")
                    .anySatisfy(d -> assertThat(d.toString()).contains("C.java"));

            for (Future<ForkedJavac.Result> f : running.subList(1, running.size())) {
                assertThat(f.get(120, TimeUnit.SECONDS).success())
                        .as("a neighbour must not fail because another lane did")
                        .isTrue();
            }
        } finally {
            JavaCompilerHost.end(job);
            JobWorkers.shutdownForRequest(job, 0L);
            JobWorkers.close();
        }
        for (int i = 0; i < 3; i++) {
            assertThat(dir.resolve("ok" + i + "/classes/p/C.class")).isRegularFile();
        }
    }

    @Test
    void the_pool_is_dropped_with_the_job() throws Exception {
        long job = 9103L;
        assertThat(JavaCompilerHost.laneCount(job)).isZero();
        JavaCompilerHost.end(job); // no pool for this id: must not throw
        assertThat(JavaCompilerHost.laneCount(job)).isZero();
    }

    private static Path workerJar() {
        String prop = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                prop != null && Files.isRegularFile(Path.of(prop)),
                "jk.java.plugin.jar must point at the built worker jar");
        return Path.of(prop);
    }

    /** A one-class module with its own output and Zinc work dir, so lanes share no module state. */
    private static ForkedJavac.Request moduleRequest(Path dir, Path worker, String name) throws IOException {
        Path root = dir.resolve(name);
        Path src = root.resolve("src/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package p; public class C { public int n() { return 1; } }");
        return new ForkedJavac.Request(
                Path.of(System.getProperty("java.home")),
                worker,
                List.of(src),
                List.of(),
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of(),
                root.resolve("zinc"));
    }
}
