// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.Log;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.OwnerOnlyFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Learned heaps of jk-planned workers, one file per project under {@code <state>/worker-heaps/}.
 * A key keeps its last {@value #WINDOW} peaks, newest first, and a known-good heap: the largest one
 * it finished at after running out of a smaller one. The next {@code -Xmx} is {@link #size} of the
 * peaks' maximum, never below the known-good heap. A key with no record starts at {@link
 * #firstHeap}. A key may also keep a fingerprint of the inputs its peaks were measured on ({@link
 * #inputs}), so a caller can tell when they no longer apply. Deleting the project's file forgets
 * them. A user-pinned heap is never written.
 */
public final class LearnedHeaps {

    /** Smallest planned heap a learned size will ask for. */
    public static final long FLOOR_BYTES = 256L << 20;

    /** The most {@link #firstHeap} asks for, however large the budget. */
    public static final long FIRST_CAP_BYTES = 2L << 30;

    /** Learned sizes round up to a multiple of this. */
    public static final long STEP_BYTES = 64L << 20;

    /** A key keeps this many peaks, newest first. {@link #size} uses their maximum. */
    static final int WINDOW = 10;

    /** Headroom over the learned peak, in percent, on a developer host. */
    static final int HEADROOM_PERCENT = 130;

    /** Headroom over the learned peak, in percent, on a CI host. */
    static final int CI_HEADROOM_PERCENT = 150;

    private static final boolean CI = EnvValues.isCi(System::getenv);

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

    /** {@link #size(long, long, boolean)} with the headroom of this process's host. */
    public static long size(long peakBytes, long budgetBytes) {
        return size(peakBytes, budgetBytes, CI);
    }

    /**
     * -Xmx is the learned peak × 1.3 (× 1.5 on a CI host, whose suites grow between runs it has
     * already learned), rounded up to 64 MiB, at least 256 MiB, and no larger than the budget can
     * lease.
     */
    public static long size(long peakBytes, long budgetBytes, boolean ci) {
        long percent = ci ? CI_HEADROOM_PERCENT : HEADROOM_PERCENT;
        long scaled = (Math.max(0, peakBytes) * percent + 99L) / 100L;
        long rounded = ((scaled + STEP_BYTES - 1) / STEP_BYTES) * STEP_BYTES;
        return fit(Math.max(FLOOR_BYTES, rounded), budgetBytes);
    }

    /**
     * The heap of a worker jk has not seen: a quarter of the worker budget, at most {@link
     * #FIRST_CAP_BYTES}, never below {@code plannedBytes} and never more than the budget can lease.
     * The ledger admits against {@code -Xmx}, while the worker's resident memory tracks what it
     * uses, so a generous first heap costs parallelism, not RAM.
     */
    public static long firstHeap(long plannedBytes, long budgetBytes) {
        if (budgetBytes <= 0) return plannedBytes;
        long quarter = (Math.min(budgetBytes / 4, FIRST_CAP_BYTES) / STEP_BYTES) * STEP_BYTES;
        return fit(Math.max(plannedBytes, quarter), budgetBytes);
    }

    /**
     * The heap a planned worker should start with: the sizing override when one was given, else
     * {@link #size} of the stored maximum raised to the known-good heap, else {@link #firstHeap} of
     * {@code fallback}. The build-script host and plugin workers, which are not re-run on a larger
     * heap, start an unseen key at {@code fallback} as it is.
     */
    public long choose(Path project, String module, String kind, int jdk, long fallback) {
        long budget = budget();
        if (forcedBytes != null) return fit(forcedBytes, budget);
        Row row = stored(project, module, kind, jdk);
        if (row == null) return generousFirst(kind) ? firstHeap(fallback, budget) : fallback;
        long sized = row.peaks().length == 0 ? 0L : size(max(row.peaks()), budget);
        return fit(Math.max(sized, row.good()), budget);
    }

    /** The kinds re-run on a larger heap when they run out, and so the kinds sized generously first. */
    static boolean generousFirst(String kind) {
        return !HeapScope.KTS_HOST.equals(kind) && !HeapScope.PLUGIN.equals(kind);
    }

    /** Largest stored peak for {@code key}, or {@code 0} when this project has not seen that worker. */
    public long peak(HeapScope.Key key) {
        if (key == null) return 0L;
        return peak(key.project(), key.module(), key.kind(), key.jdk());
    }

    /** Largest stored peak, or {@code 0} when there is no record. */
    public long peak(Path project, String module, String kind, int jdk) {
        Row row = stored(project, module, kind, jdk);
        return row == null ? 0L : max(row.peaks());
    }

    /** The known-good heap for {@code key}, or {@code 0} when it has none. */
    public long good(HeapScope.@Nullable Key key) {
        if (key == null) return 0L;
        Row row = stored(key.project(), key.module(), key.kind(), key.jdk());
        return row == null ? 0L : row.good();
    }

    private @Nullable Row stored(Path project, String module, String kind, int jdk) {
        if (project == null || module == null || module.isBlank()) return null;
        Path file = file(project);
        if (!Files.isRegularFile(file)) return null;
        synchronized (lock(file)) {
            return read(file).get(row(module, kind, jdk));
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
        update(
                project,
                row(module, kind, jdk),
                stored -> new Row(prepend(stored.peaks(), peakBytes), stored.good(), stored.inputs()));
    }

    /**
     * {@code key}'s worker finished at {@code heapBytes} after running out of a smaller heap. It is
     * never sized below the largest such heap again.
     */
    public void good(HeapScope.@Nullable Key key, long heapBytes) {
        if (key == null || heapBytes <= 0 || key.module().isBlank()) return;
        update(
                key.project(),
                row(key.module(), key.kind(), key.jdk()),
                stored -> new Row(stored.peaks(), Math.max(stored.good(), heapBytes), stored.inputs()));
    }

    /** The input fingerprint stored for {@code key}, or {@code ""} when it has none. */
    public String inputs(HeapScope.@Nullable Key key) {
        if (key == null) return "";
        Row row = stored(key.project(), key.module(), key.kind(), key.jdk());
        return row == null ? "" : row.inputs();
    }

    /** Store {@code fingerprint} as the inputs {@code key}'s next peaks are measured on. Tabs and newlines are dropped. */
    public void inputs(HeapScope.@Nullable Key key, String fingerprint) {
        if (key == null || key.module().isBlank() || fingerprint == null) return;
        String clean = fingerprint.replace('\t', ' ').replace('\n', ' ');
        update(
                key.project(),
                row(key.module(), key.kind(), key.jdk()),
                stored -> new Row(stored.peaks(), stored.good(), clean));
    }

    private void update(Path project, String row, UnaryOperator<Row> change) {
        Path file = file(project);
        synchronized (lock(file)) {
            Map<String, Row> all = read(file);
            all.put(row, change.apply(all.getOrDefault(row, Row.EMPTY)));
            try {
                OwnerOnlyFiles.createDirectories(Objects.requireNonNull(file.getParent(), "a file has a directory"));
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

    /** {@code bytes} lowered so its lease fits {@code budgetBytes}; unchanged when there is no budget. */
    private static long fit(long bytes, long budgetBytes) {
        if (budgetBytes <= 0) return bytes;
        long clamped = WorkerLeases.clampXmx(bytes, budgetBytes);
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

    private static Map<String, Row> read(Path file) {
        Map<String, Row> out = new LinkedHashMap<>();
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
            if (p.length != 6) continue;
            long[] peaks = parse(p[3]);
            long good = positive(p[4]);
            if (peaks.length == 0 && good == 0 && p[5].isBlank()) continue;
            out.put(p[0] + "\t" + p[1] + "\t" + p[2], new Row(peaks, good, p[5]));
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
            long v = positive(part);
            if (v > 0) tmp[n++] = v;
        }
        long[] peaks = new long[n];
        System.arraycopy(tmp, 0, peaks, 0, n);
        return peaks;
    }

    /** {@code token} as a positive long, else {@code 0}. A torn token is dropped; the next write replaces it. */
    private static long positive(String token) {
        try {
            return Math.max(0L, Long.parseLong(token.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String write(Map<String, Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# module\tkind\tjdk\tpeaks\tgood\tinputs\n");
        for (var e : rows.entrySet()) {
            sb.append(e.getKey()).append('\t');
            long[] peaks = e.getValue().peaks();
            for (int i = 0; i < peaks.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(peaks[i]);
            }
            sb.append('\t').append(e.getValue().good());
            sb.append('\t').append(e.getValue().inputs()).append('\n');
        }
        return sb.toString();
    }

    /**
     * One key's peaks, newest first, its known-good heap ({@code 0} when it has none), and the
     * fingerprint of the inputs they were measured on ({@code ""} when none was stored).
     */
    private record Row(long[] peaks, long good, String inputs) {
        static final Row EMPTY = new Row(new long[0], 0L, "");
    }
}
