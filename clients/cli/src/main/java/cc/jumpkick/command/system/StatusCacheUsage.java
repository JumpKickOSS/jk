// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The cache footprint {@code jk status} shows, walked at most once per {@link #FRESH_MILLIS}.
 *
 * <p>The walk is the whole cache root, and on a working cache it is most of what a status costs: 24k
 * files read about 950 ms where every other part of {@code jk status} is ~10 ms. A footprint a few
 * minutes old answers "how big is my cache" as well as a live one, so the last walk is kept in
 * {@link CacheTree#STATUS_USAGE} and reused until it is {@link #FRESH_MILLIS} old, a prune has run
 * since, or the cache CAS has lost its shards. The memo is bookkeeping, so a nuke leaves it; the
 * CAS directory's time is what retires it then. Builds in between move the counts a little and the
 * window by at most ten minutes.
 */
final class StatusCacheUsage {

    static final long FRESH_MILLIS = 10 * 60_000L;

    private StatusCacheUsage() {}

    /** The footprint of {@code cacheRoot}: remembered when fresh, else walked and remembered. */
    static CacheCommand.SectionStats of(Path cacheRoot, Clock clock) throws IOException {
        Path memo = CacheTree.STATUS_USAGE.under(cacheRoot);
        String dirs = dirTimes(cacheRoot);
        CacheCommand.SectionStats kept = read(memo, CacheTree.LAST_PRUNED.under(cacheRoot), clock.millis(), dirs);
        if (kept != null) return kept;
        CacheCommand.SectionStats walked = CacheCommand.sectionStats(cacheRoot);
        try {
            AtomicWrites.replace(
                    memo,
                    clock.millis() + " " + walked.root().files() + " "
                            + walked.root().bytes() + " " + walked.cacheCas().files() + " "
                            + walked.actionKeys().files() + " " + dirs + "\n");
        } catch (IOException e) {
            // The memo saves the next status a walk; this one already has its numbers.
        }
        return walked;
    }

    /**
     * The cache CAS directory's modification time: its 256 shards come and go only when the tier
     * is emptied, as a nuke does. Not the root's, which the memo's own rename moves, and not the
     * action keys', which every build that caches an action moves.
     */
    static String dirTimes(Path cacheRoot) {
        try {
            return Long.toString(Files.getLastModifiedTime(CacheTree.CACHE_CAS.under(cacheRoot))
                    .toMillis());
        } catch (IOException e) {
            return "-";
        }
    }

    /** The remembered footprint when it is younger than {@link #FRESH_MILLIS} and no prune has run since. */
    static CacheCommand.@Nullable SectionStats read(Path memo, Path lastPruned, long now, String dirs) {
        try {
            String[] f = Files.readString(memo, StandardCharsets.UTF_8).trim().split(" ", 6);
            if (f.length != 6 || !f[5].equals(dirs)) return null;
            long at = Long.parseLong(f[0]);
            if (now - at > FRESH_MILLIS || now < at) return null;
            if (Files.exists(lastPruned)
                    && Files.getLastModifiedTime(lastPruned).toMillis() >= at) return null;
            return new CacheCommand.SectionStats(
                    new CacheCommand.Stats(Long.parseLong(f[3]), 0),
                    new CacheCommand.Stats(Long.parseLong(f[4]), 0),
                    new CacheCommand.Stats(Long.parseLong(f[1]), Long.parseLong(f[2])));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
