// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.ActionTree;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ActionCacheTest {

    @Test
    void a_tree_wide_enough_to_fan_out_stores_and_restores_every_output(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        // Comfortably past MIN_FILES_PER_DEPOSIT_LANE so the deposit runs on several lanes, and
        // spread over packages so the blobs land in different CAS shards.
        Path outputs = tempDir.resolve("classes");
        int count = 400;
        for (int i = 0; i < count; i++) {
            Path f = outputs.resolve("p" + (i % 20)).resolve("C" + i + ".class");
            Files.createDirectories(f.getParent());
            Files.writeString(f, "class body " + i);
        }

        cache.store("compile-java", "wide", Map.of("src/A.java", "aaa"), outputs);
        var record = cache.lookup("wide").orElseThrow();

        assertThat(record.outputs()).hasSize(count);
        for (var e : record.outputs().entrySet()) {
            assertThat(cas.pathFor(e.getValue()))
                    .as("every recorded blob is on disk before the record names it: %s", e.getKey())
                    .isRegularFile();
        }

        Path restored = tempDir.resolve("restored");
        cache.restore(record, restored);
        for (int i = 0; i < count; i++) {
            assertThat(restored.resolve("p" + (i % 20) + "/C" + i + ".class")).hasContent("class body " + i);
        }
    }

    @Test
    void store_then_lookup_round_trip(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs.resolve("nested"));
        Files.writeString(outputs.resolve("a.class"), "alpha");
        Files.writeString(outputs.resolve("nested/b.class"), "beta");

        Map<String, String> inputs = Map.of("src/Foo.java", "abc123");
        cache.store("compile-main", "key1", inputs, outputs);

        var record = cache.lookup("key1").orElseThrow();
        assertThat(record.taskId()).isEqualTo("compile-main");
        assertThat(record.actionKey()).isEqualTo("key1");
        assertThat(record.outputs()).containsOnlyKeys("a.class", "nested/b.class");
        assertThat(record.inputs()).containsEntry("src/Foo.java", "abc123");
    }

    @Test
    void store_excludes_jk_scratch_dirs(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs.resolve(".jk-quarkus-bootstrap/m2/g/a/1"));
        Files.writeString(outputs.resolve("quarkus-run.jar"), "RUN");
        Files.writeString(outputs.resolve(".jk-quarkus-bootstrap/m2/g/a/1/a-1.jar"), "PRIVATE");
        Files.writeString(outputs.resolve(".jk-note"), "PRIVATE-FILE");

        cache.store("quarkus-augment", "key-jk", Map.of("src/App.java", "abc"), outputs);

        var record = cache.lookup("key-jk").orElseThrow();
        // plugin-private `.jk-*` scratch never enters the action record or the CAS walk.
        assertThat(record.outputs()).containsOnlyKeys("quarkus-run.jar");
    }

    @Test
    void restore_recreates_outputs_from_cas(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "alpha-content");

        cache.store("compile-main", "key1", Map.of(), outputs);

        // Wipe outputs, restore from cache.
        Files.delete(outputs.resolve("a.class"));
        Files.delete(outputs);

        cache.restore(cache.lookup("key1").orElseThrow(), outputs);
        assertThat(outputs.resolve("a.class")).exists();
        assertThat(Files.readString(outputs.resolve("a.class"))).isEqualTo("alpha-content");
    }

    /**
     * CAS blobs carry no mode. Without the record carrying it, a native binary is
     * runnable on the build that produced it and 0644 on every build after — with the build
     * still reporting success.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC}) // Windows ACLs: isExecutable/setExecutable are not POSIX bits
    void restore_puts_the_executable_bit_back(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Path binary = outputs.resolve("app");
        Path plain = outputs.resolve("app.jar");
        Files.writeString(binary, "#!/bin/sh\necho hi\n");
        Files.writeString(plain, "not executable");
        assertThat(binary.toFile().setExecutable(true)).isTrue();

        cache.store("native-image", "key-exec", Map.of(), outputs);

        Files.delete(binary);
        Files.delete(plain);
        cache.restore(cache.lookup("key-exec").orElseThrow(), outputs);

        assertThat(Files.isExecutable(binary)).as("restored binary is runnable").isTrue();
        assertThat(Files.isExecutable(plain))
                .as("a plain output does not gain the bit")
                .isFalse();
    }

    /** The bit is reapplied even when the target is byte-identical and therefore not re-copied. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC}) // Windows ACLs: isExecutable/setExecutable are not POSIX bits
    void restore_repairs_the_bit_on_an_unchanged_target(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Path binary = outputs.resolve("app");
        Files.writeString(binary, "#!/bin/sh\n");
        assertThat(binary.toFile().setExecutable(true)).isTrue();
        cache.store("native-image", "key-same", Map.of(), outputs);

        // Same bytes, bit stripped — the restore takes the "leave it alone" path.
        assertThat(binary.toFile().setExecutable(false)).isTrue();
        cache.restore(cache.lookup("key-same").orElseThrow(), outputs);
        assertThat(Files.isExecutable(binary)).isTrue();
    }

    @Test
    void restore_cleans_stale_files(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("kept.class"), "k");
        cache.store("compile-main", "key1", Map.of(), outputs);

        // Add a stale file before restoring.
        Files.writeString(outputs.resolve("stale.class"), "s");
        cache.restore(cache.lookup("key1").orElseThrow(), outputs);

        assertThat(outputs.resolve("stale.class")).doesNotExist();
        assertThat(outputs.resolve("kept.class")).exists();
    }

    @Test
    void last_for_task_returns_most_recent(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "v1");
        cache.store("compile-main", "key-v1", Map.of("input", "h1"), outputs);

        Files.writeString(outputs.resolve("a.class"), "v2");
        cache.store("compile-main", "key-v2", Map.of("input", "h2"), outputs);

        assertThat(cache.lastFor("compile-main").orElseThrow().actionKey()).isEqualTo("key-v2");
        // The v1 record is still independently retrievable by key.
        assertThat(cache.lookup("key-v1").orElseThrow().inputs()).containsEntry("input", "h1");
    }

    @Test
    void lookup_returns_empty_for_unknown_key(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        assertThat(cache.lookup("nonexistent")).isEmpty();
        assertThat(cache.lastFor("compile-main")).isEmpty();
    }

    @Test
    void units_round_trip_through_record(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Map<String, String> outputs = Map.of(
                "com/example/Foo.class", "sha-foo",
                "com/example/Foo$Inner.class", "sha-inner",
                "com/example/Bar.class", "sha-bar");
        Map<String, List<String>> units = Map.of(
                "/abs/src/com/example/Foo.java",
                List.of("com/example/Foo.class", "com/example/Foo$Inner.class"),
                "/abs/src/com/example/Bar.java",
                List.of("com/example/Bar.class"));

        cache.storeWithOutputs("compile-main@x", "k", Map.of(), outputs, units);

        var record = cache.lookup("k").orElseThrow();
        assertThat(record.units()).containsOnlyKeys("/abs/src/com/example/Foo.java", "/abs/src/com/example/Bar.java");
        assertThat(record.units().get("/abs/src/com/example/Foo.java"))
                .containsExactlyInAnyOrder("com/example/Foo.class", "com/example/Foo$Inner.class");
        assertThat(record.units().get("/abs/src/com/example/Bar.java")).containsExactly("com/example/Bar.class");
    }

    @Test
    void record_without_units_parses_as_empty(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        // The 4-arg store writes no UNIT lines (legacy / full-rebuild shape).
        cache.storeWithOutputs("compile-main@x", "k", Map.of(), Map.of("a.class", "sha-a"));

        var record = cache.lookup("k").orElseThrow();
        assertThat(record.units()).isEmpty();
        assertThat(record.outputs()).containsEntry("a.class", "sha-a");
    }

    @Test
    void restore_never_shares_inode_with_cas_blob(@TempDir Path tempDir) throws IOException {
        // Invariant: restored class trees must not hard-link CAS blobs.
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "alpha-content");
        cache.store("compile-main", "key1", Map.of(), outputs);

        Path restored = tempDir.resolve("restored");
        cache.restore(cache.lookup("key1").orElseThrow(), restored);

        Path casBlob = cas.pathFor(
                requireNonNull(cache.lookup("key1").orElseThrow().outputs().get("a.class")));
        Path live = restored.resolve("a.class");
        assertThat(Files.isSameFile(casBlob, live)).isFalse();

        Files.writeString(live, "mutated-in-place");
        assertThat(Files.readString(casBlob)).isEqualTo("alpha-content");
    }

    @Test
    void store_skips_empty_outputs_when_sources_were_present(@TempDir Path tempDir) throws IOException {
        // Invariant: zero-output "success" with source inputs is never action-cached.
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path emptyOut = tempDir.resolve("empty-out");
        Files.createDirectories(emptyOut);
        Map<String, String> sourceInputs = Map.of("/work/src/Foo.java", "abc123");

        cache.store("compile-main", "empty-key", sourceInputs, emptyOut);

        assertThat(cache.lookup("empty-key")).isEmpty();
        assertThat(cache.lastFor("compile-main")).isEmpty();
    }

    /**
     * A verdict is the one empty-output record that is legitimate, and it is reached by a different
     * door. The refusal above exists so a compile that emitted zero classes cannot become a hit
     * restoring an empty tree; a check that produced nothing has no tree to restore and its result
     * is the absence itself. Both facts have to hold at once, so this asserts the pair — relaxing
     * the refusal to make verdicts work would fail the test above, and routing verdicts through
     * {@code store} would fail this one.
     */
    @Test
    void a_verdict_is_recorded_where_an_empty_compile_is_refused(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Map<String, String> inputs = Map.of("build-logic", "some-key");

        Path emptyOut = tempDir.resolve("empty-out");
        Files.createDirectories(emptyOut);
        cache.store("build-logic-check", "via-store", inputs, emptyOut);
        assertThat(cache.lookup("via-store"))
                .as("store still refuses an empty success — the compile rule is untouched")
                .isEmpty();

        cache.storeVerdict("build-logic-check", "via-verdict", inputs);
        assertThat(cache.lookup("via-verdict")).isPresent();
        assertThat(cache.lookup("via-verdict").orElseThrow().outputs()).isEmpty();
    }

    @Test
    void store_refuses_empty_outputs_whatever_the_inputs(@TempDir Path tempDir) throws IOException {
        // A plugin step stores with no input fingerprints at all; a worker that exits 0 having
        // written nothing must not become a hit whose restore hands the build an empty tree.
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path emptyOut = tempDir.resolve("scratch");
        Files.createDirectories(emptyOut.resolve("only-a-dir"));

        cache.store("plugin-gen", "empty-plugin", Map.of(), emptyOut);

        assertThat(cache.lookup("empty-plugin")).isEmpty();
        assertThat(cache.lastFor("plugin-gen")).isEmpty();
        // The door for a legitimately output-less action stays open, and stays explicit.
        cache.storeVerdict("plugin-gen", "verdict", Map.of());
        assertThat(cache.lookup("verdict")).isPresent();
    }

    @Test
    void a_torn_key_record_is_a_miss_and_is_removed(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path actions = tempDir.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("A.class"), "alpha");
        Map<String, String> inputs = Map.of("src/A.java", "aaa");
        cache.store("compile-main", "torn", inputs, outputs);
        Path keyFile = ActionTree.KEYS.under(actions).resolve("torn");
        String full = Files.readString(keyFile);

        // Cut short inside the KEY line, the way a killed engine or a full disk leaves it.
        Files.writeString(keyFile, full.substring(0, full.indexOf("KEY ") + 2));
        assertThat(cache.lookup("torn"))
                .as("a torn record is a miss, not an exception")
                .isEmpty();
        assertThat(keyFile)
                .as("the torn file is gone, so the next store owns the key")
                .doesNotExist();
        assertThat(cache.lastFor("compile-main"))
                .as("the tasks/ pointer to it is a miss too")
                .isEmpty();

        // A line whose shape the parser cannot split is the same case.
        Files.writeString(keyFile, "TASK compile-main\nKEY torn\nINPUT no-space-here\n");
        assertThat(cache.lookup("torn")).isEmpty();
        assertThat(keyFile).doesNotExist();

        // The next real run repairs it.
        cache.store("compile-main", "torn", inputs, outputs);
        assertThat(cache.lookup("torn")).isPresent();
        assertThat(cache.lastFor("compile-main")).isPresent();
    }

    @Test
    void a_blank_task_pointer_is_a_miss(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path actions = tempDir.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        Path pointer = ActionTree.TASKS.under(actions).resolve("compile-main");
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, "");

        assertThat(cache.lastFor("compile-main")).isEmpty();
    }

    @Test
    void restore_after_smaller_source_set_does_not_leave_stale_classes(@TempDir Path tempDir) throws IOException {
        // Invariant: variant / shrink source set — stale classes from prior output are wiped.
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("Keep.class"), "keep");
        Files.writeString(outputs.resolve("Stale.class"), "stale");
        cache.store("compile-main", "full", Map.of(), outputs);

        // Next variant only produces Keep.class
        Files.delete(outputs.resolve("Stale.class"));
        Files.writeString(outputs.resolve("Keep.class"), "keep-v2");
        cache.store("compile-main", "slim", Map.of(), outputs);

        // Pollute the live tree with a leftover from the prior variant.
        Files.writeString(outputs.resolve("Stale.class"), "should-not-survive");
        cache.restore(cache.lookup("slim").orElseThrow(), outputs);

        assertThat(outputs.resolve("Keep.class")).exists();
        assertThat(Files.readString(outputs.resolve("Keep.class"))).isEqualTo("keep-v2");
        assertThat(outputs.resolve("Stale.class")).doesNotExist();
    }

    @Test
    void store_artifacts_refuses_missing_files(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path base = tempDir.resolve("target");
        Files.createDirectories(base);
        Path missing = base.resolve("jk");
        assertThatThrownBy(() -> cache.storeArtifacts("native-image", "key-empty", Map.of(), base, List.of(missing)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no files to cache");
        assertThat(cache.lookup("key-empty")).isEmpty();
    }

    @Test
    void store_artifacts_round_trips_a_windows_style_exe(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path base = tempDir.resolve("target");
        Files.createDirectories(base);
        Path exe = base.resolve("jk.exe");
        Files.writeString(exe, "native-image-bytes");
        cache.storeArtifacts("native-image", "key-exe", Map.of(), base, List.of(exe));
        Files.delete(exe);
        assertThat(cache.restoreArtifacts(cache.lookup("key-exe").orElseThrow(), base))
                .isTrue();
        assertThat(exe).exists();
        assertThat(Files.readString(exe)).isEqualTo("native-image-bytes");
    }

    @Test
    void concurrent_store_lookup_never_reads_torn_metadata(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "payload");

        int threads = 8;
        int rounds = 40;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> fail = new AtomicReference<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    try {
                        assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                        for (int i = 0; i < rounds; i++) {
                            String key = "k" + (i % 4);
                            String task = "task-" + id;
                            cache.storeWithOutputs(task, key, Map.of("s", "1"), Map.of("a.class", "deadbeef"));
                            cache.lookup(key); // must not throw on partial write
                            cache.lastFor(task);
                        }
                    } catch (Throwable e) {
                        fail.compareAndSet(null, e);
                    }
                }));
            }
            start.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
        }
        if (fail.get() != null) {
            throw new AssertionError("torn metadata observed", fail.get());
        }
    }
}
