// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.DirKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a project's run means ({@link ProjectStore}) and the host's ({@link HostStore}).
 * Prefer a key's last sample, then its mean, for ETA ladders; its count for confidence.
 *
 * <p>Module keys are absolute in this view: the ledger spells a module relative to its project
 * root ({@link ModuleKeys}) and {@link #load} expands them against the one directory it was asked
 * for, so a worktree reads the rows its siblings wrote. A view holds one project's ledger at most,
 * so its size follows that project, never the number of projects or checkouts on the host; a
 * caller outside any project gets {@link #hostOnly}, the host's per-kind means and counts. Per-class test walls come from the store's
 * class rows and are read by module through
 * {@link #classWalls()}. The files read are stamped (mtime and size); {@link #fresh()} is two
 * stats, which is what lets a memo hold a parsed view until a write lands.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class AggregatedMetrics {

    private static final AtomicLong PARSES = new AtomicLong();

    private final Map<String, Double> mean;
    private final Map<String, Double> last;
    private final Map<String, Long> count;
    private final Map<String, Double> hostMean;
    /** Host-wide sample counts per key, the confidence of {@link #hostMean}. */
    private final Map<String, Long> hostCount;
    /** Sanitized absolute module dir → test class → wall ms. */
    private final Map<String, Map<String, Long>> classWalls;

    private final List<FileStamp> stamps;

    /** One file as it stood when it was parsed; a missing file is {@code (-1, -1)}. */
    record FileStamp(Path file, long mtimeMillis, long size) {
        static FileStamp of(Path file) {
            try {
                BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                return new FileStamp(file, a.lastModifiedTime().toMillis(), a.size());
            } catch (IOException | RuntimeException e) {
                return new FileStamp(file, -1, -1);
            }
        }

        boolean unchanged() {
            return equals(of(file));
        }
    }

    public static AggregatedMetrics empty() {
        return new AggregatedMetrics(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
    }

    /** Heap one map entry holds besides its key's characters: the node, the key's header, a boxed value. */
    private static final long ENTRY_BYTES = 96;

    /** Estimated heap this view holds: per entry {@link #ENTRY_BYTES} plus a byte per key character. */
    public long retainedBytes() {
        long bytes =
                entryBytes(mean) + entryBytes(last) + entryBytes(count) + entryBytes(hostMean) + entryBytes(hostCount);
        for (var e : classWalls.entrySet()) bytes += ENTRY_BYTES + e.getKey().length() + entryBytes(e.getValue());
        return bytes;
    }

    private static long entryBytes(Map<String, ?> map) {
        long bytes = 0;
        for (String key : map.keySet()) bytes += ENTRY_BYTES + key.length();
        return bytes;
    }

    /** True while every file this view was parsed from still has the mtime and size it had then. */
    public boolean fresh() {
        for (FileStamp s : stamps) {
            if (!s.unchanged()) return false;
        }
        return true;
    }

    /** Test seam: ledger files parsed since process start. */
    public static long parseCount() {
        return PARSES.get();
    }

    /** The sanitized absolute form of {@code projectDir}, the root relative module keys expand against. */
    static String rootKey(Path projectDir) {
        return sanitize(projectDir.toAbsolutePath().normalize().toString());
    }

    /** Load project metrics for {@code coord}+{@code projectDir} plus host means. */
    public static AggregatedMetrics load(@Nullable String coord, Path projectDir) {
        return load(ProjectBuilds.buildsRoot(), coord, projectDir);
    }

    public static AggregatedMetrics load(Path buildsRoot, @Nullable String coord, Path projectDir) {
        Path home = ProjectBuilds.projectHome(buildsRoot, coord, projectDir);
        Map<String, Double> mean = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        Map<String, Long> count = new LinkedHashMap<>();
        Map<String, Map<String, Long>> classWalls = new LinkedHashMap<>();
        List<FileStamp> stamps = new ArrayList<>(2);
        stamps.add(FileStamp.of(ProjectStore.file(home)));
        parseProject(home, rootKey(projectDir), mean, last, count, classWalls);
        Map<String, Double> hostMean = new LinkedHashMap<>();
        Map<String, Long> hostCount = new LinkedHashMap<>();
        Path store = buildsRoot.resolve(HostStore.FILE);
        stamps.add(FileStamp.of(store));
        parseHost(store, hostMean, hostCount);
        return new AggregatedMetrics(mean, last, count, hostMean, hostCount, classWalls, List.copyOf(stamps));
    }

    /**
     * The host's means and counts only: the view for a caller outside any project (the engine's own
     * working dir, a dashboard). Its size is the number of host keys, a few per task kind.
     */
    public static AggregatedMetrics hostOnly(Path buildsRoot) {
        Map<String, Double> hostMean = new LinkedHashMap<>();
        Map<String, Long> hostCount = new LinkedHashMap<>();
        Path store = buildsRoot.resolve(HostStore.FILE);
        parseHost(store, hostMean, hostCount);
        return new AggregatedMetrics(
                Map.of(), Map.of(), Map.of(), hostMean, hostCount, Map.of(), List.of(FileStamp.of(store)));
    }

    /**
     * Prefer last-success, then trimmed mean — but reject a last sample that is an obvious
     * cache-restore blip relative to the mean (e.g. native-image {@code 32ms} after real
     * {@code ~32s} walls). Those poison ETA when action-cache hits are recorded as SUCCESS.
     */
    public OptionalDouble value(String key) {
        if (key == null) return OptionalDouble.empty();
        Double l = last.get(key);
        Double m = mean.get(key);
        if (l != null && l > 0 && isCredibleLast(l, m)) return OptionalDouble.of(l);
        if (m != null && m > 0) return OptionalDouble.of(m);
        Double h = hostMean.get(key);
        if (h != null && h > 0) return OptionalDouble.of(h);
        return OptionalDouble.empty();
    }

    /** {@code last} is usable when mean is unknown, or last is not a tiny fraction of mean. */
    static boolean isCredibleLast(double lastMs, @Nullable Double meanMs) {
        if (!(lastMs > 0)) return false;
        if (meanMs == null || !(meanMs > 0)) return true;
        // Cache-restore / skip mis-recorded as SUCCESS: 32ms last vs 32s mean.
        if (meanMs >= 5_000.0 && lastMs < meanMs * 0.2 && lastMs < 5_000.0) return false;
        return true;
    }

    public OptionalDouble mean(String key) {
        Double m = mean.get(key);
        if (m != null && m > 0) return OptionalDouble.of(m);
        Double h = hostMean.get(key);
        if (h != null && h > 0) return OptionalDouble.of(h);
        return OptionalDouble.empty();
    }

    public OptionalDouble last(String key) {
        Double l = last.get(key);
        return l != null && l > 0 ? OptionalDouble.of(l) : OptionalDouble.empty();
    }

    public long count(String key) {
        Long c = count.get(key);
        return c != null ? c : hostCount.getOrDefault(key, 0L);
    }

    public OptionalLong stepWallMs(String dir, String step) {
        return taskWallMs(dir, step);
    }

    public OptionalLong taskWallMs(String dir, String task) {
        if (task == null || task.isBlank()) return OptionalLong.empty();
        String t = sanitize(task);
        if (dir != null && !dir.isBlank()) {
            String mod = sanitize(dir);
            OptionalDouble v = value("module." + mod + ".task." + t + ".wall-ms");
            if (v.isPresent()) return OptionalLong.of(Math.round(v.getAsDouble()));
        }
        OptionalDouble v = value("task." + t + ".wall-ms");
        return v.isPresent() ? OptionalLong.of(Math.round(v.getAsDouble())) : OptionalLong.empty();
    }

    /** Measured class wall for {@code fqcn} under module {@code dir}, if any. */
    public OptionalLong testClassWallMs(String dir, String fqcn) {
        if (dir == null || dir.isBlank() || fqcn == null || fqcn.isBlank()) return OptionalLong.empty();
        Long v = classWalls.getOrDefault(sanitize(dir), Map.of()).get(sanitize(fqcn));
        return v != null && v > 0 ? OptionalLong.of(v) : OptionalLong.empty();
    }

    /** Per-class test walls by sanitized absolute module dir, then by class name. */
    public Map<String, Map<String, Long>> classWalls() {
        return classWalls;
    }

    public Map<String, Double> meanMap() {
        return mean;
    }

    public Map<String, Double> lastMap() {
        return last;
    }

    public Map<String, Double> hostMeanMap() {
        return hostMean;
    }

    public static String sanitize(@Nullable String s) {
        if (s == null) return "unknown";
        // Forward slashes first so Windows paths stay one key family with Unix; a POSIX
        // backslash name is NOT a separator and folds to '_' like any other odd character.
        return DirKeys.slashes(s).replaceAll("[^a-zA-Z0-9._:/@-]+", "_");
    }

    /**
     * The project store's rows into the view: module keys expanded against {@code root}, a class
     * wall by its mean. A module step's {@code last} is left out: its wall is read from its mean,
     * which is its one sample until it has more.
     */
    private static void parseProject(
            Path home,
            @Nullable String root,
            Map<String, Double> mean,
            Map<String, Double> last,
            Map<String, Long> count,
            Map<String, Map<String, Long>> classWalls) {
        PARSES.incrementAndGet();
        try {
            ProjectStore.scan(
                    home,
                    (rawKey, m) -> {
                        String key = ModuleKeys.absolute(rawKey, root);
                        mean.put(key, m.mean());
                        if (!moduleTaskRow(rawKey)) last.put(key, m.last());
                        count.put(key, m.count());
                    },
                    (dir, fqcn, m) -> {
                        if (!(m.mean() > 0)) return;
                        classWalls
                                .computeIfAbsent(ModuleKeys.absoluteDir(dir, root), k -> new LinkedHashMap<>())
                                .put(fqcn, Math.round(m.mean()));
                    });
        } catch (RuntimeException e) {
            Log.debug("parseProject: RuntimeException ignored", e);
        }
    }

    /** {@code module.<dir>.task.<step>.<measure>}: a row the step scheduler reads by its mean. */
    static boolean moduleTaskRow(String key) {
        return key.startsWith("module.") && key.contains(".task.");
    }

    private static void parseHost(Path store, Map<String, Double> hostMean, Map<String, Long> hostCount) {
        // The host store's continuous rates and calibration constants are host-tier keys too.
        for (String section : List.of(HostStore.RATES, HostStore.CALIBRATION)) {
            for (String line : HostStore.section(store, section)) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                try {
                    double v = Double.parseDouble(line.substring(eq + 1).strip());
                    hostMean.putIfAbsent(line.substring(0, eq).strip(), v);
                } catch (NumberFormatException notANumber) {
                    // a string or boolean constant: not a mean
                }
            }
        }
        HostStore.means(store).forEach((key, m) -> {
            hostMean.putIfAbsent(key, m.mean());
            hostCount.putIfAbsent(key, m.count());
        });
    }
}
