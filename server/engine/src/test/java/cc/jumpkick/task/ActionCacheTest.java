// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    void an_input_value_with_spaces_and_percents_reads_back_whole(@TempDir Path tempDir) throws IOException {
        // A packaging step stores its token bag as one input value, and an args token carries
        // spaces; the record line is split at its first space, so the value has to be encoded.
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Files.writeString(outputs.resolve("bin"), "b");
        String bag = "cp:abc;args:-H:+Unlock -H:Dirs=/m/a,/m/b 100%;main:t.Main";
        cache.store("native-image", "spaced", Map.of("inputs", bag), outputs);

        var record = cache.lookup("spaced").orElseThrow();
        assertThat(record.inputs()).containsExactly(Map.entry("inputs", bag));
        assertThat(ActionCache.encodeValue("abc123")).isEqualTo("abc123");
        assertThat(ActionCache.decodeValue(ActionCache.encodeValue("a b%c"))).isEqualTo("a b%c");
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
    void a_record_round_trips_its_outputs(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        cache.storeWithOutputs("compile-main@x", "k", Map.of(), Map.of("a.class", "sha-a"));

        var record = cache.lookup("k").orElseThrow();
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

    /**
     * Two checkouts of one project share a task pointer but, on different branches, not a key.
     * Trimming generations by pointer flips would take the other checkout's key on every
     * alternate build; the generations are counted per checkout instead.
     */
    @Test
    void two_checkouts_alternating_a_class_c_task_both_keep_hitting(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path actions = tempDir.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        String task = TaskNames.WRITE_IMAGE + "@shared"; // one generation per checkout
        Path a = Files.createDirectories(tempDir.resolve("checkout-a/target"));
        Path b = Files.createDirectories(tempDir.resolve("checkout-b/target"));

        for (int round = 0; round < 3; round++) {
            storeImage(cache, task, "key-a", a);
            storeImage(cache, task, "key-b", b);
        }
        assertThat(cache.lookup("key-a")).as("A's key survives B's stores").isPresent();
        assertThat(cache.lookup("key-b")).isPresent();
        assertThat(cache.restoreArtifacts(cache.lookup("key-a").orElseThrow(), a))
                .isTrue();

        // A's own next generation replaces A's previous one and leaves B's alone.
        storeImage(cache, task, "key-a2", a);
        assertThat(cache.lookup("key-a"))
                .as("A's superseded generation is dropped")
                .isEmpty();
        assertThat(cache.lookup("key-a2")).isPresent();
        assertThat(cache.lookup("key-b"))
                .as("B's generation is not A's to drop")
                .isPresent();
        assertThat(HeavyActionPolicy.readGenerations(HeavyActionPolicy.gensFile(ActionTree.TASKS.under(actions), task)))
                .extracting(HeavyActionPolicy.Generation::key)
                .containsExactlyInAnyOrder("key-a2", "key-b");
    }

    /** A key two checkouts both stored goes only when neither checkout's generations name it. */
    @Test
    void a_generation_two_checkouts_share_outlives_either_ones_trim(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        String task = TaskNames.NATIVE_IMAGE + "@shared"; // two generations per checkout
        Path a = Files.createDirectories(tempDir.resolve("checkout-a/target"));
        Path b = Files.createDirectories(tempDir.resolve("checkout-b/target"));

        storeImage(cache, task, "key-same", a);
        storeImage(cache, task, "key-same", b);
        storeImage(cache, task, "key-a2", a);
        storeImage(cache, task, "key-a3", a);
        assertThat(cache.lookup("key-same"))
                .as("A trimmed it from its own generations; B still names it")
                .isPresent();
        storeImage(cache, task, "key-b2", b);
        storeImage(cache, task, "key-b3", b);
        assertThat(cache.lookup("key-same")).as("no checkout names it any more").isEmpty();
        assertThat(cache.lookup("key-a2")).isPresent();
        assertThat(cache.lookup("key-b2")).isPresent();
        assertThat(cache.lastFor(task).orElseThrow().actionKey()).isEqualTo("key-b3");
    }

    /** A record whose store path carries no output root keeps no generation list. */
    @Test
    void a_store_with_no_output_root_keeps_no_generations(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        Path actions = tempDir.resolve("actions");
        ActionCache cache = new ActionCache(cas, actions);
        String task = TaskNames.NATIVE_IMAGE + "@shared";
        cache.storeWithOutputs(task, "k1", Map.of(), Map.of("bin", "sha"));
        cache.storeWithOutputs(task, "k2", Map.of(), Map.of("bin", "sha"));
        cache.storeWithOutputs(task, "k3", Map.of(), Map.of("bin", "sha"));
        assertThat(cache.lookup("k1")).isPresent();
        assertThat(HeavyActionPolicy.gensFile(ActionTree.TASKS.under(actions), task))
                .doesNotExist();
    }

    /** One artifact under {@code base}, stored as a Class-C generation of {@code task}. */
    private static void storeImage(ActionCache cache, String task, String key, Path base) throws IOException {
        Path artifact = Files.writeString(base.resolve("app.tar"), "image " + key);
        cache.storeArtifacts(task, key, Map.of(), base, List.of(artifact));
    }

    /**
     * A record this build could not publish costs the next build a rebuild, not this build a
     * failure. Windows denies a replace while any handle has the target open — even one opened
     * with {@code FILE_SHARE_DELETE} — so a concurrent {@code lookup} of the same key can outlast
     * what {@link cc.jumpkick.util.AtomicWrites} is willing to retry. The step has already
     * succeeded and its outputs are already in the CAS by then; throwing would discard both.
     *
     * <p>Reached here through an unwritable keys directory and a spoofed {@code os.name}, the way
     * {@code AtomicWritesTest} reaches the Windows retry from a POSIX host: a real denial by a
     * live reader cannot be produced to order. POSIX keeps its own denial loud — see the case
     * below — because there a denial is a permissions fault that waiting cannot clear.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_record_that_cannot_be_published_on_windows_is_returned_rather_than_thrown(@TempDir Path tempDir)
            throws Exception {
        ActionCache cache = new ActionCache(new Cas(tempDir.resolve("cas")), tempDir.resolve("actions"));
        Path keys = Files.createDirectories(ActionTree.KEYS.under(tempDir.resolve("actions")));
        String realOs = System.getProperty("os.name");
        Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(keys), "running as root — the mode bits deny nothing");
            System.setProperty("os.name", "Windows 11");

            ActionCache.ActionRecord record =
                    cache.storeWithOutputs("compile-main", "k0", Map.of("s", "1"), Map.of("a.class", "deadbeef"));

            assertThat(record.actionKey()).isEqualTo("k0");
            assertThat(record.outputs()).containsEntry("a.class", "deadbeef");
            assertThat(keys.resolve("k0")).as("nothing published").doesNotExist();
        } finally {
            if (realOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", realOs);
            Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * Windows denies a read of a name a {@code REPLACE_EXISTING} is landing on, the mirror of
     * denying the replace while a reader holds it. A lookup that cannot read its key is a miss —
     * the caller re-runs the action — and never a failure of the step that asked, which is what
     * a propagated {@link AccessDeniedException} made it. A directory standing in for the key
     * file is the denial this platform can be made to produce on demand; the race produces the
     * same exception from the same call.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void a_key_the_platform_denies_reads_as_a_miss_not_a_failure(@TempDir Path tempDir) throws Exception {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        Path keys = tempDir.resolve("actions").resolve("keys");
        Files.createDirectories(keys.resolve("denied"));
        Files.createDirectories(tempDir.resolve("actions").resolve("tasks"));
        Files.createDirectories(tempDir.resolve("actions").resolve("tasks").resolve("t-denied"));

        assertThat(cache.lookup("denied"))
                .as("a key that will not open is a miss")
                .isEmpty();
        assertThat(cache.lastFor("t-denied"))
                .as("the pointer read behind lastFor answers the same way")
                .isEmpty();
    }

    /** On POSIX a denied publish is a permissions fault, and stays loud rather than losing an entry quietly. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void a_record_that_cannot_be_published_on_posix_still_throws(@TempDir Path tempDir) throws Exception {
        ActionCache cache = new ActionCache(new Cas(tempDir.resolve("cas")), tempDir.resolve("actions"));
        Path keys = Files.createDirectories(ActionTree.KEYS.under(tempDir.resolve("actions")));
        Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(keys), "running as root — the mode bits deny nothing");

            assertThatThrownBy(() ->
                            cache.storeWithOutputs("compile-main", "k0", Map.of("s", "1"), Map.of("a.class", "dead")))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
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

    /**
     * The record a forecast reads is this checkout's own last compile — the one the state's ledger
     * names — not whichever checkout flipped the shared pointer last; without a ledger, or with a
     * ledger naming a pruned record, the pointer stands in.
     */
    @Test
    void the_last_record_for_a_state_dir_is_the_one_its_ledger_names(@TempDir Path tempDir) throws IOException {
        Cas cas = new Cas(tempDir.resolve("cas"));
        ActionCache cache = new ActionCache(cas, tempDir.resolve("actions"));
        cache.storeWithOutputs("compile-java@t", "mine", Map.of("src/A.java", "a1"), Map.of());
        cache.storeWithOutputs("compile-java@t", "theirs", Map.of("src/A.java", "a2"), Map.of());
        Path state = Files.createDirectories(tempDir.resolve("state"));

        assertThat(cache.lastFor("compile-java@t", state).map(ActionCache.ActionRecord::actionKey))
                .as("no ledger: the pointer")
                .contains("theirs");
        LangCompile.recordTree(state, "mine", Map.of());
        assertThat(cache.lastFor("compile-java@t", state).map(ActionCache.ActionRecord::actionKey))
                .as("the ledger's compile")
                .contains("mine");
        LangCompile.recordTree(state, "gone", Map.of());
        assertThat(cache.lastFor("compile-java@t", state).map(ActionCache.ActionRecord::actionKey))
                .as("a pruned record: the pointer again")
                .contains("theirs");
        assertThat(cache.lastFor("compile-java@t", null).map(ActionCache.ActionRecord::actionKey))
                .contains("theirs");
    }
}
