// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import cc.jumpkick.host.CacheTree;
import java.time.Duration;

/**
 * What bounds every {@link CacheTree} entry.
 *
 * <p>The switch below is <strong>exhaustive</strong>, and that is the whole design. Twice now a
 * tier has grown without limit because its bound lived somewhere nobody called — {@code
 * AccessLedger.compactIfLarge()} with zero callers (the file reached 3.1 GB), and {@code RunLogGc}
 * with one caller on a path only a human types (its 7-day TTL had not fired in 18 days). A tier
 * that is merely <em>absent</em> from a bounds table would be a third instance. Absent from {@link
 * CacheTree} means reclaimed by {@link CacheRetention}'s residue sweep; present there and absent
 * here does not compile. Either way the only way to keep a new tier is to give it an instrument or
 * an explicit {@link Bound#unbounded} reason.
 *
 * <p>Clocks are <strong>mtime</strong>. {@code atime} would be a truer last-use signal on the
 * derived tiers, but it is off on many filesystems and jk cannot tell which it got, so the design
 * does not depend on it. Where mtime is not a use clock, the instrument does not rank: it caps by
 * count, or resets the tier wholesale.
 */
public final class CacheTier {

    private CacheTier() {}

    /** The instrument that holds {@code tree} down. Total by construction — see the class javadoc. */
    public static Bound bound(CacheTree tree) {
        return switch (tree) {
            // Action index and cache CAS are one budget and one pass: ActionCachePrune's Policy.
            case ACTIONS, CACHE_CAS -> Bound.delegated();

            // Zero data bytes and `Blocks: 0` — the cost is inodes and dirents, invisible to both
            // `du` and `stat`, so a byte budget could not see this tier at all.
            case FORMAT_STAMPS -> Bound.files(Duration.ofDays(7), Bound.countCap(FormatStamps.maxFiles()));

            // `save()` rewrites the live index on every `jk format`, so mtime is an exact use clock
            // here and the window is the whole policy: no count cap, because the population is one
            // per configuration a workspace actually formats with, and a cap on that would evict an
            // index still in weekly use to make room for one used today.
            case FORMAT_FRESHNESS -> Bound.files(Duration.ofDays(7), Bound.none());

            // A single file, which FileHashMemo keeps capped by entry count in memory and rewrites
            // whole. Retention cannot rank inside it and does not need to; the budget is the
            // backstop for a store that somehow outgrows what the in-memory cap allows, and
            // resetting it costs one rebuild of something wholly derived.
            //
            // No window either: mtime here is a churn clock, rewritten on every flush, so age says
            // nothing about whether the next build wants what is inside.
            case HASH_MEMO -> Bound.files(null, Bound.resetOverBytes(32L * 1024 * 1024));

            // Written once and never rewritten on reuse, so mtime cannot rank them.
            case KOTLIN_CP_SNAPSHOTS -> Bound.files(null, Bound.resetOverBytes(128L * 1024 * 1024));

            // Near-zero real bytes; recreated on demand.
            case JSHELL_CP -> Bound.files(null, Bound.resetAlways());

            // 50–200 MB each — the only tier where a count cap is a byte bound. The window reads
            // the `.extracted` marker, which `BaseJre.javaBinary` touches on use; without that a
            // digest-pinned tree, which is never re-extracted and never rewritten, would age out of
            // a workspace that builds an image with it every day.
            case BASE_JRE -> Bound.subtrees(Duration.ofDays(30), Bound.countCap(2));

            // Every source edit mints one, so the cap belongs to the inner key, not the tier.
            case PATH_ARTIFACTS -> Bound.nestedSubtrees(Duration.ofDays(7), Bound.countCap(2));

            // A dead task id freezes: nothing rewrites its tree, so the window is the whole policy.
            case GENERATED -> Bound.subtrees(Duration.ofDays(7), Bound.none());

            // `storeDirty` rewrites the memo every build, so mtime is exact here.
            case PROJECTS -> Bound.subtrees(Duration.ofDays(90), Bound.countCap(512));

            // Losing one costs a re-download.
            case TOOL_SRC -> Bound.subtrees(Duration.ofDays(30), Bound.countCap(256));

            // Bookkeeping, not cache: the reason is CacheTree's, because it is the same fact that
            // keeps `jk cache nuke` off these two entries.
            case LAST_PRUNED, PRUNE_LOCK -> Bound.unbounded(tree.bookkeeping());
        };
    }
}
