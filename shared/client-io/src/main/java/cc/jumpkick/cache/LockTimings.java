// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    public static Path defaultFile() {
        return JkDirs.builds().resolve("lock-timings.toml");
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
            Snapshot cur = memo != null ? memo : loadUnlocked();
            List<Long> graph = new ArrayList<>(cur.graphPerPackageMs);
            List<Long> mat = new ArrayList<>(cur.materializePerPackageMs);
            List<Long> over = new ArrayList<>(cur.overheadMs);

            if (graphMs > 0 && graphPackages > 0) {
                long per = Math.max(1, graphMs / graphPackages);
                push(graph, per);
            }
            if (materializeMs > 0 && materializePackages > 0) {
                long per = Math.max(1, materializeMs / materializePackages);
                push(mat, per);
            }
            long residual = totalMs - Math.max(0, graphMs) - Math.max(0, materializeMs);
            if (residual > 0) {
                push(over, residual);
            } else if (totalMs > 0 && graphMs <= 0 && materializeMs <= 0) {
                // Only whole-wall known — treat as overhead (coarse).
                push(over, totalMs);
            }

            Snapshot next = new Snapshot(List.copyOf(graph), List.copyOf(mat), List.copyOf(over));
            writeUnlocked(next);
            memo = next;
        } catch (IOException | RuntimeException e) {
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

    private static Snapshot loadUnlocked() {
        Path file = defaultFile();
        if (!Files.isRegularFile(file)) return Snapshot.EMPTY;
        try {
            List<Long> graph = new ArrayList<>();
            List<Long> mat = new ArrayList<>();
            List<Long> over = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) continue;
                if (s.startsWith("graph-per-package-ms")) parseArray(s, graph);
                else if (s.startsWith("materialize-per-package-ms")) parseArray(s, mat);
                else if (s.startsWith("overhead-ms")) parseArray(s, over);
            }
            return new Snapshot(List.copyOf(graph), List.copyOf(mat), List.copyOf(over));
        } catch (IOException e) {
            return Snapshot.EMPTY;
        }
    }

    private static void parseArray(String line, List<Long> out) {
        int lb = line.indexOf('[');
        int rb = line.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return;
        for (String part : line.substring(lb + 1, rb).split(",")) {
            String t = part.strip();
            if (t.isEmpty()) continue;
            try {
                long v = Long.parseLong(t);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
    }

    private static void writeUnlocked(Snapshot s) throws IOException {
        Path file = defaultFile();
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# jk lock phase timings (ms) — successful locks only\n");
        sb.append("# Atomized host priors: scale by package count for project-specific ETAs.\n");
        sb.append("schema = 1\n");
        appendArray(sb, "graph-per-package-ms", s.graphPerPackageMs);
        appendArray(sb, "materialize-per-package-ms", s.materializePerPackageMs);
        appendArray(sb, "overhead-ms", s.overheadMs);
        AtomicWrites.replace(file, sb.toString());
    }

    private static void appendArray(StringBuilder sb, String key, List<Long> samples) {
        sb.append(key).append(" = [");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(samples.get(i));
        }
        sb.append("]\n");
    }
}
