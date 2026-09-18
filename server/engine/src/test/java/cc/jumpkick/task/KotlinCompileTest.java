// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.KotlincRequest;
import cc.jumpkick.engine.plugin.WorkerEnv;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The action-cache fast path of {@link LangCompile} (Kotlin arm): an exact-input hit restores the output dir
 * from the CAS and never forks the worker (so this runs with no Kotlin toolchain present). The
 * miss/worker path is covered end-to-end by the CLI's KotlinCompilationTest.
 */
@Tag("integration")
class KotlinCompileTest {

    @Test
    void restores_from_cache_on_exact_input_hit(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, dir.resolve("actions"));

        Path src = write(dir.resolve("A.kt"), "package x\nclass A");
        Path out = dir.resolve("out");
        // A bogus worker classpath — proves it's never launched on a hit.
        Path worker = write(dir.resolve("worker.jar"), "not a real jar");
        KotlincRequest req = req(src, out, worker);

        // Seed the cache: store a record under this request's key with one output.
        String key = ActionKey.forKotlinc("compile-kotlin", req, "jk-test", KotlinClasspathAbi.MEMOIZED_ONLY);
        Path blob = cas.put("CLASS BYTES".getBytes(StandardCharsets.UTF_8));
        String sha = cas.hashFromPath(blob).orElseThrow();
        cache.storeWithOutputs("compile-kotlin", key, Map.of(), Map.of("x/A.class", sha));

        LangCompile.Result r = LangCompile.run(
                "compile-kotlin",
                req,
                "jk-test",
                /* useCache= */ true,
                cas,
                cache,
                WorkerEnv.strict(),
                KotlinClasspathAbi.MEMOIZED_ONLY);

