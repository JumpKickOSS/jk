// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionCachePruneTest {

    private static final long OLD = Duration.ofDays(30).toMillis();
    private static final long OLDER = Duration.ofDays(60).toMillis();
    private static final long OLDEST = Duration.ofDays(90).toMillis();
    private static final long ANCIENT = Duration.ofDays(365).toMillis();
    private static final long FRESH = Duration.ofMinutes(1).toMillis(); // inside MIN_AGE_FOR_SWEEP

    @Test
    void evicts_oldest_key_first_and_stops_at_the_budget(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String shaA = blob(cas, 300_000, 'a', OLDEST);
        String shaB = blob(cas, 200_000, 'b', OLDER);
        String shaC = blob(cas, 100_000, 'c', OLD);
        Path keyA = key(root, "key-a", "compile-main@a", OLDEST, shaA);
        Path keyB = key(root, "key-b", "compile-main@b", OLDER, shaB);
        Path keyC = key(root, "key-c", "compile-main@c", OLD, shaC);

        var report = ActionCachePrune.toBudget(root, cas, 350_000, Set.of(), false);

        assertThat(keyA).doesNotExist();
        assertThat(cas.pathFor(shaA)).doesNotExist();
        assertThat(keyB).exists();
        assertThat(keyC).exists();
        assertThat(cas.pathFor(shaB)).exists();
        assertThat(cas.pathFor(shaC)).exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
        assertThat(report.deletedBlobs()).isEqualTo(1);
        assertThat(report.finalBytes()).isLessThanOrEqualTo(350_000);
    }

    @Test
    void keeps_a_blob_a_surviving_key_still_references(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String shared = blob(cas, 200_000, 's', OLDEST);
        String privateToA = blob(cas, 300_000, 'p', OLDEST);
        Path keyA = key(root, "key-a", "compile-main@a", OLDEST, shared, privateToA);
        Path keyB = key(root, "key-b", "compile-main@b", OLD, shared);

        var report = ActionCachePrune.toBudget(root, cas, 350_000, Set.of(), false);

        assertThat(keyA).doesNotExist();
        assertThat(keyB).exists();
        assertThat(cas.pathFor(shared)).exists();
        assertThat(cas.pathFor(privateToA)).doesNotExist();
        assertThat(report.deletedBlobs()).isEqualTo(1);
    }

    /**
     * The CAS is write-once, so a blob's mtime is its first store time. An entry that ran this
     * morning over bytes first seen a year ago is current work, and ranking on the blob would eat it.
     */
    @Test
    void young_key_with_old_blob_survives_and_keeps_its_blob(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String recentlyStored = blob(cas, 300_000, 'r', OLDEST);
        String firstSeenLongAgo = blob(cas, 200_000, 'f', ANCIENT);
        Path oldKey = key(root, "key-old", "compile-main@a", OLDEST, recentlyStored);
        Path youngKey =
                key(root, "key-young", "compile-main@b", Duration.ofHours(2).toMillis(), firstSeenLongAgo);

        ActionCachePrune.toBudget(root, cas, 350_000, Set.of(), false);

        assertThat(oldKey).doesNotExist();
        assertThat(youngKey).exists();
        assertThat(cas.pathFor(firstSeenLongAgo)).exists();
    }

    @Test
    void old_key_with_young_blob_loses_the_key_and_keeps_the_blob(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String justWritten = blob(cas, 300_000, 'j', FRESH);
        Path oldKey = key(root, "key-old", "compile-main@a", OLDEST, justWritten);

        var report = ActionCachePrune.toBudget(root, cas, 1_000, Set.of(), false);

        assertThat(oldKey).doesNotExist();
        assertThat(cas.pathFor(justWritten)).exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
        assertThat(report.deletedBlobs()).isZero();
        assertThat(report.finalBytes())
                .as("the budget is soft while writers are active")
                .isGreaterThan(1_000);
    }

    @Test
    void dry_run_totals_match_a_real_run(@TempDir Path tempDir) throws IOException {
        long[] dry = sweepThenPrune(tempDir.resolve("dry"), true);
        long[] real = sweepThenPrune(tempDir.resolve("real"), false);

        assertThat(dry[0]).as("files").isEqualTo(real[0]);
        assertThat(dry[1]).as("bytes").isEqualTo(real[1]);
    }

    @Test
    void skips_shas_the_sweep_already_claimed(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String garbage = blob(cas, 600_000, 'g', OLDEST); // named by no key — the sweep's victim
        String live = blob(cas, 100_000, 'l', OLDEST);
        Path liveKey = key(root, "key-live", "compile-main@a", OLDEST, live);

        var roots = CacheRoots.collect(cas, root.resolve("actions"), root.resolve("tools"));
        var sweep = CasSweep.sweep(cas, roots, true);
        assertThat(sweep.deletedShas()).containsExactly(garbage);

        // Without the hand-off the garbage still on disk pushes the pool over budget and the live
        // entry is evicted to make room for bytes the same pass already claimed.
        var report = ActionCachePrune.toBudget(root, cas, 200_000, sweep.deletedShas(), true);

        assertThat(report.deletedKeys()).isZero();
        assertThat(report.deletedBlobs()).isZero();
        assertThat(liveKey).exists();
    }

    @Test
    void skips_entries_inside_the_write_grace_window(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 300_000, 'w', FRESH);
        Path freshKey = key(root, "key-fresh", "compile-main@a", FRESH, sha);

        var report = ActionCachePrune.toBudget(root, cas, 1_000, Set.of(), false);

        assertThat(freshKey).exists();
        assertThat(cas.pathFor(sha)).exists();
        assertThat(report.deletedKeys()).isZero();
        assertThat(report.finalBytes()).isGreaterThan(1_000);
    }

    @Test
    void drops_the_task_pointer_and_generation_entry_with_the_key(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 300_000, 'n', OLDEST);
        Path evicted = key(root, "gen-current", "native-image@mod", OLDEST, sha);
        Path tasks = root.resolve("actions").resolve("tasks");
        Path gens = HeavyActionPolicy.gensFile(tasks, "native-image@mod");
        Files.writeString(gens, "gen-current\ngen-previous\n");

        ActionCachePrune.toBudget(root, cas, 1_000, Set.of(), false);

        assertThat(evicted).doesNotExist();
        assertThat(tasks.resolve("native-image@mod")).doesNotExist();
        assertThat(Files.readString(gens)).isEqualTo("gen-previous\n");
    }

    /**
     * A record with no {@code TASK} line has no pointer or generation list to unlink, so it is not
     * an entry the prune can delete coherently — and its outputs stay rooted, because
     * {@link CacheRoots#collect} roots them from the same text and the two must not disagree.
     */
    @Test
    void a_record_with_no_task_line_roots_its_outputs_and_is_never_evicted(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String orphanedByShape = blob(cas, 400_000, 'x', OLDEST);
        Path taskless = root.resolve("actions").resolve("keys").resolve("no-task");
        Files.createDirectories(taskless.getParent());
        Files.writeString(taskless, "KEY no-task\nOUTPUT " + orphanedByShape + " out/0.bin\n");
        backdate(taskless, ANCIENT);

        var report = ActionCachePrune.toBudget(root, cas, 1_000, Set.of(), false);

        assertThat(taskless).exists();
        assertThat(cas.pathFor(orphanedByShape)).exists();
        assertThat(report.deletedKeys()).isZero();
        assertThat(CacheRoots.collect(cas, root.resolve("actions"), root.resolve("tools")))
                .as("the sweep roots it too, so nothing reclaims these bytes short of `jk cache nuke`")
                .contains(orphanedByShape);
    }

    @Test
    void under_budget_is_a_no_op(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 300_000, 'u', OLDEST);
        Path keyFile = key(root, "key-a", "compile-main@a", OLDEST, sha);

        var report = ActionCachePrune.toBudget(root, cas, 1024L * 1024 * 1024, Set.of(), false);

        assertThat(keyFile).exists();
        assertThat(cas.pathFor(sha)).exists();
        assertThat(report.deletedKeys()).isZero();
        assertThat(report.freedBytes()).isZero();
        assertThat(report.finalBytes()).isGreaterThan(300_000);
        assertThat(ActionCachePrune.toBudget(root, cas, 0L, Set.of(), false))
                .as("an unset budget measures nothing")
                .isEqualTo(new ActionCachePrune.Report(0, 0, 0L, 0L));
    }

    /** One sweep-then-prune pass over an identical tree; returns {files, bytes} the way prune sums them. */
    private static long[] sweepThenPrune(Path root, boolean dryRun) throws IOException {
        Files.createDirectories(root);
        Cas cas = new Cas(root);
        blob(cas, 600, 'g', OLDEST); // unreachable — the sweep's victim
        String live = blob(cas, 300_000, 'l', OLDEST);
        key(root, "key-live", "compile-main@a", OLDEST, live);

        var roots = CacheRoots.collect(cas, root.resolve("actions"), root.resolve("tools"));
        var sweep = CasSweep.sweep(cas, roots, dryRun);
        var prune = ActionCachePrune.toBudget(root, cas, 1_000, sweep.deletedShas(), dryRun);
        return new long[] {
            sweep.deleted() + prune.deletedKeys() + prune.deletedBlobs(), sweep.freedBytes() + prune.freedBytes()
        };
    }

    /** Store {@code size} identical bytes and backdate the blob by {@code ageMillis}. */
    private static String blob(Cas cas, int size, char fill, long ageMillis) throws IOException {
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) fill);
        backdate(cas.put(payload), ageMillis);
        return Hashing.sha256Hex(payload);
    }

    /** Write an action record plus its task pointer, backdating the record by {@code ageMillis}. */
    private static Path key(Path cacheRoot, String actionKey, String taskId, long ageMillis, String... shas)
            throws IOException {
        StringBuilder body = new StringBuilder("TASK ")
                .append(taskId)
                .append("\nKEY ")
                .append(actionKey)
                .append('\n');
        for (int i = 0; i < shas.length; i++) {
            body.append("OUTPUT ").append(shas[i]).append(" out/").append(i).append(".bin\n");
        }
        Path keyFile = cacheRoot.resolve("actions").resolve("keys").resolve(actionKey);
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, body.toString(), StandardCharsets.UTF_8);
        Path pointer = cacheRoot.resolve("actions").resolve("tasks").resolve(taskId);
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, actionKey, StandardCharsets.UTF_8);
        backdate(keyFile, ageMillis);
        return keyFile;
    }

    private static void backdate(Path file, long ageMillis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
    }
}
