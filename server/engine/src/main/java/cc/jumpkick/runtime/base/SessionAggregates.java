// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * table is a small LRU.
 *
 * <p>Only a checkout with a {@code jk.toml} is a project session; the engine's own CWD (its
 * state dir) and other directories read the host-wide merge instead.
 */
public final class SessionAggregates {

    /** Concurrent checkouts a resident engine typically serves; beyond it the least recent goes. */
    static final int MAX_MEMOS = 8;

    private record Key(Path builds, @Nullable Path work) {}

    /** One window's aggregates and the views folded from them so far. */
    private record Memo(AggregatedMetrics agg, ConcurrentHashMap<Class<?>, Object> folds) {}

    private static final Map<Key, Memo> MEMOS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Memo> eldest) {
            return size() > MAX_MEMOS;
        }
    };

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
        Object view = memo.folds().computeIfAbsent(key, k -> {
            FOLDS.incrementAndGet();
            return fold.apply(memo.agg());
        });
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
            Memo fresh = new Memo(agg, new ConcurrentHashMap<>());
            MEMOS.put(key, fresh);
            return fresh;
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
