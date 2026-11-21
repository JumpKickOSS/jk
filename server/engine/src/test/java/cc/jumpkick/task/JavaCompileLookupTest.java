// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.CompileRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A caller that weighs a compile by whether it restores hears from the compile's own lookup. */
class JavaCompileLookupTest {

    @Test
    void a_held_key_is_told_once_and_restored_without_a_compile(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        byte[] cls = "class-bytes".getBytes(StandardCharsets.UTF_8);
        String sha = Hashing.sha256Hex(cls);
        f.cas.put(cls, sha);
        String key = JavaCompile.key("compile-main", f.request, "jk-test", List.of());
        f.actionCache.storeWithOutputs("compile-main", key, Map.of(), Map.of("A.class", sha));
        List<Boolean> told = new ArrayList<>();

        JavaCompile.Result r = f.run(told);

        assertThat(told).containsExactly(true);
        assertThat(r.cacheHit()).isTrue();
        assertThat(f.out.resolve("A.class")).hasBinaryContent(cls);
    }

    @Test
    void a_missing_key_is_told_before_the_compile_runs(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        List<Boolean> told = new ArrayList<>();

        // No worker jar: the compile fails as it starts, after the lookup has been told.
        assertThatThrownBy(() -> f.run(told)).isInstanceOf(NullPointerException.class);
        assertThat(told).containsExactly(false);
    }

    private static final class Fixture {
        final Cas cas;
        final ActionCache actionCache;
        final Path dir;
        final Path out;
        final Path stateDir;
        final CompileRequest request;

        Fixture(Path dir) throws Exception {
            this.dir = dir;
            cas = new Cas(dir.resolve("cas"));
            actionCache = new ActionCache(cas, dir.resolve("actions"));
            out = dir.resolve("classes");
            stateDir = dir.resolve("state");
            Path src = dir.resolve("src/A.java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, "class A {}");
            request = CompileRequest.builder()
                    .sources(List.of(src))
                    .classpath(List.of())
                    .outputDir(out)
                    .release(21)
                    .javaHome(Path.of(System.getProperty("java.home")))
                    .build();
        }

        JavaCompile.Result run(List<Boolean> told) throws Exception {
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
                    null,
                    dir.resolve("gen"),
                    WorkerEnv.strict(),
                    List.of(),
                    told::add);
        }
    }
}
