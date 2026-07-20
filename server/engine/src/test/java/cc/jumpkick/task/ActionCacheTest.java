// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionCacheTest {

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
        // Invariant: restored class trees must not hard-link CAS blobs (ticket-1004).
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));

        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "alpha-content");
        cache.store("compile-main", "key1", Map.of(), outputs);

        Path restored = tempDir.resolve("restored");
        cache.restore(cache.lookup("key1").orElseThrow(), restored);

        Path casBlob = cas.pathFor(cache.lookup("key1").orElseThrow().outputs().get("a.class"));
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

    @Test
    void restore_after_smaller_source_set_does_not_leave_stale_classes(@TempDir Path tempDir)
            throws IOException {
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
    void concurrent_store_lookup_never_reads_torn_metadata(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("a.class"), "payload");

        int threads = 8;
        int rounds = 40;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> fail =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
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
        for (var f : futures) f.get();
        pool.shutdown();
        if (fail.get() != null) {
            throw new AssertionError("torn metadata observed", fail.get());
        }
    }
}
