// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.host.Log;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Host-wide atomized lock timings for ETA (survives {@code jk clean}).
 *
 * <p>Every successful lock ({@code jk lock}, {@code jk update}, an auto-freshen) folds
 * three rates so estimates scale with graph size:
 *
 * <ul>
 *   <li>{@code graph-per-package-ms} — PubGrub + metadata/universe work per decided package
 *   <li>{@code materialize-per-package-ms} — jar/CAS work per package (warm hits dominate the mean)
 *   <li>{@code overhead-ms} — fixed setup (BOM load, feature expand, lockfile assemble) not scaled
 *       by package count
 * </ul>
 *
 * <p>Remote CAS-miss jar walls still train {@link FetchTimings} separately; materialize-per-package
 * captures the <em>wall</em> cost of processing each package (hits + misses blended by how often
 * this host re-locks warm).
 *
 * <p>Samples use the same trimmed-mean policy as {@link FetchTimings} (drop top/bottom 10% when
 * n ≥ 10).
 */
public final class LockTimings {

    static final int MAX_SAMPLES = 80;

    /** Cold defaults when the host has never locked successfully (ms). */
    public static final long COLD_GRAPH_PER_PACKAGE_MS = 25;

    public static final long COLD_MATERIALIZE_PER_PACKAGE_MS = 8;
    public static final long COLD_OVERHEAD_MS = 400;

    /**
     * Declared-root expansion when no prior lock package count is known (matches LockOrchestrator's
     * rough {@code declared * 12} graph estimate order of magnitude, kept conservative).
     */
    public static final int COLD_PACKAGES_PER_ROOT = 10;

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static volatile @Nullable Snapshot memo;

    private LockTimings() {}

    /** The host's store, whose {@link HostStore#LOCK} section these are. */
    public static Path defaultFile() {
        return HostStore.defaultFile();
    }

    /**
     * One successful lock observation. Non-positive durations / counts are ignored for that
     * component. Best-effort — never throws into the lock path.
     *
     * @param graphMs wall ms of the graph phase (all scopes)
     * @param graphPackages packages decided during graph (unique display modules)
     * @param materializeMs wall ms of jar materialize phase
     * @param materializePackages packages materialised (usually same as graph packages)
     * @param totalMs whole lock wall (optional; used to derive residual overhead)
     */
    public static void record(
            long graphMs, int graphPackages, long materializeMs, int materializePackages, long totalMs) {
        if (graphMs <= 0 && materializeMs <= 0 && totalMs <= 0) return;
        LOCK.lock();
        try {
            AtomicReference<@Nullable Snapshot> kept = new AtomicReference<>();
            HostStore.update(defaultFile(), HostStore.LOCK, lines -> {
                Snapshot cur = parse(lines);
                List<Long> graph = new ArrayList<>(cur.graphPerPackageMs);
                List<Long> mat = new ArrayList<>(cur.materializePerPackageMs);
                List<Long> over = new ArrayList<>(cur.overheadMs);
                if (graphMs > 0 && graphPackages > 0) push(graph, Math.max(1, graphMs / graphPackages));
                if (materializeMs > 0 && materializePackages > 0)
                    push(mat, Math.max(1, materializeMs / materializePackages));
                long residual = totalMs - Math.max(0, graphMs) - Math.max(0, materializeMs);
                if (residual > 0) {
                    push(over, residual);
                } else if (totalMs > 0 && graphMs <= 0 && materializeMs <= 0) {
                    // Only whole-wall known — treat as overhead (coarse).
                    push(over, totalMs);
                }
                Snapshot next = new Snapshot(List.copyOf(graph), List.copyOf(mat), List.copyOf(over));
                kept.set(next);
                return List.of(
                        line(GRAPH, next.graphPerPackageMs),
                        line(MATERIALIZE, next.materializePerPackageMs),
                        line(OVERHEAD, next.overheadMs));
            });
            Snapshot next = kept.get();
            if (next != null) memo = next;
        } catch (RuntimeException e) {
            // advisory
            Log.debug("record: advisory", e);
        } finally {
            LOCK.unlock();
        }
    }

