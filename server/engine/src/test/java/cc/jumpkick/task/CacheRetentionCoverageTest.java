// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
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
        m.put(CacheTier.HASH_MEMO, root -> overCountCap(root, "hash-memo", 32_768));
        m.put(CacheTier.GRAAL_REACHABILITY, root -> tree(root, "graal-reachability/1.0.0", OLD));
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

    /** One entry over the count cap; the oldest is the victim. */
    private static Path overCountCap(Path root, String tier, int cap) throws IOException {
        Path victim = null;
        for (int i = 0; i <= cap; i++) {
            Path p = file(root, tier + "/" + (i % 256) + "/" + i, "x", OLD + (cap - i));
            if (i == 0) victim = p;
        }
        return victim;
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
