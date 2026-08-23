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

    private static final long RECENT = Duration.ofHours(2).toMillis(); // past the grace window
    private static final long OLD = Duration.ofDays(30).toMillis();
    private static final long OLDER = Duration.ofDays(60).toMillis();
    private static final long OLDEST = Duration.ofDays(90).toMillis();
    private static final long ANCIENT = Duration.ofDays(365).toMillis();
    private static final long FRESH = Duration.ofMinutes(1).toMillis(); // inside MIN_AGE_FOR_SWEEP

    // ---------------------------------------------------------------- budget pass

    @Test
    void evicts_oldest_key_first_and_stops_at_the_budget(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String shaA = blob(cas, 300_000, 'a', OLDEST);
        String shaB = blob(cas, 200_000, 'b', OLDER);
        String shaC = blob(cas, 100_000, 'c', OLD);
        Path keyA = key(root, "key-a", "compile-main@a", OLDEST, shaA);
        Path keyB = key(root, "key-b", "compile-main@b", OLDER, shaB);
        Path keyC = key(root, "key-c", "compile-main@c", OLD, shaC);

        var report = ActionCachePrune.run(root, cas, budgetOnly(350_000), Set.of(), false);

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

    /**
     * Supersession outranks age: a key that is neither its task's pointer nor in its generation list
     * can never be hit again, so taking it costs nothing — while the older key it jumps ahead of is
     * still reachable and would cost a recompute.
     */
    @Test
    void takes_a_superseded_entry_before_an_older_reachable_one(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String dead = blob(cas, 300_000, 'd', RECENT);
        String reachable = blob(cas, 100_000, 'r', OLDER);
        Path supersededKey = key(root, "key-churn", "compile-main@churn", RECENT, dead);
        Path reachableKey = key(root, "key-stable", "compile-main@stable", OLDER, reachable);
        pointAt(root, "compile-main@churn", "key-churn-newer"); // its inputs moved on

        var report = ActionCachePrune.run(root, cas, budgetOnly(150_000), Set.of(), false);

        assertThat(supersededKey).doesNotExist();
        assertThat(reachableKey)
                .as("the older key is still reachable, so it outranks a young dead one")
                .exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
    }

    @Test
    void keeps_a_blob_a_surviving_key_still_references(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String shared = blob(cas, 200_000, 's', OLDEST);
        String privateToA = blob(cas, 300_000, 'p', OLDEST);
        Path keyA = key(root, "key-a", "compile-main@a", OLDEST, shared, privateToA);
        Path keyB = key(root, "key-b", "compile-main@b", OLD, shared);

        var report = ActionCachePrune.run(root, cas, budgetOnly(350_000), Set.of(), false);

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
        Path youngKey = key(root, "key-young", "compile-main@b", RECENT, firstSeenLongAgo);

        ActionCachePrune.run(root, cas, budgetOnly(350_000), Set.of(), false);

        assertThat(oldKey).doesNotExist();
        assertThat(youngKey).exists();
        assertThat(cas.pathFor(firstSeenLongAgo)).exists();
    }

    @Test
    void old_key_with_young_blob_loses_the_key_and_keeps_the_blob(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String justWritten = blob(cas, 300_000, 'j', FRESH);
        Path oldKey = key(root, "key-old", "compile-main@a", OLDEST, justWritten);

        var report = ActionCachePrune.run(root, cas, budgetOnly(1_000), Set.of(), false);

        assertThat(oldKey).doesNotExist();
        assertThat(cas.pathFor(justWritten)).exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
        assertThat(report.deletedBlobs()).isZero();
        assertThat(report.finalBytes())
                .as("the budget is soft while writers are active")
                .isGreaterThan(1_000);
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
        var report = ActionCachePrune.run(root, cas, budgetOnly(200_000), sweep.deletedShas(), true);

        assertThat(report.deletedKeys()).isZero();
        assertThat(report.deletedBlobs()).isZero();
        assertThat(liveKey).exists();
    }

    @Test
    void skips_entries_inside_the_write_grace_window(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 300_000, 'w', FRESH);
        Path freshKey = key(root, "key-fresh", "compile-main@a", FRESH, sha);

        var report = ActionCachePrune.run(root, cas, budgetOnly(1_000), Set.of(), false);

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

        ActionCachePrune.run(root, cas, budgetOnly(1_000), Set.of(), false);

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

        var report = ActionCachePrune.run(root, cas, budgetOnly(1_000), Set.of(), false);

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

        var report = ActionCachePrune.run(root, cas, budgetOnly(1024L * 1024 * 1024), Set.of(), false);

        assertThat(keyFile).exists();
        assertThat(cas.pathFor(sha)).exists();
        assertThat(report.deletedKeys()).isZero();
        assertThat(report.freedBytes()).isZero();
        assertThat(report.finalBytes()).isGreaterThan(300_000);
    }

    /** An unset budget disables the budget pass; the pass still measures, because the windows run. */
    @Test
    void an_unset_budget_evicts_nothing_and_still_reports_the_tier(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 300_000, 'u', OLDEST);
        Path keyFile = key(root, "key-a", "compile-main@a", OLDEST, sha);

        var report = ActionCachePrune.run(root, cas, budgetOnly(0L), Set.of(), false);

        assertThat(keyFile).exists();
        assertThat(report.deletedKeys()).isZero();
        assertThat(report.freedBytes()).isZero();
        assertThat(report.finalBytes()).isGreaterThan(300_000);
    }

    @Test
    void an_absent_actions_dir_is_an_empty_report(@TempDir Path root) throws IOException {
        assertThat(ActionCachePrune.run(root, new Cas(root), budgetOnly(1_000), Set.of(), false))
                .isEqualTo(ActionCachePrune.Report.EMPTY);
    }

    // ---------------------------------------------------------------- window passes

    /** The window is unconditional: work nobody will ask for again goes whether or not we are tight. */
    @Test
    void the_action_window_takes_a_cold_entry_far_under_budget(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String coldSha = blob(cas, 100_000, 'c', OLDER);
        String warmSha = blob(cas, 100_000, 'w', RECENT);
        Path cold = key(root, "key-cold", "compile-main@cold", OLDER, coldSha);
        Path warm = key(root, "key-warm", "compile-main@warm", RECENT, warmSha);

        var report = ActionCachePrune.run(root, cas, windows(1024L * 1024 * 1024), Set.of(), false);

        assertThat(cold).doesNotExist();
        assertThat(cas.pathFor(coldSha)).doesNotExist();
        assertThat(warm).exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- memo tier

    /**
     * The headline of the memo tier: a {@code run-tests} stamp is a few hundred bytes, so evicting
     * one to satisfy a byte budget reclaims a rounding error and costs a whole test run.
     */
    @Test
    void the_byte_budget_never_takes_a_memo(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 300_000, 'a', OLDEST);
        Path action = key(root, "key-action", "compile-main@a", OLDEST, sha);
        Path memo = key(root, "key-memo", "run-tests@a", ANCIENT); // no OUTPUT: the record is the result

        var report = ActionCachePrune.run(root, cas, budgetOnly(1_000), Set.of(), false);

        assertThat(action).doesNotExist();
        assertThat(memo)
                .as("older than the action key and still kept — bytes are the wrong denominator here")
                .exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
    }

    @Test
    void the_memo_window_takes_a_stamp_nothing_has_asked_for_in_a_quarter(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        Path stale = key(root, "memo-stale", "run-tests@stale", ANCIENT);
        Path recent = key(root, "memo-recent", "run-tests@recent", OLD);

        var report = ActionCachePrune.run(root, cas, windows(1024L * 1024 * 1024), Set.of(), false);

        assertThat(stale).doesNotExist();
        assertThat(recent).as("30 days is well inside the 90-day memo window").exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
    }

    @Test
    void the_memo_cap_trims_the_coldest_first(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        Path coldest = key(root, "memo-a", "run-tests@a", OLDEST);
        Path middle = key(root, "memo-b", "run-tests@b", OLDER);
        Path newest = key(root, "memo-c", "run-tests@c", OLD);

        // Budget and windows off: the count cap is the only thing that can act.
        var policy = new ActionCachePrune.Policy(0L, Duration.ZERO, Duration.ZERO, 2, 0L, Duration.ZERO);
        var report = ActionCachePrune.run(root, cas, policy, Set.of(), false);

        assertThat(coldest).doesNotExist();
        assertThat(middle).exists();
        assertThat(newest).exists();
        assertThat(report.deletedKeys()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- incremental tier

    /**
     * The reason the denominators are split. 5 MiB of Zinc state under {@code actions/} must not
     * evict a 100 KB action entry that fits its own budget — with one budget over the whole tree the
     * prune would take every action key and still be over.
     */
    @Test
    void zinc_bytes_do_not_count_against_the_action_budget(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        String sha = blob(cas, 100_000, 'a', RECENT);
        Path action = key(root, "key-a", "compile-main@a", RECENT, sha);
        analysis(root, "compile-main@big", 5 * 1024 * 1024, RECENT);

        // Action budget comfortably fits the action tier; the incremental budget is off entirely.
        var policy = new ActionCachePrune.Policy(1_000_000L, Duration.ZERO, Duration.ZERO, 0, 0L, Duration.ZERO);
        var report = ActionCachePrune.run(root, cas, policy, Set.of(), false);

        assertThat(action).exists();
        assertThat(report.deletedKeys()).isZero();
        assertThat(report.finalBytes())
                .as("the action denominator excludes the Zinc trees")
                .isLessThan(1_000_000L);
        assertThat(report.incrementalFinalBytes()).isGreaterThan(5L * 1024 * 1024 - 1);
    }

    @Test
    void the_incremental_window_drops_a_cold_analysis_tree(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        Path cold =
                analysis(root, "compile-main@cold", 4_096, Duration.ofDays(10).toMillis());
        Path hot = analysis(root, "compile-main@hot", 4_096, Duration.ofDays(1).toMillis());

        var report = ActionCachePrune.run(root, cas, windows(1024L * 1024 * 1024), Set.of(), false);

        assertThat(cold).doesNotExist();
        assertThat(hot).as("inside the 7-day edit loop").exists();
        assertThat(report.deletedIncrementalFiles()).isEqualTo(1);
        assertThat(report.incrementalFreedBytes()).isEqualTo(4_096);
    }

    @Test
    void the_incremental_budget_takes_the_coldest_tree_first(@TempDir Path root) throws IOException {
        Cas cas = new Cas(root);
        Path colder = analysis(
                root, "compile-main@colder", 400_000, Duration.ofDays(3).toMillis());
        Path warmer = analysis(
                root, "compile-main@warmer", 400_000, Duration.ofDays(1).toMillis());

        // Both are inside the 7-day window, so only the byte budget can act — and only on one.
        var policy = new ActionCachePrune.Policy(
                0L, Duration.ZERO, Duration.ZERO, 0, 500_000L, ActionCachePrune.Policy.INCREMENTAL_WINDOW);
        var report = ActionCachePrune.run(root, cas, policy, Set.of(), false);

        assertThat(colder).doesNotExist();
        assertThat(warmer).exists();
        assertThat(report.incrementalFinalBytes()).isEqualTo(400_000);
    }

    // ---------------------------------------------------------------- dry run

    @Test
    void dry_run_totals_match_a_real_run(@TempDir Path tempDir) throws IOException {
        long[] dry = sweepThenPrune(tempDir.resolve("dry"), true);
        long[] real = sweepThenPrune(tempDir.resolve("real"), false);

        assertThat(dry[0]).as("files").isEqualTo(real[0]);
        assertThat(dry[1]).as("bytes").isEqualTo(real[1]);
    }

    /** One sweep-then-prune pass over an identical tree; returns {files, bytes} the way prune sums them. */
    private static long[] sweepThenPrune(Path root, boolean dryRun) throws IOException {
        Files.createDirectories(root);
        Cas cas = new Cas(root);
        blob(cas, 600, 'g', OLDEST); // unreachable — the sweep's victim
        String live = blob(cas, 300_000, 'l', OLDEST);
        key(root, "key-live", "compile-main@a", OLDEST, live);
        key(root, "memo-stale", "run-tests@a", ANCIENT); // taken by the memo window
        analysis(root, "compile-main@cold", 4_096, Duration.ofDays(10).toMillis()); // by the Zinc window

        var roots = CacheRoots.collect(cas, root.resolve("actions"), root.resolve("tools"));
        var sweep = CasSweep.sweep(cas, roots, dryRun);
        var prune = ActionCachePrune.run(root, cas, windows(1_000), sweep.deletedShas(), dryRun);
        return new long[] {sweep.deleted() + prune.totalDeletedFiles(), sweep.freedBytes() + prune.totalFreedBytes()};
    }

    // ---------------------------------------------------------------- fixtures

    /** Budget pass only: windows and the memo cap off, so eviction order is the only thing under test. */
    private static ActionCachePrune.Policy budgetOnly(long actionBudgetBytes) {
        return new ActionCachePrune.Policy(actionBudgetBytes, Duration.ZERO, Duration.ZERO, 0, 0L, Duration.ZERO);
    }

    /** The shipped windows against {@code actionBudgetBytes}; the memo cap stays off. */
    private static ActionCachePrune.Policy windows(long actionBudgetBytes) {
        return new ActionCachePrune.Policy(
                actionBudgetBytes,
                ActionCachePrune.Policy.ACTION_WINDOW,
                ActionCachePrune.Policy.MEMO_WINDOW,
                0,
                0L,
                ActionCachePrune.Policy.INCREMENTAL_WINDOW);
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
        pointAt(cacheRoot, taskId, actionKey);
        backdate(keyFile, ageMillis);
        return keyFile;
    }

    /** Aim {@code taskId}'s pointer at {@code actionKey} — at a stranger, to mark a record superseded. */
    private static void pointAt(Path cacheRoot, String taskId, String actionKey) throws IOException {
        Path pointer = cacheRoot.resolve("actions").resolve("tasks").resolve(taskId);
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, actionKey, StandardCharsets.UTF_8);
    }

    /** One task's Zinc analysis tree: {@code actions/incremental-java/<taskId>/zinc}. */
    private static Path analysis(Path cacheRoot, String taskId, int size, long ageMillis) throws IOException {
        Path dir = cacheRoot.resolve("actions").resolve("incremental-java").resolve(taskId);
        Files.createDirectories(dir);
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 'z');
        Path zinc = dir.resolve("zinc");
        Files.write(zinc, payload);
        backdate(zinc, ageMillis);
        return dir;
    }

    private static void backdate(Path file, long ageMillis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
    }
}
