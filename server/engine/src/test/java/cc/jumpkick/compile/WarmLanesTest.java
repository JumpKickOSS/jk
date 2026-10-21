// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.compile.JavaCompilerHost.Lanes;
import cc.jumpkick.compile.JavaCompilerHost.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.WarmLinger;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiler lanes that outlive their job: the next build's compile lands on the worker the last one
 * left, an idle pool goes after its linger and not while an item is on it, and a lane still
 * compiling for a job that has ended is stopped without touching its neighbours.
 */
class WarmLanesTest {

    @AfterEach
    void reset() {
        WarmLanes.closeAllForTests();
        WarmLinger.forTests(-1);
    }

    @Test
    void one_key_is_one_pool_and_an_idle_one_is_retired_after_its_linger(@TempDir Path dir) throws Exception {
        WarmLinger.forTests(50);
        AtomicInteger made = new AtomicInteger();
        Lanes pool = idlePool();
        CompileWork first = item(dir, "a", 1L);
        CompileWork second = item(dir, "b", 2L);

        assertThat(WarmLanes.lanes("k", () -> counted(made, pool), first)).isSameAs(pool);
        assertThat(WarmLanes.lanes("k", () -> counted(made, pool), second)).isSameAs(pool);
        assertThat(made).as("the second job finds the first one's pool").hasValue(1);

        Thread.sleep(200);
        assertThat(WarmLanes.poolCount())
                .as("two items on it: past the linger, still there")
                .isEqualTo(1);
        first.compile.complete(ok());
        Thread.sleep(300);
        assertThat(WarmLanes.poolCount())
                .as("an item still on the pool keeps it past the linger")
                .isEqualTo(1);

        second.compile.complete(ok());
        awaitTrue(() -> WarmLanes.poolCount() == 0, "the idle pool is retired");
        assertThat(pool.closing()).isTrue();
    }

    @Test
    void a_lane_still_working_for_an_ended_job_is_stopped_and_its_neighbour_is_not(@TempDir Path dir) throws Exception {
        CountDownLatch bothTaken = new CountDownLatch(2);
        AtomicInteger next = new AtomicInteger();
        long[] scopes = {WarmLanes.nextScope(), WarmLanes.nextScope()};
        // Each lane takes one item and then sits on a real process registered under its own scope,
        // as a worker mid-compile does; stopping a lane is killing that process.
        Lanes pool = new Lanes(
                2,
                (owner, index) -> new Session(owner, scopes[next.getAndIncrement()], index, self -> {
                    self.takeNext();
                    Process p = JobWorkers.start(new ProcessBuilder("sleep", "60"));
                    bothTaken.countDown();
                    p.waitFor();
                }),
                ForkedJavac::writeSpec);
        CompileWork ended = item(dir, "a", 77L);
        CompileWork alive = item(dir, "b", 78L);
        pool.enqueue(ended);
        pool.enqueue(alive);
        assertThat(bothTaken.await(30, TimeUnit.SECONDS)).isTrue();

        pool.stopLanesWorking(t -> t.job == 77L);

        awaitTrue(() -> pool.liveLanes() == 1, "the ended job's lane is gone");
        Thread.sleep(200);
        assertThat(pool.liveLanes()).as("the other job's compile goes on").isEqualTo(1);
        pool.stopLanesWorking(t -> true);
        awaitTrue(() -> pool.liveLanes() == 0, "the neighbour stops when asked");
    }

    @Test
    @Tag("integration")
    void the_next_job_compiles_on_the_worker_the_last_one_left(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        WarmLinger.forTests(60_000);
        ForkedJavac.Request req = moduleRequest(dir, worker, "m");
        Path src = req.sources().getFirst();

        assertThat(compileInJob(9301L, req).success()).isTrue();
        List<Long> after = WarmLanes.laneScopes();
        assertThat(after).as("the worker outlives its job").hasSize(1);

        Files.writeString(
                src, "package p; public class C { public int n() { return 2; } public int m() { return 3; } }");
        ForkedJavac.Result again = compileInJob(9302L, req);

        assertThat(again.success()).as("%s", again.diagnostics()).isTrue();
        assertThat(again.compiledSources()).contains(src);
        assertThat(WarmLanes.laneScopes())
                .as("the second job used the same worker")
                .isEqualTo(after);
    }

    @Test
    @Tag("integration")
    void a_rewritten_jar_on_the_classpath_is_read_again_by_a_warm_worker(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        WarmLinger.forTests(60_000);
        Path lib = dir.resolve("lib.jar");
        writeLib(dir, lib, "public int one() { return 1; }");
        ForkedJavac.Request uses = moduleRequest(
                dir, worker, "u", "package p; public class C { int n() { return new q.L().one(); } }", List.of(lib));
        assertThat(compileInJob(9311L, uses).success()).isTrue();

        // The jar is replaced the way a re-resolved snapshot is: a new file renamed over the old one.
        writeLib(dir, lib, "public int two() { return 2; }");
        Files.writeString(
                uses.sources().getFirst(), "package p; public class C { int n() { return new q.L().two(); } }");
        ForkedJavac.Result r = compileInJob(9312L, uses);

        assertThat(r.success())
                .as("a warm worker must see the jar as it is now: %s", r.diagnostics())
                .isTrue();
    }

