// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The harvested aggregates a session prices its steps from, parsed once per (builds root, working
 * dir) and held until a ledger file changes.
 *
 * <p>Every priced step reads these. A parse is a scan of the project's ledger plus the host's;
 * a freshness check is one stat per file ({@link AggregatedMetrics#fresh()}), so a build reads
 * its ledger once and a harvest rewrite between builds is picked up on the next read. Memos are
 * keyed by working dir so concurrent builds of different checkouts do not evict each other; the
 * table is an LRU bounded by {@link #MAX_BYTES}.
 *
 * <p>Only a checkout with a {@code jk.toml} is a project session; the engine's own CWD (its
 * state dir) and other directories read the host-wide merge instead.
 */
public final class SessionAggregates {

    /**
     * Heap the memos hold together. Past it the least recently read go, never the one just read: a
     * job's own ledgers are charged to it by the engine's memory admission while it runs.
     */
    static final long MAX_BYTES = 8L << 20;

    private static volatile long maxBytes = MAX_BYTES;

    private record Key(Path builds, @Nullable Path work) {}

    /**
     * One window's aggregates and the views folded from them so far. Each view is counted at the
     * size of the aggregates it was folded from.
     */
    private record Memo(AggregatedMetrics agg, long aggBytes, ConcurrentHashMap<Class<?>, Object> folds) {
        long bytes() {
            return aggBytes * (1L + folds.size());
        }
    }

    /** Access-ordered: the eldest entry is the least recently read. */
    private static final Map<Key, Memo> MEMOS = new LinkedHashMap<>(16, 0.75f, true);

    private static final AtomicLong FOLDS = new AtomicLong();

    private SessionAggregates() {}

    /** The session's aggregates: the working dir's project ledger when it is a checkout, else every project's. */
    public static AggregatedMetrics current() {
        return memo().agg();
    }

    /**
     * A view folded from the session aggregates, computed once per window under {@code key} and
     * shared by every caller in it. Views live beside the aggregates they were folded from, so a
     * ledger change refolds each view on its first use and nothing serves a view of aggregates
     * that are gone.
     */
    public static <T> T view(Class<T> key, Function<AggregatedMetrics, T> fold) {
        Memo memo = memo();
        boolean[] folded = {false};
        Object view = memo.folds().computeIfAbsent(key, k -> {
            FOLDS.incrementAndGet();
            folded[0] = true;
            return fold.apply(memo.agg());
        });
        if (folded[0]) trim(memo);
        return key.cast(view);
    }

    /** Drop every memo: the idle boundary, and tests that repoint the state dir between cases. */
    public static void clear() {
        synchronized (MEMOS) {
            MEMOS.clear();
        }
    }

    /** Test seam: views folded since process start (or the last reset). */
    public static long foldCount() {
        return FOLDS.get();
    }

    /** Test seam. */
    public static void resetFoldCount() {
        FOLDS.set(0);
    }

    /** Test seam: the heap the memos are estimated to hold. */
    static long retainedBytes() {
        synchronized (MEMOS) {
            long total = 0;
            for (Memo m : MEMOS.values()) total += m.bytes();
            return total;
        }
    }

    /** Test seam: the bound the table trims to; {@link #MAX_BYTES} when {@code bytes} is negative. */
    static void maxBytesForTest(long bytes) {
        maxBytes = bytes < 0 ? MAX_BYTES : bytes;
    }

    private static Memo memo() {
        Path builds = JkDirs.builds();
        Path work = sessionWorkingDir();
        Path project = work != null && ManifestPaths.describesProject(work) ? work : null;
        Key key = new Key(builds, project);
        synchronized (MEMOS) {
            Memo memo = MEMOS.get(key);
            if (memo != null && memo.agg().fresh()) return memo;
            AggregatedMetrics agg =
                    project != null ? AggregatedMetrics.load(builds, null, project) : AggregatedMetrics.loadAll(builds);
            Memo fresh = new Memo(agg, agg.retainedBytes(), new ConcurrentHashMap<>());
            MEMOS.put(key, fresh);
            trim(fresh);
            return fresh;
        }
    }

    /** Drop the least recently read memos until the table is within its bound, keeping {@code keep}. */
    private static void trim(Memo keep) {
        synchronized (MEMOS) {
            long total = 0;
            for (Memo m : MEMOS.values()) total += m.bytes();
            Iterator<Memo> eldestFirst = MEMOS.values().iterator();
            while (total > maxBytes && eldestFirst.hasNext()) {
                Memo m = eldestFirst.next();
                if (m == keep) continue;
                total -= m.bytes();
                eldestFirst.remove();
            }
        }
    }

    private static @Nullable Path sessionWorkingDir() {
        try {
            Path w = SessionContext.current().workingDir();
            return w != null && Files.isDirectory(w) ? w : null;
        } catch (RuntimeException e) {
            Log.debug("sessionWorkingDir: no session / bad path", e);
            return null;
        }
    }
}