    /** Trimmed-mean graph cost per package (ms), or 0 when cold. */
    public static long graphPerPackageMs() {
        return trimmedMean(load().graphPerPackageMs);
    }

    /** Trimmed-mean materialize cost per package (ms), or 0 when cold. */
    public static long materializePerPackageMs() {
        return trimmedMean(load().materializePerPackageMs);
    }

    /** Trimmed-mean fixed lock overhead (ms), or 0 when cold. */
    public static long overheadMs() {
        return trimmedMean(load().overheadMs);
    }

    /**
     * Compose a lock ETA for a project of known size.
     *
     * @param declaredRoots declared main+test+processor roots (0 if unknown)
     * @param knownPackages packages from an existing lock (0 if none)
     */
    public static long estimateMillis(int declaredRoots, int knownPackages) {
        int packages =
                knownPackages > 0 ? knownPackages : Math.max(1, Math.max(declaredRoots, 1) * COLD_PACKAGES_PER_ROOT);
        // Graph usually sees ~final package count; use the same N for both legs.
        long g = graphPerPackageMs();
        if (g <= 0) g = COLD_GRAPH_PER_PACKAGE_MS;
        long m = materializePerPackageMs();
        if (m <= 0) {
            // Prefer warm materialize mean; cold hosts fall back to a thin fraction of remote
            // fetch mean (most re-locks are CAS hits) or a static floor.
            long fetch = FetchTimings.trimmedMeanMs();
            m = fetch > 0 ? Math.max(1, Math.round(fetch * 0.15)) : COLD_MATERIALIZE_PER_PACKAGE_MS;
        }
        long o = overheadMs();
        if (o <= 0) o = COLD_OVERHEAD_MS;
        return Math.max(200, o + packages * g + packages * m);
    }

    /** Pure trimmed mean (package-visible for tests). */
    static long trimmedMean(List<Long> samples) {
        if (samples == null || samples.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        int trim = n >= 10 ? Math.max(1, n / 10) : 0;
        if (trim * 2 >= n) trim = 0;
        long sum = 0;
        int count = 0;
        for (int i = trim; i < n - trim; i++) {
            sum += sorted.get(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    /** Test seam: drop process memo. */
    public static void clearMemo() {
        LOCK.lock();
        try {
            memo = null;
        } finally {
            LOCK.unlock();
        }
    }

    private record Snapshot(List<Long> graphPerPackageMs, List<Long> materializePerPackageMs, List<Long> overheadMs) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of());
    }

    private static Snapshot load() {
        Snapshot m = memo;
        if (m != null) return m;
        LOCK.lock();
        try {
            Snapshot loaded = memo;
            if (loaded != null) return loaded;
            loaded = loadUnlocked();
            memo = loaded;
            return loaded;
        } finally {
            LOCK.unlock();
        }
    }

    private static void push(List<Long> ring, long ms) {
        if (ms <= 0) return;
        ring.add(ms);
        while (ring.size() > MAX_SAMPLES) ring.remove(0);
    }

    private static final String GRAPH = "graph-per-package-ms";
    private static final String MATERIALIZE = "materialize-per-package-ms";
    private static final String OVERHEAD = "overhead-ms";

    private static Snapshot loadUnlocked() {
        return parse(HostStore.section(defaultFile(), HostStore.LOCK));
    }

    /** {@code name ms ms ...} lines. */
    private static Snapshot parse(List<String> lines) {
        List<Long> graph = new ArrayList<>();
        List<Long> mat = new ArrayList<>();
        List<Long> over = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.strip().split("\\s+");
            @Nullable
            List<Long> into =
                    switch (f[0]) {
                        case GRAPH -> graph;
                        case MATERIALIZE -> mat;
                        case OVERHEAD -> over;
                        default -> null;
                    };
            if (into == null) continue;
            for (int i = 1; i < f.length; i++) {
                try {
                    long v = Long.parseLong(f[i]);
                    if (v > 0) into.add(v);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return new Snapshot(List.copyOf(graph), List.copyOf(mat), List.copyOf(over));
    }

    private static String line(String key, List<Long> samples) {
        StringBuilder sb = new StringBuilder(key);
        for (long v : samples) sb.append(' ').append(v);
        return sb.toString();
    }
}
