// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.engine.plugin.WarmLinger;
import cc.jumpkick.engine.plugin.WorkerScope;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.run.JkThreads;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resident compiler worker as a real JVM: modules submitted together compile at once in one
 * process, each with its own diagnostics; the next job compiles on the worker the last one left; a
 * warm worker reads a rewritten jar again; a forecast uses a warm worker and never starts one.
 */
@Tag("integration")
class ResidentCompilerTest {

    @BeforeEach
    void lingerPastTheTest() {
        CompilerPool.closeAllForTests(); // another class in this JVM may have left a worker for the same key
        WarmLinger.forTests(60_000);
    }

    @AfterEach
    void reset() {
        CompilerPool.closeAllForTests();
        WarmLinger.forTests(-1);
    }

    @Test
    void modules_submitted_together_compile_at_once_in_one_worker_each_with_its_own_answer(@TempDir Path dir)
            throws Exception {
        Path worker = workerJar();
        List<ForkedJavac.Request> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(moduleRequest(
                    dir, worker, "ok" + i, "package p; public class C { public int n() { return " + i + "; } }"));
        }
        for (int i = 0; i < 2; i++) {
            requests.add(moduleRequest(
                    dir, worker, "bad" + i, "package p; public class C { int n() { return \"bad" + i + "\"; } }"));
        }

        List<ForkedJavac.Result> results = inJob(9401L, () -> {
            List<Future<ForkedJavac.Result>> running = new ArrayList<>();
            for (ForkedJavac.Request req : requests) running.add(JkThreads.io().submit(() -> ForkedJavac.compile(req)));
            List<ForkedJavac.Result> out = new ArrayList<>();
            for (Future<ForkedJavac.Result> f : running) out.add(f.get(120, TimeUnit.SECONDS));
            return out;
        });

        CompilerPool pool = requireNonNull(
                CompilerPool.get(JavaCompilerHost.residentKey(requests.getFirst())),
                "one pool for every module on this JDK");
        List<CompilerWorker> workers = pool.workers();
        assertThat(workers).as("one worker process for every module").hasSize(1);
        CompilerWorker only = workers.getFirst();
        assumeTrue(
                Math.min(only.threads(), CompilerBudget.slots()) > 1,
                "the worker or the engine sees one core, so one compile runs at a time");
        assertThat(only.peakInflight())
                .as("modules submitted together are in flight together on the one worker")
                .isGreaterThan(1);
        for (int i = 0; i < 4; i++) {
            assertThat(results.get(i).success())
                    .as("%s", results.get(i).diagnostics())
                    .isTrue();
            assertThat(results.get(i).diagnostics()).isEmpty();
            assertThat(dir.resolve("ok" + i + "/classes/p/C.class")).isRegularFile();
        }
        for (int i = 0; i < 2; i++) {
            ForkedJavac.Result bad = results.get(4 + i);
            String own = "bad" + i;
            String other = "bad" + (1 - i);
            assertThat(bad.success()).isFalse();
            assertThat(bad.diagnostics())
                    .as("each broken module's errors are its own, never its neighbour's")
                    .isNotEmpty()
                    .allSatisfy(d -> assertThat(d.toString())
                            .contains(own + File.separator)
                            .doesNotContain(other + File.separator));
        }
    }

    @Test
    void the_next_job_compiles_on_the_worker_the_last_one_left(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        ForkedJavac.Request req = moduleRequest(dir, worker, "m");
        Path src = req.sources().getFirst();

        assertThat(compileInJob(9301L, req).success()).isTrue();
        CompilerPool pool =
                requireNonNull(CompilerPool.get(JavaCompilerHost.residentKey(req)), "the worker outlives its job");
        long pid = pool.workers().getFirst().pid();
        assertThat(pid).isPositive();

        Files.writeString(
                src, "package p; public class C { public int n() { return 2; } public int m() { return 3; } }");
        ForkedJavac.Result again = compileInJob(9302L, req);

        assertThat(again.success()).as("%s", again.diagnostics()).isTrue();
        assertThat(again.compiledSources()).contains(src);
        assertThat(CompilerPool.poolCount()).isEqualTo(1);
        assertThat(pool.workers())
                .as("the second job used the same worker process")
                .singleElement()
                .satisfies(w -> assertThat(w.pid()).isEqualTo(pid));
    }

    /**
     * The worker writes the spec on its dispatcher's thread. The request's shell must still reach
     * it: the caller's JK_COMPILE_PHASES is how anyone learns where a compile's time went.
     */
    @Test
    void a_compile_writes_the_phases_the_callers_shell_asked_for(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        ForkedJavac.Request req = moduleRequest(dir, worker, "phased");
        Path phases = dir.resolve("phases.log");
        ForkedJavac.Result r = RequestShell.where(
                Map.of(PluginProtocol.COMPILE_PHASES_ENV, phases.toString()), () -> compileInJob(9104L, req));
        assertThat(r.success()).as("%s", r.diagnostics()).isTrue();
        assertThat(phases).exists();
        assertThat(Files.readString(phases)).contains("zinc-compile=");
    }

    @Test
    void a_rewritten_jar_on_the_classpath_is_read_again_by_a_warm_worker(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
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
    void a_warm_only_plan_starts_no_worker_and_uses_the_one_a_build_left(@TempDir Path dir) throws Exception {
        Path worker = workerJar();
        ForkedJavac.Request req = moduleRequest(dir, worker, "w");

        assertThat(inJob(9321L, () -> ForkedJavac.planIfWarm(req)))
                .as("no warm worker: no plan")
                .isNull();
        assertThat(CompilerPool.poolCount()).as("and no worker started for one").isZero();

        assertThat(compileInJob(9322L, req).success()).isTrue();
        Files.writeString(
                req.sources().getFirst(),
                "package p; public class C { public int n() { return 2; } public int m() { return 3; } }");
        ForkedJavac.Plan plan =
                requireNonNull(inJob(9323L, () -> ForkedJavac.planIfWarm(req)), "the build's worker is still warm");
        assertThat(plan.sources()).contains(req.sources().getFirst());
    }

    private static <T extends @Nullable Object> T inJob(long job, Callable<T> work) throws Exception {
        WorkerScope scope = new WorkerScope(job);
        return scope.call(() -> {
            try {
                return JkThreads.io().submit(work).get(180, TimeUnit.SECONDS);
            } finally {
                scope.shutdown(0L);
                JavaCompilerHost.end(job);
            }
        });
    }

    private static ForkedJavac.Result compileInJob(long job, ForkedJavac.Request req) throws Exception {
        return inJob(job, () -> ForkedJavac.compile(req));
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
        return moduleRequest(dir, worker, name, "package p; public class C { public int n() { return 1; } }");
    }

    private static ForkedJavac.Request moduleRequest(Path dir, Path worker, String name, String source)
            throws IOException {
        return moduleRequest(dir, worker, name, source, List.of());
    }

    /** A one-class module with its own output and Zinc work dir, so compiles share no module state. */
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
}
