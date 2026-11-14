// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.StatIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>Each key also keeps its last {@value #WINDOW} resident-set peaks, and a host-wide row per
 * worker kind keeps the last peaks of every module, so {@link #price} can lease a worker at what it
 * is expected to hold rather than at its {@code -Xmx}.
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

    private static final boolean CI = EnvValues.isCi(ProcessEnv::get);

    /** Lease over the expected resident peak, in percent: a JVM worker. */
    static final int PRICE_PERCENT = 125;

    /** Lease over the expected resident peak, in percent: a native-image build. */
    static final int NATIVE_PRICE_PERCENT = 130;

    /** Smallest lease {@link #price} asks for a JVM worker. */
    static final long PRICE_FLOOR_BYTES = 160L << 20;

    /** Smallest lease {@link #price} asks for a native-image build. */
    static final long NATIVE_PRICE_FLOOR_BYTES = 2L << 30;

    /** What a JVM worker is expected to hold when neither it nor its kind was seen on this host. */
    static final long PRIOR_BYTES = 512L << 20;

    /** What a native-image build is expected to hold when none was seen on this host. */
    static final long NATIVE_PRIOR_BYTES = 3L << 30;

    /** The module name of a kind's host-wide row, filed in {@link #HOST_FILE}. */
    static final String ANY_MODULE = "*";

    /** The file under the state directory that keeps each kind's host-wide resident peaks. */
    static final String HOST_FILE = "host";

    private static final LearnedHeaps ENGINE =
            new LearnedHeaps(() -> JkDirs.state().resolve("worker-heaps"), null, true);

    private final Supplier<Path> stateDir;
    private final @Nullable Long forcedBytes;
    private final boolean projectIds;
    private final Object[] locks = new Object[64];

    /** Each file's rows as last read or written, by the file's stat identity then. */
    private final ConcurrentHashMap<Path, Parsed> parsed = new ConcurrentHashMap<>();

    private final AtomicInteger parses = new AtomicInteger();

    private record Parsed(String stat, Map<String, Row> rows) {}

    private LearnedHeaps(Supplier<Path> stateDir, @Nullable Long forcedBytes, boolean projectIds) {
        this.stateDir = stateDir;
        this.forcedBytes = forcedBytes;
        this.projectIds = projectIds;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
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
        return stored(file(project), row(module, kind, jdk));
    }

    private @Nullable Row stored(Path file, String row) {
        if (!Files.isRegularFile(file)) return null;
        synchronized (lock(file)) {
            return rows(file).get(row);
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
                stored ->
                        new Row(prepend(stored.peaks(), peakBytes), stored.good(), stored.inputs(), stored.resident()));
    }

    /**
     * Record {@code residentBytes} as the newest resident-set peak for {@code key} and for its
     * kind's host-wide row.
     */
    public void noteResident(HeapScope.@Nullable Key key, long residentBytes) {
        if (key == null || residentBytes <= 0 || key.module().isBlank()) return;
        UnaryOperator<Row> add = stored -> stored.withResident(prepend(stored.resident(), residentBytes));
        update(key.project(), row(key.module(), key.kind(), key.jdk()), add);
        updateFile(stateDir.get().resolve(HOST_FILE), row(ANY_MODULE, key.kind(), 0), add);
    }

    /**
     * The bytes to lease {@code key}'s worker at: its largest recorded resident peak, else the
     * largest its kind reached on this host lately, else a prior; times {@link #PRICE_PERCENT}
     * ({@link #NATIVE_PRICE_PERCENT} for native-image), rounded up to 64 MiB, at least the floor.
     * The ledger never leases more than the worker's {@code -Xmx} lease.
     */
    public long price(HeapScope.@Nullable Key key) {
        boolean nativeImage = key != null && HeapScope.NATIVE_IMAGE.equals(key.kind());
        long expected = 0;
        if (key != null && !key.module().isBlank()) {
            Row own = stored(file(key.project()), row(key.module(), key.kind(), key.jdk()));
            if (own != null) expected = max(own.resident());
            if (expected == 0) {
                Row kind = stored(stateDir.get().resolve(HOST_FILE), row(ANY_MODULE, key.kind(), 0));
                if (kind != null) expected = max(kind.resident());
            }
        }
        if (expected == 0) expected = nativeImage ? NATIVE_PRIOR_BYTES : PRIOR_BYTES;
        long percent = nativeImage ? NATIVE_PRICE_PERCENT : PRICE_PERCENT;
        long scaled = (expected * percent + 99L) / 100L;
        long rounded = ((scaled + STEP_BYTES - 1) / STEP_BYTES) * STEP_BYTES;
        return Math.max(nativeImage ? NATIVE_PRICE_FLOOR_BYTES : PRICE_FLOOR_BYTES, rounded);
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
                stored -> new Row(
                        stored.peaks(), Math.max(stored.good(), heapBytes), stored.inputs(), stored.resident()));
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
                stored -> new Row(stored.peaks(), stored.good(), clean, stored.resident()));
    }

    private void update(Path project, String row, UnaryOperator<Row> change) {
        updateFile(file(project), row, change);
    }

    private void updateFile(Path file, String row, UnaryOperator<Row> change) {
        synchronized (lock(file)) {
            Map<String, Row> all = new LinkedHashMap<>(rows(file));
            all.put(row, change.apply(all.getOrDefault(row, Row.EMPTY)));
            try {
                OwnerOnlyFiles.createDirectories(Objects.requireNonNull(file.getParent(), "a file has a directory"));
                AtomicWrites.replace(file, write(all));
                parsed.put(file, new Parsed(StatIdentity.of(file).encode(), Collections.unmodifiableMap(all)));
            } catch (IOException e) {
                parsed.remove(file);
                Log.debug("learned heap " + file + ": " + e.getMessage());
            }
        }
    }

    /**
     * {@code file}'s rows, parsed again only when its stat identity moved: every worker fork asks,
     * and another engine sharing the state directory may have written it since.
     */
    private Map<String, Row> rows(Path file) {
        String stat;
        try {
            stat = StatIdentity.of(file).encode();
        } catch (IOException absent) {
            parsed.remove(file);
            return Map.of();
        }
        Parsed had = parsed.get(file);
        if (had != null && had.stat().equals(stat)) return had.rows();
        parses.incrementAndGet();
        Map<String, Row> rows = Collections.unmodifiableMap(read(file));
        parsed.put(file, new Parsed(stat, rows));
        return rows;
    }

    /** How many times this store has parsed a file. Test seam. */
    int parses() {
        return parses.get();
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
        return locks[Math.floorMod(file.hashCode(), locks.length)];
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
            if (p.length != 7) continue;
            long[] peaks = parse(p[3]);
            long good = positive(p[4]);
            long[] resident = parse(p[6]);
            if (peaks.length == 0 && good == 0 && p[5].isBlank() && resident.length == 0) continue;
            out.put(p[0] + "\t" + p[1] + "\t" + p[2], new Row(peaks, good, p[5], resident));
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
        sb.append("# module\tkind\tjdk\tpeaks\tgood\tinputs\tresident\n");
        for (var e : rows.entrySet()) {
            sb.append(e.getKey()).append('\t');
            join(sb, e.getValue().peaks());
            sb.append('\t').append(e.getValue().good());
            sb.append('\t').append(e.getValue().inputs()).append('\t');
            join(sb, e.getValue().resident());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void join(StringBuilder sb, long[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
    }

    /**
     * One key's heap peaks, newest first, its known-good heap ({@code 0} when it has none), the
     * fingerprint of the inputs they were measured on ({@code ""} when none was stored), and its
     * resident-set peaks, newest first.
     */
    private record Row(long[] peaks, long good, String inputs, long[] resident) {
        static final Row EMPTY = new Row(new long[0], 0L, "", new long[0]);

        Row withResident(long[] next) {
            return new Row(peaks, good, inputs, next);
        }
    }
}
