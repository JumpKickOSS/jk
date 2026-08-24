// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.runtime.ReachabilityMetadata;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every {@link CacheTree} is bounded on a path that actually runs, or says why it is not.
 *
 * <p>This is the guard the tree has been missing twice over: {@code AccessLedger.compactIfLarge()}
 * had zero callers and its file reached 3.1 GB, and {@code RunLogGc}'s 7-day TTL sat behind a CLI
 * verb and had not fired in 18 days. Both would have failed here.
 *
 * <p>{@link #every_tier_has_a_fixture} is the part that keeps it honest: adding a constant to
 * {@link CacheTree} without adding a fixture below fails the suite, so a new tier cannot be
 * introduced unbounded — nor bounded-but-unreachable, since every fixture asserts through {@link
 * CacheRetention#sweep}, the one entry point the idle boundary calls.
 *
 * <p>Every fixture path is built from {@link CacheTree#entry()} rather than typed, and {@link
 * #a_tier_is_reclaimed_by_its_own_bound_not_by_the_residue_sweep} is what makes that matter: a
 * fixture that spelled its own directory name would follow a rename of the constant while the
 * producer did not, land in the residue sweep, and still see its victim deleted — passing having
 * proven nothing about the bound it exists to prove.
 */
class CacheRetentionCoverageTest {

    /** How to put one tier in violation of its own bound, and what must survive alongside. */
    private interface Fixture {
        /** Seed the violation; return the path that must be gone afterwards. */
        Path seed(Path cacheRoot) throws IOException;
    }

    private static final long OLD = Duration.ofDays(400).toMillis();
    private static final long FRESH = Duration.ofMinutes(1).toMillis(); // inside MIN_AGE_FOR_SWEEP

    private static Map<CacheTree, Fixture> fixtures() {
        Map<CacheTree, Fixture> m = new EnumMap<>(CacheTree.class);
        // Delegated: an action key past ActionCachePrune's 30-day window goes regardless of budget.
        m.put(CacheTree.ACTIONS, root -> actionKey(root, OLD));
        m.put(CacheTree.CACHE_CAS, root -> actionKey(root, OLD));
        m.put(CacheTree.FORMAT_STAMPS, root -> in(root, CacheTree.FORMAT_STAMPS, "ab/cd/stamp", "", OLD));
        m.put(CacheTree.FORMAT_FRESHNESS, root -> in(root, CacheTree.FORMAT_FRESHNESS, "orphan.idx", "x", OLD));
        m.put(CacheTree.HASH_MEMO, root -> overCountCap(root, CacheTree.HASH_MEMO, 32_768));
        m.put(CacheTree.GRAAL_REACHABILITY, root -> subtree(root, CacheTree.GRAAL_REACHABILITY, "0.0.1", OLD));
        m.put(CacheTree.KOTLIN_CP_SNAPSHOTS, root -> overByteBudget(root, CacheTree.KOTLIN_CP_SNAPSHOTS));
        m.put(CacheTree.JSHELL_CP, root -> in(root, CacheTree.JSHELL_CP, "alias.jar", "x", FRESH));
        m.put(CacheTree.BASE_JRE, root -> subtree(root, CacheTree.BASE_JRE, "sha256-dead", OLD));
        m.put(CacheTree.PATH_ARTIFACTS, root -> subtree(root, CacheTree.PATH_ARTIFACTS, "pathhash/fp", OLD));
        m.put(CacheTree.GENERATED, root -> subtree(root, CacheTree.GENERATED, "compile-test@dead", OLD));
        m.put(CacheTree.PROJECTS, root -> subtree(root, CacheTree.PROJECTS, "dead-project", OLD));
        m.put(CacheTree.TOOL_SRC, root -> subtree(root, CacheTree.TOOL_SRC, "deadhash", OLD));
        // Bookkeeping entries seed a file that must SURVIVE; see the dedicated test below.
        m.put(CacheTree.LAST_PRUNED, root -> null);
        m.put(CacheTree.PRUNE_LOCK, root -> null);
        return m;
    }

    @Test
    void every_tier_has_a_fixture() {
        assertThat(fixtures().keySet())
                .as("a new CacheTree must declare how it is bounded AND how that is proven")
                .isEqualTo(EnumSet.allOf(CacheTree.class));
    }

    @Test
    void every_bounded_tier_is_reclaimed_through_the_prune(@TempDir Path root) throws IOException {
        for (Map.Entry<CacheTree, Fixture> e : fixtures().entrySet()) {
            CacheTree tier = e.getKey();
            if (!tier.holdsCachedBytes()) continue;
            Path cacheRoot = Files.createDirectories(root.resolve(tier.name().toLowerCase(Locale.ROOT)));
            Path victim = e.getValue().seed(cacheRoot);

            CacheRetention.sweep(cacheRoot, new Cas(cacheRoot), Set.of(), false);

            assertThat(victim)
                    .as("%s must be reclaimed by the automatic prune", tier)
                    .doesNotExist();
        }
    }

    /**
     * The rename check. A tier's bytes must be taken by the instrument {@link CacheTier} declares
     * for it, never by the residue sweep — and the residue sweep is precisely what picks up a
     * directory whose name has drifted from the table. Renaming {@link CacheTree#FORMAT_STAMPS} to
     * {@code fmt-stamps} while the formatter worker keeps writing {@code format-stamps/} leaves the
     * old tree unbounded and unmeasured, and the only visible symptom without this assertion is a
     * warning nobody reads. Here the tier reports as unrecognised and the suite goes red.
     */
    @Test
    void a_tier_is_reclaimed_by_its_own_bound_not_by_the_residue_sweep(@TempDir Path root) throws IOException {
        for (Map.Entry<CacheTree, Fixture> e : fixtures().entrySet()) {
            CacheTree tier = e.getKey();
            if (!tier.holdsCachedBytes()) continue;
            Path cacheRoot = Files.createDirectories(root.resolve(tier.name().toLowerCase(Locale.ROOT)));
            e.getValue().seed(cacheRoot);

            var report = CacheRetention.sweep(cacheRoot, new Cas(cacheRoot), Set.of(), false);

            assertThat(report.unknownEntries())
                    .as("%s is named by the table, so nothing it writes may read as residue", tier)
                    .isEmpty();
        }
    }

    /**
     * {@code Cas} builds its shard root from the same name against both the cache root and the
     * artifact store's, so it cannot read {@link CacheTree}. This is the seam that keeps the two
     * spellings together: rename the constant and the cache CAS is a tier the prune bounds at a
     * path nothing writes, with the real blobs left to the residue sweep.
     */
    @Test
    void the_cache_cas_tier_is_where_Cas_actually_puts_a_blob(@TempDir Path root) {
        Path blob = new Cas(root).pathFor("0".repeat(64));

        assertThat(blob.startsWith(CacheTree.CACHE_CAS.under(root)))
                .as("Cas put a blob at %s, outside the tier the prune bounds", blob)
                .isTrue();
    }

    @Test
    void every_bookkeeping_entry_says_why_and_is_left_alone(@TempDir Path root) throws IOException {
        for (CacheTree tier : CacheTree.values()) {
            if (tier.holdsCachedBytes()) continue;
            assertThat(tier.bookkeeping())
                    .as("%s is not cache and must say what it is instead", tier)
                    .isNotBlank();
            assertThat(CacheTier.bound(tier).kind())
                    .as("%s is bookkeeping, so the engine must decline to bound it", tier)
                    .isEqualTo(Bound.Kind.UNBOUNDED);
            Path cacheRoot = Files.createDirectories(root.resolve("keep-" + tier.name()));
            Path kept = file(cacheRoot, tier.entry(), "x", OLD);

            CacheRetention.sweep(cacheRoot, new Cas(cacheRoot), Set.of(), false);

            assertThat(kept).as("%s is unbounded and must survive", tier).exists();
        }
    }

    /**
     * Exactly one metadata bundle can ever be read, so the tier keeps that one and nothing else —
     * including the directory an interrupted extract leaves beside it.
     */
    @Test
    void the_reachability_tier_keeps_only_the_bundle_in_use(@TempDir Path root) throws IOException {
        CacheTree tier = CacheTree.GRAAL_REACHABILITY;
        Path live = subtree(root, tier, ReachabilityMetadata.VERSION, OLD);
        Path superseded = subtree(root, tier, "0.0.1", OLD);
        Path leaked = subtree(root, tier, ReachabilityMetadata.VERSION + ".extract-7f3a", OLD);

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(live).as("the version this jk resolves is never old").exists();
        assertThat(superseded).doesNotExist();
        assertThat(leaked).doesNotExist();
    }

    /** {@code path-artifacts} is keyed twice, and the cap belongs to the inner key. */
    @Test
    void a_nested_cap_counts_per_parent_not_across_the_tier(@TempDir Path root) throws IOException {
        CacheTree tier = CacheTree.PATH_ARTIFACTS;
        long inWindow = Duration.ofDays(2).toMillis(); // past grace, short of the 7-day window
        for (String pathHash : List.of("aaa", "bbb")) {
            for (int i = 0; i < 3; i++) {
                subtree(root, tier, pathHash + "/fp-" + i, inWindow + (3 - i) * 60_000L);
            }
        }

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        for (String pathHash : List.of("aaa", "bbb")) {
            assertThat(tier.under(root).resolve(pathHash + "/fp-0"))
                    .as("the oldest fingerprint under %s", pathHash)
                    .doesNotExist();
            assertThat(tier.under(root).resolve(pathHash + "/fp-1")).exists();
            assertThat(tier.under(root).resolve(pathHash + "/fp-2")).exists();
        }
    }

    /** An interrupted base-image extract leaves its tarball beside the trees; nothing else would. */
    @Test
    void a_loose_file_in_a_tier_of_trees_is_residue(@TempDir Path root) throws IOException {
        CacheTree tier = CacheTree.BASE_JRE;
        Path live = subtree(root, tier, "sha256-live", Duration.ofDays(2).toMillis());
        Path leaked = in(root, tier, "sha256-live.tar", "half a base image", OLD);
        Path fresh = in(root, tier, "sha256-other.tar", "still being written", FRESH);

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(leaked).doesNotExist();
        assertThat(fresh).as("another engine may be mid-pull").exists();
        assertThat(live).exists();
    }

    /** The residue rule: anything the table does not name is reclaimed, and reported. */
    @Test
    void unrecognised_entries_are_reclaimed_and_named(@TempDir Path root) throws IOException {
        Path stale = file(root, ".access.log", "44MB of dead ledger", OLD);
        Path residue = tree(root, "repos", OLD);

        var report = CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(stale).doesNotExist();
        assertThat(residue).doesNotExist();
        assertThat(report.unknownEntries()).contains(".access.log", "repos");
    }

    /** A stray that appeared moments ago may be another engine mid-write. */
    @Test
    void a_fresh_unrecognised_entry_is_inside_the_grace_window(@TempDir Path root) throws IOException {
        Path fresh = file(root, "something-brand-new", "x", FRESH);

        var report = CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(fresh).exists();
        assertThat(report.unknownEntries()).isEmpty();
    }

    @Test
    void a_dry_run_deletes_nothing(@TempDir Path root) throws IOException {
        Path stamp = in(root, CacheTree.FORMAT_STAMPS, "ab/cd/stamp", "", OLD);
        Path stale = file(root, ".access.log", "x", OLD);

        var report = CacheRetention.sweep(root, new Cas(root), Set.of(), true);

        assertThat(stamp).exists();
        assertThat(stale).exists();
        assertThat(report.deletedFiles()).isPositive();
    }

    // ---------------------------------------------------------------- fixtures

    private static Path file(Path root, String rel, String body, long ageMillis) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.UTF_8);
        backdate(p, ageMillis);
        return p;
    }

    /** A file at {@code rel} inside {@code tier}, named through the owner rather than by hand. */
    private static Path in(Path root, CacheTree tier, String rel, String body, long ageMillis) throws IOException {
        return file(tier.under(root), rel, body, ageMillis);
    }

    /** A child tree at {@code rel} inside {@code tier}. */
    private static Path subtree(Path root, CacheTree tier, String rel, long ageMillis) throws IOException {
        return tree(tier.under(root), rel, ageMillis);
    }

    private static Path tree(Path root, String rel, long ageMillis) throws IOException {
        Path dir = root.resolve(rel);
        Files.createDirectories(dir);
        Path inner = dir.resolve("content");
        Files.writeString(inner, "x", StandardCharsets.UTF_8);
        backdate(inner, ageMillis);
        backdate(dir, ageMillis);
        return dir;
    }

    /** An action entry whose key record is past the 30-day action window. */
    private static Path actionKey(Path root, long ageMillis) throws IOException {
        Path actions = CacheTree.ACTIONS.under(root);
        Path key = actions.resolve("keys/key-a");
        Files.createDirectories(key.getParent());
        Files.writeString(key, "TASK compile-main@a\nKEY key-a\n", StandardCharsets.UTF_8);
        Path pointer = actions.resolve("tasks/compile-main@a");
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, "key-a", StandardCharsets.UTF_8);
        backdate(key, ageMillis);
        return key;
    }

    /** One entry over the count cap; the oldest is the victim. */
    private static Path overCountCap(Path root, CacheTree tier, int cap) throws IOException {
        Path victim = null;
        for (int i = 0; i <= cap; i++) {
            Path p = in(root, tier, (i % 256) + "/" + i, "x", OLD + (cap - i));
            if (i == 0) victim = p;
        }
        return victim;
    }

    /**
     * Over the reset budget, so the whole tier goes. Sparse: the instrument sums apparent bytes —
     * the same figure it uses in production — so 132 MB of logical size costs no disk here.
     */
    private static Path overByteBudget(Path root, CacheTree tier) throws IOException {
        Path first = null;
        for (int i = 0; i < 33; i++) {
            Path p = tier.under(root).resolve("snap-" + i);
            Files.createDirectories(p.getParent());
            try (var raf = new RandomAccessFile(p.toFile(), "rw")) {
                raf.setLength(4L * 1024 * 1024);
            }
            backdate(p, OLD);
            if (i == 0) first = p;
        }
        return first;
    }

    private static void backdate(Path p, long ageMillis) throws IOException {
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - ageMillis));
    }
}
