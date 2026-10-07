// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.RepositorySpec;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide memo of the URLs a repository answered "not found". On a multi-repository project
 * every path is asked of each repository ahead of the one that holds it, so the misses outnumber
 * the hits, and a re-lock in the same engine would pay each of them again — the positive memos
 * answer only for what was found. A known miss is answered without a request until it expires.
 *
 * <p>The key is the URL the request opened, not the repository's logical origin: a repository a
 * settings.xml {@code <mirror>} starts routing is asked at the mirror's URL, which no miss recorded
 * at the origin answers for, and a mirror removed leaves the origin asked afresh.
 *
 * <p>Two memos, two lifetimes. {@link #FETCHES} holds POMs and artifacts for the window the
 * {@code maven-metadata.xml} cache trusts a catalog: a published GAV is immutable, and the lock is
 * already prepared to see a new one that late. {@link #CATALOGS} holds version catalogs for an
 * hour — long enough that an import's probe rounds and the lock after them ask each repository
 * that lacks an artifact once, short enough that a coordinate's first release is seen the same
 * afternoon. {@code jk outdated} and {@code jk update} revalidate, so they see it at once.
 *
 * <p>A forced session ({@code --force}, or a revalidating lock) bypasses both and refreshes them,
 * and {@link RepoGroup#clearProcessFetchCache()} drops them with the positive fetch memos. Each is
 * capped so a long-lived engine cannot retain an unbounded set of misses.
 *
 * <p>A loopback repository is never memoized: its URL names whatever process holds the port right
 * now — a stub, a proxy under development — and the memo exists to save remote round trips, which
 * a loopback request is not. Nor is a {@code file://} repository: it is a directory on this disk —
 * a workspace path, a git materialization — that gains an artifact without a session boundary,
 * and a read of it costs no round trip to save. See {@link #memoizes}.
 */
final class RepoMisses {

    /** POMs and artifacts: the same window as the metadata cache's. */
    static final RepoMisses FETCHES = new RepoMisses(MavenMetadataCache.DEFAULT_TTL);

    /** Version catalogs: one hour. */
    static final RepoMisses CATALOGS = new RepoMisses(Duration.ofHours(1));

    private static final int MAX = 65_536;

    private final Duration ttl;

    /** URL → the monotonic nanosecond reading at which the miss stops being trusted. */
    private final ConcurrentHashMap<String, Long> misses = new ConcurrentHashMap<>();

    private RepoMisses(Duration ttl) {
        this.ttl = ttl;
    }

    /** How long a miss is trusted. */
    Duration ttl() {
        return ttl;
    }

    /** True when {@code uri} answered not-found within {@link #ttl}. */
    boolean known(URI uri) {
        String key = uri.toString();
        Long expiresAt = misses.get(key);
        if (expiresAt == null) return false;
        if (Clock.SYSTEM.nanos() - expiresAt >= 0) {
            misses.remove(key, expiresAt);
            return false;
        }
        return true;
    }

    /**
     * True when answers from {@code uri} are remembered: every remote but a loopback one, whose
     * port can belong to a different process the next time it is asked; never a {@code file://}
     * directory, whose contents are whatever is on the disk right now.
     */
    static boolean memoizes(URI uri) {
        return !"file".equalsIgnoreCase(uri.getScheme()) && !RepositorySpec.loopback(uri.getHost());
    }

    /** Remember that {@code uri} answered not-found; a loopback or file repository's miss is not kept. */
    void record(URI uri) {
        if (!memoizes(uri)) return;
        String key = uri.toString();
        if (misses.size() >= MAX && !misses.containsKey(key)) return;
        misses.put(key, Clock.SYSTEM.nanos() + ttl.toNanos());
    }

    /** Forget a miss: {@code uri} answered after all (a forced session asked past the memo). */
    void forget(URI uri) {
        misses.remove(uri.toString());
    }

    /** Drop every miss in both memos. */
    static void clear() {
        FETCHES.misses.clear();
        CATALOGS.misses.clear();
    }

    /** How many misses are held; for tests. */
    int size() {
        return misses.size();
    }
}