        assertThat(r.success()).isTrue();
        assertThat(r.cacheHit()).isTrue();
        assertThat(r.actionKey()).isEqualTo(key);
        // Output restored from the CAS, no compile.
        assertThat(out.resolve("x/A.class"))
                .usingCharset(StandardCharsets.UTF_8)
                .hasContent("CLASS BYTES");
    }

    /**
     * A hit that lays down a tree other than the state's own compile wrote keeps the state when
     * the record's key names other inputs — the state's own tracking recompiles what moved; a
     * record under the state's own key that wrote another tree, or a state with no ledger, starts
     * over; the state's own tree is left alone.
     */
    @Test
    void a_hit_laying_down_another_tree_reconciles_the_incremental_state(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, dir.resolve("actions"));
        Path src = write(dir.resolve("A.kt"), "package x\nclass A");
        Path out = dir.resolve("out");
        Path worker = write(dir.resolve("worker.jar"), "not a real jar");
        Path state = dir.resolve("ic");
        KotlincRequest req = KotlincRequest.builder()
                .sources(List.of(src))
                .classpath(List.of())
                .outputDir(out)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .javaHome(Path.of(System.getProperty("java.home")))
                .workingDir(state)
                .build();
        String key = ActionKey.forKotlinc("compile-kotlin", req, "jk-test", KotlinClasspathAbi.MEMOIZED_ONLY);
        String sha = cas.hashFromPath(cas.put("CLASS BYTES".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        Map<String, String> restored = Map.of("x/A.class", sha, "x/B.class", sha);
        cache.storeWithOutputs("compile-kotlin", key, Map.of(), restored);

        // The state's compile, under another key, wrote a tree without B and with Old: the hit
        // lays down the record's tree — B in, Old pruned — and the state stays for its own tracking.
        Files.createDirectories(state);
        write(state.resolve("caches.bin"), "state");
        LangCompile.recordTree(state, "another-key", Map.of("x/A.class", sha, "x/Old.class", sha));
        Files.createDirectories(out.resolve("x"));
        write(out.resolve("x/Old.class"), "OLD");
        LangCompile.Result r = LangCompile.run(
                "compile-kotlin",
                req,
                "jk-test",
                true,
                cas,
                cache,
                WorkerEnv.strict(),
                KotlinClasspathAbi.MEMOIZED_ONLY);
        assertThat(r.cacheHit()).isTrue();
        assertThat(out.resolve("x/B.class")).isRegularFile();
        assertThat(state.resolve("caches.bin")).as("the state is kept").isRegularFile();
        assertThat(out.resolve("x/Old.class"))
                .as("the disk holds the record's tree")
                .doesNotExist();

        // The same key wrote another tree: nothing explains the difference, so the state goes.
        LangCompile.recordTree(state, key, Map.of("x/A.class", sha));
        r = LangCompile.run(
                "compile-kotlin",
                req,
                "jk-test",
                true,
                cas,
                cache,
                WorkerEnv.strict(),
                KotlinClasspathAbi.MEMOIZED_ONLY);
        assertThat(r.cacheHit()).isTrue();
        assertThat(state).doesNotExist();

        // A state with no ledger vouches for nothing.
        Files.createDirectories(state);
        write(state.resolve("caches.bin"), "state");
        r = LangCompile.run(
                "compile-kotlin",
                req,
                "jk-test",
                true,
                cas,
                cache,
                WorkerEnv.strict(),
                KotlinClasspathAbi.MEMOIZED_ONLY);
        assertThat(r.cacheHit()).isTrue();
        assertThat(state).doesNotExist();

        // The state's own compile wrote this very tree: the hit leaves it alone.
        Files.createDirectories(state);
        write(state.resolve("caches.bin"), "state");
        LangCompile.recordTree(state, key, restored);
        r = LangCompile.run(
                "compile-kotlin",
                req,
                "jk-test",
                true,
                cas,
                cache,
                WorkerEnv.strict(),
                KotlinClasspathAbi.MEMOIZED_ONLY);
        assertThat(r.cacheHit()).isTrue();
        assertThat(state.resolve("caches.bin")).isRegularFile();
    }

    @Test
    void the_ledger_round_trips_the_key_and_the_tree(@TempDir Path dir) throws IOException {
        Path state = Files.createDirectories(dir.resolve("ic"));
        Map<String, String> tree = Map.of("x/A.class", "aa", "x/with space/B.class", "bb");
        LangCompile.recordTree(state, "the-key", tree);
        LangCompile.Ledger ledger =
                Objects.requireNonNull(LangCompile.readLedger(state.resolve(LangCompile.TREE_LEDGER)), "ledger");
        assertThat(ledger.key()).isEqualTo("the-key");
        assertThat(ledger.outputs()).isEqualTo(tree);
        Files.writeString(state.resolve(LangCompile.TREE_LEDGER), "a-digest-alone\n");
        assertThat(LangCompile.readLedger(state.resolve(LangCompile.TREE_LEDGER)))
                .as("a ledger with a key and no rows is an empty tree")
                .isEqualTo(new LangCompile.Ledger("a-digest-alone", Map.of()));
    }

    @Test
    void key_changes_when_a_source_changes(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("out");
        Path worker = write(dir.resolve("worker.jar"), "stub");
        Path src = write(dir.resolve("A.kt"), "package x\nclass A");
        String k1 = ActionKey.forKotlinc("t", req(src, out, worker), "jk", KotlinClasspathAbi.MEMOIZED_ONLY);
        write(src, "package x\nclass A { fun f() = 1 }");
        String k2 = ActionKey.forKotlinc("t", req(src, out, worker), "jk", KotlinClasspathAbi.MEMOIZED_ONLY);
        assertThat(k2).isNotEqualTo(k1);
    }

    private static KotlincRequest req(Path src, Path out, Path worker) {
        return KotlincRequest.builder()
                .sources(List.of(src))
                .classpath(List.of())
                .outputDir(out)
                .jvmTarget(21)
                .workerClasspath(List.of(worker))
                .javaHome(Path.of(System.getProperty("java.home")))
                .build();
    }

    private static Path write(Path file, String body) throws IOException {
        Files.writeString(file, body);
        return file;
    }
}