    @Test
    @Tag("integration")
    void a_warm_only_plan_starts_no_worker_and_uses_the_one_a_build_left(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        WarmLanes.closeAllForTests(); // another class in this JVM may have left a worker for the same key
        WarmLinger.forTests(60_000);
        ForkedJavac.Request req = moduleRequest(dir, worker, "w");

        assertThat(inJob(9321L, () -> ForkedJavac.planIfWarm(req)))
                .as("no warm worker: no plan")
                .isNull();
        assertThat(WarmLanes.laneScopes()).as("and no worker started for one").isEmpty();

        assertThat(compileInJob(9322L, req).success()).isTrue();
        Files.writeString(
                req.sources().getFirst(),
                "package p; public class C { public int n() { return 2; } public int m() { return 3; } }");
        ForkedJavac.Plan plan = inJob(9323L, () -> ForkedJavac.planIfWarm(req));
        assertThat(plan).as("the build's worker is still warm").isNotNull();
        assertThat(plan.sources()).contains(req.sources().getFirst());
    }

    private static <T> T inJob(long job, Callable<T> work) throws Exception {
        JobWorkers.open(job);
        try {
            return JkThreads.io().submit(work).get(120, TimeUnit.SECONDS);
        } finally {
            JobWorkers.shutdownForRequest(job, 0L);
            JavaCompilerHost.end(job);
            JobWorkers.close();
        }
    }

    private static ForkedJavac.Result compileInJob(long job, ForkedJavac.Request req) throws Exception {
        JobWorkers.open(job);
        try {
            return JkThreads.io().submit(() -> ForkedJavac.compile(req)).get(120, TimeUnit.SECONDS);
        } finally {
            JobWorkers.shutdownForRequest(job, 0L);
            JavaCompilerHost.end(job);
            JobWorkers.close();
        }
    }

    /** {@code lib}: one class {@code q.L} with {@code body}, compiled in-process and jarred beside, then moved over. */
    private static void writeLib(Path dir, Path lib, String body) throws Exception {
        Path root = Files.createTempDirectory(dir, "lib");
        Path src = root.resolve("q/L.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package q; public class L { " + body + " }");
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", root.resolve("out").toString(), src.toString());
        assertThat(rc).isZero();
        Path staged = root.resolve("lib.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(staged))) {
            out.putNextEntry(new JarEntry("q/L.class"));
            out.write(Files.readAllBytes(root.resolve("out/q/L.class")));
            out.closeEntry();
        }
        Files.move(staged, lib, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path workerJar() {
        String prop = System.getProperty("jk.java.plugin.jar");
        assumeTrue(
                prop != null && Files.isRegularFile(Path.of(prop)),
                "jk.java.plugin.jar must point at the built worker jar");
        return Path.of(prop);
    }

    private static ForkedJavac.Request moduleRequest(Path dir, Path worker, String name) throws IOException {
        return moduleRequest(
                dir, worker, name, "package p; public class C { public int n() { return 1; } }", List.of());
    }

    private static ForkedJavac.Request moduleRequest(
            Path dir, Path worker, String name, String source, List<Path> classpath) throws IOException {
        Path root = dir.resolve(name);
        Path src = root.resolve("src/p/C.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        return new ForkedJavac.Request(
                Path.of(System.getProperty("java.home")),
                worker,
                List.of(src),
                classpath,
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of(),
                root.resolve("zinc"));
    }

    /** A pool whose lanes never start: these tests are about the warm map, not about compiling. */
    private static Lanes idlePool() {
        return new Lanes(
                0,
                (owner, index) -> new Session(owner, WarmLanes.nextScope(), index, self -> {}),
                r -> r.classOutput());
    }

    private static Lanes counted(AtomicInteger made, Lanes pool) {
        made.incrementAndGet();
        return pool;
    }

    private static CompileWork item(Path dir, String name, long job) {
        Path root = dir.resolve(name);
        ForkedJavac.Request req = new ForkedJavac.Request(
                null,
                root.resolve("worker.jar"),
                List.of(root.resolve("C.java")),
                List.of(),
                List.of(),
                root.resolve("classes"),
                root.resolve("gen"),
                21,
                List.of());
        return CompileWork.compile(req).forJob(job, SessionContext.current());
    }

    private static ForkedJavac.Result ok() {
        return new ForkedJavac.Result(true, List.of(), Map.of(), List.of(), 0L);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            Thread.sleep(5);
        }
    }
}
