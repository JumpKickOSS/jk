// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action-cache fast path of {@link LangCompile} (Groovy arm): an exact-input hit restores the output dir
 * from the CAS and never forks the worker (so this runs with no Groovy toolchain present).
 */
@Tag("integration")
class GroovyCompileTest {

    @Test
    void restores_from_cache_on_exact_input_hit(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, dir.resolve("actions"));

        Path src = write(dir.resolve("A.groovy"), "class A {}");
        Path out = dir.resolve("out");
        // A bogus worker classpath — proves it's never launched on a hit.
        Path worker = write(dir.resolve("worker.jar"), "not a real jar");
        GroovycRequest req = req(src, out, worker);

        // Seed the cache: store a record under this request's key with one output.
        String key = ActionKey.forGroovyc("compile-groovy", req, "jk-test");
        Path blob = cas.put("CLASS BYTES".getBytes(StandardCharsets.UTF_8));
        String sha = cas.hashFromPath(blob).orElseThrow();
        cache.storeWithOutputs("compile-groovy", key, Map.of(), Map.of("A.class", sha));

        LangCompile.Result r =
                LangCompile.run("compile-groovy", req, "jk-test", /* useCache= */ true, cas, cache, WorkerEnv.strict());

        assertThat(r.success()).isTrue();
        assertThat(r.cacheHit()).isTrue();
        assertThat(r.actionKey()).isEqualTo(key);
        // Output restored from the CAS, no compile.
        assertThat(out.resolve("A.class")).usingCharset(StandardCharsets.UTF_8).hasContent("CLASS BYTES");
    }

    @Test
    void key_changes_when_a_source_changes(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out");
        Path worker = write(dir.resolve("worker.jar"), "stub");
        Path src = write(dir.resolve("A.groovy"), "class A {}");
        String k1 = ActionKey.forGroovyc("t", req(src, out, worker), "jk");
        write(src, "class A { def f() { 1 } }");
        String k2 = ActionKey.forGroovyc("t", req(src, out, worker), "jk");
        assertThat(k2).isNotEqualTo(k1);
    }

    @Test
    void key_changes_when_a_swept_java_source_root_file_changes(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out");
        Path worker = write(dir.resolve("worker.jar"), "stub");
        Path src = write(dir.resolve("A.groovy"), "class A {}");
        Path javaRoot = dir.resolve("java");
        Path helper = write(javaRoot.resolve("Helper.java"), "public class Helper {}");

        GroovycRequest req = GroovycRequest.builder()
                .sources(List.of(src))
                .javaSourceRoots(List.of(javaRoot))
                .outputDir(out)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .build();
        String k1 = ActionKey.forGroovyc("t", req, "jk");
        write(helper, "public class Helper { int x; }");
        String k2 = ActionKey.forGroovyc("t", req, "jk");
        assertThat(k2).isNotEqualTo(k1);
    }

    /**
     * Compile avoidance for the full-compile lane: the compile classpath is keyed by JVM ABI (the
     * same token javac uses), the worker closure and the processor path by content.
     */
    @Test
    void a_body_only_classpath_change_keeps_the_key_and_worker_or_api_changes_move_it(@TempDir Path dir)
            throws IOException {
        Path out = dir.resolve("out");
        Path src = write(dir.resolve("A.groovy"), "class A {}");
        Path worker = AbiJars.jar(dir.resolve("worker.jar"), AbiJars.classReturning(1));
        Path dep = AbiJars.jar(dir.resolve("dep.jar"), AbiJars.classReturning(1));
        String before = ActionKey.forGroovyc("t", req(src, out, worker, dep), "jk");

        AbiJars.jar(dep, AbiJars.classReturning(2));
        assertThat(ActionKey.forGroovyc("t", req(src, out, worker, dep), "jk"))
                .as("a dependency's bodies are not a groovyc input")
                .isEqualTo(before);

        AbiJars.jar(dep, AbiJars.classWithMethods("n", "added"));
        assertThat(ActionKey.forGroovyc("t", req(src, out, worker, dep), "jk"))
                .as("a dependency's API is")
                .isNotEqualTo(before);

        AbiJars.jar(dep, AbiJars.classReturning(1));
        AbiJars.jar(worker, AbiJars.classReturning(2));
        assertThat(ActionKey.forGroovyc("t", req(src, out, worker, dep), "jk"))
                .as("the worker closure is the compiler: content-keyed")
                .isNotEqualTo(before);

        assertThat(ActionKey.groovycClasspathTokens(req(src, out, worker, dep)))
                .containsExactly("cp:" + ClasspathAbi.token(dep), "worker:" + ClasspathFingerprint.entry(worker));
    }

    private static GroovycRequest req(Path src, Path out, Path worker, Path dep) {
        return GroovycRequest.builder()
                .sources(List.of(src))
                .classpath(List.of(dep))
                .outputDir(out)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .build();
    }

    private static GroovycRequest req(Path src, Path out, Path worker) {
        return GroovycRequest.builder()
                .sources(List.of(src))
                .classpath(List.of())
                .outputDir(out)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .build();
    }

    private static Path write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }
}
