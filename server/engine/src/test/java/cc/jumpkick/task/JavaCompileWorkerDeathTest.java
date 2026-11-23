// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A compiler worker that exits before it compiles anything fails the compile and leaves no record:
 * the next run looks its key up, misses, and compiles.
 */
class JavaCompileWorkerDeathTest {

    @Test
    void a_worker_that_exits_before_compiling_stores_nothing_and_the_next_run_tries_again(@TempDir Path dir)
            throws Exception {
        Fixture f = new Fixture(dir);
        Path broken = f.brokenWorker();
        List<Boolean> told = new ArrayList<>();

        assertThatThrownBy(() -> f.run(broken, told)).hasMessageContaining("worker");
        assertThat(f.actionCache.lookup(f.key())).isEmpty();

        assertThatThrownBy(() -> f.run(broken, told)).hasMessageContaining("worker");
        assertThat(told).as("each run looked its key up and missed").containsExactly(false, false);
    }

    @Test
    @Tag("integration")
    void after_a_worker_that_exits_before_compiling_the_next_run_compiles(@TempDir Path dir) throws Exception {
        String prop = System.getProperty("jk.java.plugin.jar");
        Assumptions.assumeTrue(
                prop != null && Files.isRegularFile(Path.of(prop)),
                "jk.java.plugin.jar must point at the built worker jar");
        Fixture f = new Fixture(dir);
        List<Boolean> told = new ArrayList<>();

        assertThatThrownBy(() -> f.run(f.brokenWorker(), told)).hasMessageContaining("worker");
        JavaCompile.Result next = f.run(Path.of(prop), told);

        assertThat(next.success()).as("compile succeeded").isTrue();
        assertThat(next.cacheHit()).isFalse();
        assertThat(next.compiledSources()).containsExactly(f.source);
        assertThat(f.out.resolve("a/A.class")).isRegularFile();
        assertThat(told).containsExactly(false, false);
    }

    private static final class Fixture {
        final Path dir;
        final Cas cas;
        final ActionCache actionCache;
        final Path out;
        final Path stateDir;
        final Path source;
        final CompileRequest request;

        Fixture(Path dir) throws Exception {
            this.dir = dir;
            cas = new Cas(dir.resolve("cas"));
            actionCache = new ActionCache(cas, dir.resolve("actions"));
            out = dir.resolve("target/classes");
            stateDir = dir.resolve("state");
            source = dir.resolve("src/main/java/a/A.java").toAbsolutePath().normalize();
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package a; public class A {}");
            Files.writeString(dir.resolve("jk.toml"), "name = \"app\"\n");
            request = CompileRequest.builder()
                    .sources(List.of(source))
                    .classpath(List.of())
                    .outputDir(out)
                    .release(21)
                    .extraOptions(List.of())
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
        }

        /** A worker jar the JVM refuses to open: the worker process exits before it says hello. */
        Path brokenWorker() throws Exception {
            Path jar = dir.resolve("broken-worker.jar");
            Files.writeString(jar, "not a jar");
            return jar;
        }

        String key() throws Exception {
            return JavaCompile.key("compile-main", request, "jk-test", List.of());
        }

        JavaCompile.Result run(Path workerJar, List<Boolean> told) throws Exception {
            return JavaCompile.run(
                    "compile-main",
                    "test:module compile-main",
                    request,
                    "jk-test",
                    true,
                    true,
                    cas,
                    actionCache,
                    stateDir,
                    workerJar,
                    dir.resolve("gen"),
                    WorkerEnv.strict(),
                    List.of(),
                    told::add);
        }
    }
}
