// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.host.Log;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Learned heaps of jk-planned workers, one file per project under {@code <state>/worker-heaps/}.
 * A key keeps the last five peaks, newest first, and the next {@code -Xmx} is {@link #size} of
 * their maximum. Deleting the project's file forgets them. A user-pinned heap is never written.
 */
public final class LearnedHeaps {

    /** Smallest planned heap a learned size will ask for. */
    public static final long FLOOR_BYTES = 128L << 20;

    /** Learned sizes round up to a multiple of this. */
    public static final long STEP_BYTES = 64L << 20;

    /** A key keeps this many peaks, newest first. {@link #size} uses their maximum. */
    static final int WINDOW = 5;

    private static final LearnedHeaps ENGINE =
            new LearnedHeaps(() -> JkDirs.state().resolve("worker-heaps"), null, true);

    private final Supplier<Path> stateDir;
    private final @Nullable Long forcedBytes;
    private final boolean projectIds;
    private final ConcurrentHashMap<Path, Object> locks = new ConcurrentHashMap<>();

    private LearnedHeaps(Supplier<Path> stateDir, @Nullable Long forcedBytes, boolean projectIds) {
        this.stateDir = stateDir;
        this.forcedBytes = forcedBytes;
        this.projectIds = projectIds;
    }

    /** The store this process records into. Its directory follows {@link JkDirs#state()}. */
    public static LearnedHeaps engine() {
        return ENGINE;
    }

    /** A store filing one file per project under {@code stateDir}. */
    public LearnedHeaps(Path stateDir) {
        this(stateDir, null);
    }

    /**
     * As {@link #LearnedHeaps(Path)}. {@code forcedBytes}, when set, is what {@link #choose} returns
     * instead of a learned or estimated heap.
     */
    public LearnedHeaps(Path stateDir, @Nullable Long forcedBytes) {
        this(() -> Objects.requireNonNull(stateDir, "stateDir"), forcedBytes, false);
    }

    /**
     * -Xmx is the learned peak × 1.3, rounded up to 64 MiB, at least 128 MiB, and no larger than the
     * budget can lease.
     */
    public static long size(long peakBytes, long budgetBytes) {
        long scaled = (Math.max(0, peakBytes) * 13L + 9L) / 10L;
        long rounded = ((scaled + STEP_BYTES - 1) / STEP_BYTES) * STEP_BYTES;
        long sized = Math.max(FLOOR_BYTES, rounded);
        if (budgetBytes <= 0) return sized;
        long fit = WorkerLeases.clampXmx(sized, budgetBytes);
        return fit > 0 ? fit : sized;
    }

    /**
     * The heap a planned worker should start with: the sizing override when one was given, else
     * {@link #size} of the stored maximum, else {@code fallback}.
     */
    public long choose(Path project, String module, String kind, int jdk, long fallback) {
        if (forcedBytes != null) return fit(forcedBytes);
        long seen = peak(project, module, kind, jdk);
        if (seen <= 0) return fallback;
        return size(seen, budget());
    }

    /** Largest stored peak for {@code key}, or {@code 0} when this project has not seen that worker. */
    public long peak(HeapScope.Key key) {
        if (key == null) return 0L;
        return peak(key.project(), key.module(), key.kind(), key.jdk());
    }

    /** Largest stored peak, or {@code 0} when there is no record. */
    public long peak(Path project, String module, String kind, int jdk) {
        if (project == null || module == null || module.isBlank()) return 0L;
        Path file = file(project);
        if (!Files.isRegularFile(file)) return 0L;
        String row = row(module, kind, jdk);
        synchronized (lock(file)) {
            long[] stored = read(file).get(row);
            return stored == null ? 0L : max(stored);
        }
    }

    /** Record {@code peakBytes} as the newest peak for {@code key}. */
    public void note(HeapScope.@Nullable Key key, long peakBytes) {
        if (key == null || peakBytes <= 0 || key.module().isBlank()) return;
        note(key.project(), key.module(), key.kind(), key.jdk(), peakBytes);
    }

    /** As {@link #note(HeapScope.Key, long)}. */
    public void note(Path project, String module, String kind, int jdk, long peakBytes) {
        if (project == null || module == null || module.isBlank() || peakBytes <= 0) return;
        Path file = file(project);
        String row = row(module, kind, jdk);
        synchronized (lock(file)) {
            Map<String, long[]> all = read(file);
            long[] stored = all.get(row);
            all.put(row, prepend(stored == null ? new long[0] : stored, peakBytes));
            try {
                Files.createDirectories(file.getParent());
                AtomicWrites.replace(file, write(all));
            } catch (IOException e) {
                Log.debug("learned heap " + file + ": " + e.getMessage());
            }
        }
    }

    /** The file this project is recorded in, whether or not it exists yet. */
    public Path file(Path project) {
        return stateDir.get().resolve(id(project));
    }

    /** Twice {@code heapBytes}, clamped so the lease fits {@code budgetBytes}. */
    public static long doubled(long heapBytes, long budgetBytes) {
        long twice = Math.max(0, heapBytes) * 2L;
        if (budgetBytes <= 0) return twice;
        return WorkerLeases.clampXmx(twice, budgetBytes);
    }

    private long fit(long bytes) {
        long budget = budget();
        if (budget <= 0) return bytes;
        long clamped = WorkerLeases.clampXmx(bytes, budget);
        return clamped > 0 ? clamped : bytes;
    }

    private static long budget() {
        return WorkerLeases.engine().capacityBytes();
    }

    private Object lock(Path file) {
        return locks.computeIfAbsent(file, p -> new Object());
    }

    private String id(Path project) {
        String dir = project.toAbsolutePath().normalize().toString();
        if (projectIds) {
            try {
                String recorded = ProjectIds.idOf(dir);
                if (recorded != null && !recorded.isBlank()) return recorded;
            } catch (RuntimeException e) {
                Log.debug("learned heap id: " + e.getMessage());
            }
        }
        return Integer.toHexString(dir.hashCode());
    }

    static String row(String module, String kind, int jdk) {
        return module + "\t" + (kind == null ? "" : kind) + "\t" + jdk;
    }

    /** {@code prev} is newest first and may be empty. The result drops whatever falls off the window. */
    private static long[] prepend(long[] prev, long peak) {
        int keep = Math.min(prev.length, WINDOW - 1);
        long[] next = new long[keep + 1];
        next[0] = peak;
        if (keep > 0) System.arraycopy(prev, 0, next, 1, keep);
        return next;
    }

    private static long max(long[] peaks) {
        long best = 0L;
        for (long peak : peaks) {
            if (peak > best) best = peak;
        }
        return best;
    }

    private static Map<String, long[]> read(Path file) {
        Map<String, long[]> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return out;
        }
        for (String line : text.split("\n", -1)) {
            if (line.isBlank() || line.charAt(0) == '#') continue;
            String[] p = line.split("\t", -1);
            if (p.length != 4) continue;
            long[] peaks = parse(p[3]);
            if (peaks.length == 0) continue;
            out.put(p[0] + "\t" + p[1] + "\t" + p[2], peaks);
        }
        return out;
    }

    /** Newest first, at most {@link #WINDOW}, dropping a token that is not a positive long. */
    private static long[] parse(String field) {
        String[] parts = field.split(",", -1);
        long[] tmp = new long[WINDOW];
        int n = 0;
        for (String part : parts) {
            if (n == WINDOW) break;
            try {
                long v = Long.parseLong(part.trim());
                if (v > 0) tmp[n++] = v;
            } catch (NumberFormatException ignored) {
                // a torn token is skipped; the next write replaces the file
            }
        }
        long[] peaks = new long[n];
        System.arraycopy(tmp, 0, peaks, 0, n);
        return peaks;
    }

    private static String write(Map<String, long[]> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# module\tkind\tjdk\tpeaks\n");
        for (var e : rows.entrySet()) {
            sb.append(e.getKey()).append('\t');
            long[] peaks = e.getValue();
            for (int i = 0; i < peaks.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(peaks[i]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
