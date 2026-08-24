// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
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
 * Every {@link CacheTier} is bounded on a path that actually runs, or says why it is not.
 *
 * <p>This is the guard the tree has been missing twice over: {@code AccessLedger.compactIfLarge()}
 * had zero callers and its file reached 3.1 GB, and {@code RunLogGc}'s 7-day TTL sat behind a CLI
 * verb and had not fired in 18 days. Both would have failed here.
 *
 * <p>{@link #every_tier_has_a_fixture} is the part that keeps it honest: adding a constant to
 * {@link CacheTier} without adding a fixture below fails the suite, so a new tier cannot be
 * introduced unbounded — nor bounded-but-unreachable, since every fixture asserts through {@link
 * CacheRetention#sweep}, the one entry point the idle boundary calls.
 */
class CacheRetentionCoverageTest {

    /** How to put one tier in violation of its own bound, and what must survive alongside. */
    private interface Fixture {
        /** Seed the violation; return the path that must be gone afterwards. */
        Path seed(Path cacheRoot) throws IOException;
    }

    private static final long OLD = Duration.ofDays(400).toMillis();
    private static final long FRESH = Duration.ofMinutes(1).toMillis(); // inside MIN_AGE_FOR_SWEEP

    private static Map<CacheTier, Fixture> fixtures() {
        Map<CacheTier, Fixture> m = new EnumMap<>(CacheTier.class);
        // Delegated: an action key past ActionCachePrune's 30-day window goes regardless of budget.
        m.put(CacheTier.ACTIONS, root -> actionKey(root, OLD));
        m.put(CacheTier.CACHE_CAS, root -> actionKey(root, OLD));
        m.put(CacheTier.FORMAT_STAMPS, root -> file(root, "format-stamps/ab/cd/stamp", "", OLD));
        m.put(CacheTier.FORMAT_FRESHNESS, root -> file(root, "format-freshness/orphan.idx", "x", OLD));
        m.put(CacheTier.HASH_MEMO, root -> overStoreBudget(root, "hash-memo", "memo.v1", 32L * 1024 * 1024));
        m.put(CacheTier.GRAAL_REACHABILITY, root -> tree(root, "graal-reachability/0.0.1", OLD));
        m.put(CacheTier.KOTLIN_CP_SNAPSHOTS, root -> overByteBudget(root, "kotlin-cp-snapshots"));
        m.put(CacheTier.JSHELL_CP, root -> file(root, "jshell-cp/alias.jar", "x", FRESH));
        m.put(CacheTier.BASE_JRE, root -> tree(root, "base-jre/sha256-dead", OLD));
        m.put(CacheTier.PATH_ARTIFACTS, root -> tree(root, "path-artifacts/pathhash/fingerprint", OLD));
        m.put(CacheTier.GENERATED, root -> tree(root, "generated/compile-test@dead", OLD));
        m.put(CacheTier.PROJECTS, root -> tree(root, "projects/dead-project", OLD));
        m.put(CacheTier.TOOL_SRC, root -> tree(root, "tool-src/deadhash", OLD));
        // Unbounded tiers seed a file that must SURVIVE; see the dedicated test below.
        m.put(CacheTier.LAST_PRUNED, root -> null);
        m.put(CacheTier.PRUNE_LOCK, root -> null);
        return m;
    }

    @Test
    void every_tier_has_a_fixture() {
        assertThat(fixtures().keySet())
                .as("a new CacheTier must declare how it is bounded AND how that is proven")
                .isEqualTo(EnumSet.allOf(CacheTier.class));
    }

    @Test
    void every_bounded_tier_is_reclaimed_through_the_prune(@TempDir Path root) throws IOException {
        for (Map.Entry<CacheTier, Fixture> e : fixtures().entrySet()) {
            CacheTier tier = e.getKey();
            if (tier.bound().kind() == Bound.Kind.UNBOUNDED) continue;
            Path cacheRoot = Files.createDirectories(root.resolve(tier.name().toLowerCase(Locale.ROOT)));
            Path victim = e.getValue().seed(cacheRoot);

            CacheRetention.sweep(cacheRoot, new Cas(cacheRoot), Set.of(), false);

            assertThat(victim)
                    .as("%s must be reclaimed by the automatic prune", tier)
                    .doesNotExist();
        }
    }

    @Test
    void every_unbounded_tier_says_why_and_is_left_alone(@TempDir Path root) throws IOException {
        for (CacheTier tier : CacheTier.values()) {
            if (tier.bound().kind() != Bound.Kind.UNBOUNDED) continue;
            assertThat(tier.bound().reason())
                    .as("%s is unbounded and must say why", tier)
                    .isNotBlank();
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
        Path live = tree(root, "graal-reachability/" + ReachabilityMetadata.VERSION, OLD);
        Path superseded = tree(root, "graal-reachability/0.0.1", OLD);
        Path leaked = tree(root, "graal-reachability/" + ReachabilityMetadata.VERSION + ".extract-7f3a", OLD);

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        assertThat(live).as("the version this jk resolves is never old").exists();
        assertThat(superseded).doesNotExist();
        assertThat(leaked).doesNotExist();
    }

    /** {@code path-artifacts} is keyed twice, and the cap belongs to the inner key. */
    @Test
    void a_nested_cap_counts_per_parent_not_across_the_tier(@TempDir Path root) throws IOException {
        long inWindow = Duration.ofDays(2).toMillis(); // past grace, short of the 7-day window
        for (String pathHash : List.of("aaa", "bbb")) {
            for (int i = 0; i < 3; i++) {
                tree(root, "path-artifacts/" + pathHash + "/fp-" + i, inWindow + (3 - i) * 60_000L);
            }
        }

        CacheRetention.sweep(root, new Cas(root), Set.of(), false);

        for (String pathHash : List.of("aaa", "bbb")) {
            assertThat(root.resolve("path-artifacts/" + pathHash + "/fp-0"))
                    .as("the oldest fingerprint under %s", pathHash)
                    .doesNotExist();
            assertThat(root.resolve("path-artifacts/" + pathHash + "/fp-1")).exists();
            assertThat(root.resolve("path-artifacts/" + pathHash + "/fp-2")).exists();
        }
    }

    /** An interrupted base-image extract leaves its tarball beside the trees; nothing else would. */
    @Test
    void a_loose_file_in_a_tier_of_trees_is_residue(@TempDir Path root) throws IOException {
        Path live = tree(root, "base-jre/sha256-live", Duration.ofDays(2).toMillis());
        Path leaked = file(root, "base-jre/sha256-live.tar", "half a base image", OLD);
        Path fresh = file(root, "base-jre/sha256-other.tar", "still being written", FRESH);

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
        Path stamp = file(root, "format-stamps/ab/cd/stamp", "", OLD);
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
        Path key = root.resolve("actions/keys/key-a");
        Files.createDirectories(key.getParent());
        Files.writeString(key, "TASK compile-main@a\nKEY key-a\n", StandardCharsets.UTF_8);
        Path pointer = root.resolve("actions/tasks/compile-main@a");
        Files.createDirectories(pointer.getParent());
        Files.writeString(pointer, "key-a", StandardCharsets.UTF_8);
        backdate(key, ageMillis);
        return key;
    }

    /**
     * A single-file store past its byte budget, so the whole tier resets. Sparse for the same
     * reason as {@link #overByteBudget}: the instrument sums apparent bytes.
     */
    private static Path overStoreBudget(Path root, String tier, String name, long budgetBytes) throws IOException {
        Path p = root.resolve(tier).resolve(name);
        Files.createDirectories(p.getParent());
        try (var raf = new RandomAccessFile(p.toFile(), "rw")) {
            raf.setLength(budgetBytes + 1);
        }
        backdate(p, OLD);
        return p;
    }

    /**
     * Over the reset budget, so the whole tier goes. Sparse: the instrument sums apparent bytes —
     * the same figure it uses in production — so 132 MB of logical size costs no disk here.
     */
    private static Path overByteBudget(Path root, String tier) throws IOException {
        Path first = null;
        for (int i = 0; i < 33; i++) {
            Path p = root.resolve(tier).resolve("snap-" + i);
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
