// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.GroovycRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Groovy is a full-recompile lane — the output dir must hold exactly one compile's
 * results. A cache-hit restore into a dirty dir resurrected deleted sources' classes.
 */
class GroovyCompileWipeTest {

    @Test
    void cache_hit_restore_wipes_stale_outputs_and_stubs(@TempDir Path tmp) throws IOException {
        Cas cas = new Cas(tmp.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tmp.resolve("actions"));

        Path src = tmp.resolve("A.groovy");
        Files.writeString(src, "class A {}");
        Path out = Files.createDirectories(tmp.resolve("groovy/main"));
        Path stubs = Files.createDirectories(tmp.resolve("groovy/stubs"));
        Path worker = tmp.resolve("worker.jar");
        Files.writeString(worker, "W");

        GroovycRequest req = GroovycRequest.builder()
                .sources(List.of(src))
                .classpath(List.of())
                .outputDir(out)
                .stubsOut(stubs)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .build();

        // Seed a green record for this exact key whose only output is A.class.
        String key = ActionKey.forGroovyc("compile-groovy@x", req, "test-jk");
        Path aClass = tmp.resolve("A.class");
        Files.writeString(aClass, "ACLASS");
        String hex = Hashing.sha256Hex(aClass);
        cas.putFile(aClass, hex);
        cache.storeWithOutputs("compile-groovy@x", key, Map.of(), Map.of("A.class", hex));

        // Dirty state from a previous, larger source set.
        Files.writeString(out.resolve("B.class"), "STALE");
        Files.writeString(stubs.resolve("B.java"), "stub class B {}");

        LangCompile.Result result =
                LangCompile.run("compile-groovy@x", req, "test-jk", true, cas, cache, WorkerEnv.strict());

        assertThat(result.cacheHit()).isTrue();
        assertThat(out.resolve("A.class")).exists();
        assertThat(out.resolve("B.class"))
                .as("deleted source's class must not resurrect")
                .doesNotExist();
        assertThat(stubs.resolve("B.java"))
                .as("stale stub must not shadow javac resolution")
                .doesNotExist();
    }
}
