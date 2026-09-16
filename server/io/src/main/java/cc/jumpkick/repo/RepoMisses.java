// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.time.Clock;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide memo of the URLs a repository answered "not found": version catalogs, POMs and
 * artifacts alike. On a multi-repository project every path is asked of each repository ahead of
 * the one that holds it, so the misses outnumber the hits, and a re-lock in the same engine would
 * pay each of them again — the positive memos answer only for what was found. A known miss is
 * answered without a request until it expires.
 *
 * <p>An entry lives {@link #TTL}, the window the {@code maven-metadata.xml} cache already trusts a
 * catalog for: a coordinate absent now may be published later, and the lock is already prepared to
 * see it that late. A forced session ({@code --force}, or a revalidating lock) bypasses the memo
 * and refreshes it, and {@link RepoGroup#clearProcessFetchCache()} drops it with the positive fetch
 * memos. Capped so a long-lived engine cannot retain an unbounded set of misses.
 */
final class RepoMisses {

    /** How long a miss is trusted; the same window as the metadata cache's. */
    static final Duration TTL = MavenMetadataCache.DEFAULT_TTL;

    private static final int MAX = 65_536;

    /** URL → the monotonic nanosecond reading at which the miss stops being trusted. */
    private static final ConcurrentHashMap<String, Long> MISSES = new ConcurrentHashMap<>();

    private RepoMisses() {}

    /** True when {@code uri} answered not-found within {@link #TTL}. */
    static boolean known(URI uri) {
        String key = uri.toString();
        Long expiresAt = MISSES.get(key);
        if (expiresAt == null) return false;
        if (Clock.SYSTEM.nanos() - expiresAt >= 0) {
            MISSES.remove(key, expiresAt);
            return false;
        }
        return true;
    }

    /** Remember that {@code uri} answered not-found. */
    static void record(URI uri) {
        String key = uri.toString();
        if (MISSES.size() >= MAX && !MISSES.containsKey(key)) return;
        MISSES.put(key, Clock.SYSTEM.nanos() + TTL.toNanos());
    }

    /** Forget a miss: {@code uri} answered after all (a forced session asked past the memo). */
    static void forget(URI uri) {
        MISSES.remove(uri.toString());
    }

    /** Drop every miss. */
    static void clear() {
        MISSES.clear();
    }

    /** How many misses are held; for tests. */
    static int size() {
        return MISSES.size();
    }
}
