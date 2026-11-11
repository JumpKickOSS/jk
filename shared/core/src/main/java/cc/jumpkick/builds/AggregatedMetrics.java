// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.Log;
import cc.jumpkick.util.DirKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Read-only view of harvested {@code project-metrics.toml} + {@code host-metrics.toml} scalars.
 * Prefer {@code [last]} then {@code [mean]} for ETA ladders; {@code [count]} for confidence.
 *
 * <p>Module keys are absolute in this view: the ledger spells a module relative to its project
 * root ({@link ModuleKeys}) and {@link #load} expands them against the one directory it was asked
 * for, so a worktree reads the rows its siblings wrote. A view holds one project's ledger at most,
 * so its size follows that project, never the number of projects or checkouts on the host; a
 * caller outside any project gets {@link #hostOnly}, the host's per-kind means and counts. Per-class test walls come from the ledger's
 * {@code [test-class."<dir>"]} tables ({@link MetricsFile}) and are read by module through
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
        Path project = home.resolve(ProjectBuilds.PROJECT_METRICS);
        stamps.add(FileStamp.of(project));
        parseProjectFile(project, rootKey(projectDir), mean, last, count, classWalls);
        Map<String, Double> hostMean = new LinkedHashMap<>();
        Map<String, Long> hostCount = new LinkedHashMap<>();
        Path host = ProjectBuilds.hostMetricsFile(buildsRoot);
        stamps.add(FileStamp.of(host));
        parseHost(host, hostMean, hostCount);
        return new AggregatedMetrics(mean, last, count, hostMean, hostCount, classWalls, List.copyOf(stamps));
    }

    /**
     * The host's means and counts only: the view for a caller outside any project (the engine's own
     * working dir, a dashboard). Its size is the number of host keys, a few per task kind.
     */
    public static AggregatedMetrics hostOnly(Path buildsRoot) {
        Map<String, Double> hostMean = new LinkedHashMap<>();
        Map<String, Long> hostCount = new LinkedHashMap<>();
        Path host = ProjectBuilds.hostMetricsFile(buildsRoot);
        parseHost(host, hostMean, hostCount);
        return new AggregatedMetrics(
                Map.of(), Map.of(), Map.of(), hostMean, hostCount, Map.of(), List.of(FileStamp.of(host)));
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

    public OptionalLong phaseWallMs(String dir, String phase) {
        if (phase == null || phase.isBlank()) return OptionalLong.empty();
        String p = sanitize(phase);
        if (dir != null && !dir.isBlank()) {
            OptionalDouble v = value("module." + sanitize(dir) + ".phase." + p + ".wall-ms");
            if (v.isPresent()) return OptionalLong.of(Math.round(v.getAsDouble()));
        }
        OptionalDouble v = value("phase." + p + ".wall-ms");
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

    public OptionalLong invocationWallMs(String kind, String dirKey) {
        String k = kind == null ? "build" : kind;
        if (dirKey != null && !dirKey.isBlank()) {
            OptionalDouble shaped = value("invocation." + sanitize(k) + "." + sanitize(dirKey) + ".wall-ms");
            if (shaped.isPresent()) return OptionalLong.of(Math.round(shaped.getAsDouble()));
            // strip #dN
            int hash = dirKey.lastIndexOf("#d");
            if (hash > 0) {
                String base = dirKey.substring(0, hash);
                OptionalDouble any = value("invocation." + sanitize(k) + "." + sanitize(base) + ".wall-ms");
                if (any.isPresent()) return OptionalLong.of(Math.round(any.getAsDouble()));
            }
        }
        OptionalDouble ws = value("workspace.wall-ms");
        if (ws.isPresent() && (kind == null || "build".equals(kind) || kind.startsWith("build"))) {
            return OptionalLong.of(Math.round(ws.getAsDouble()));
        }
        return OptionalLong.empty();
    }

    public OptionalDouble perUnitMs(String dir, String step) {
        if (step == null) return OptionalDouble.empty();
        String t = sanitize(step);
        if (dir != null && !dir.isBlank()) {
            String mod = sanitize(dir);
            OptionalDouble v = value("module." + mod + ".task." + t + ".per-unit-ms");
            if (v.isPresent()) return v;
        }
        return value("task." + t + ".per-unit-ms");
    }

    public OptionalDouble hostRate(String key) {
        Double h = hostMean.get(key);
        return h != null && h > 0 ? OptionalDouble.of(h) : OptionalDouble.empty();
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

    private static void parseProjectFile(
            Path file,
            @Nullable String root,
            Map<String, Double> mean,
            Map<String, Double> last,
            Map<String, Long> count,
            Map<String, Map<String, Long>> classWalls) {
        String text = readLedger(file);
        if (text != null) parseProjectText(text, root, mean, last, count, classWalls);
    }

    private static @Nullable String readLedger(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Log.debug("readLedger: IOException|RuntimeException ignored", e);
            return null;
        }
    }

    private static void parseProjectText(
            String text,
            @Nullable String root,
            Map<String, Double> mean,
            Map<String, Double> last,
            Map<String, Long> count,
            Map<String, Map<String, Long>> classWalls) {
        PARSES.incrementAndGet();
        try {
            MetricsFile.scan(
                    text,
                    (section, rawKey, v) -> {
                        String key = ModuleKeys.absolute(rawKey, root);
                        switch (section) {
                            case "mean" -> mean.put(key, v);
                            case "last" -> last.put(key, v);
                            case "count" -> count.put(key, Math.round(v));
                            default -> {
                                // flat keys without section → treat as mean
                                if (section.isEmpty()) mean.putIfAbsent(key, v);
                            }
                        }
                    },
                    (dir, fqcn, ms) -> {
                        if (!(ms > 0)) return;
                        classWalls
                                .computeIfAbsent(ModuleKeys.absoluteDir(dir, root), k -> new LinkedHashMap<>())
                                .put(fqcn, Math.round(ms));
                    });
        } catch (RuntimeException e) {
            Log.debug("parseProjectText: RuntimeException ignored", e);
        }
    }

    private static void parseHost(Path file, Map<String, Double> hostMean, Map<String, Long> hostCount) {
        if (!Files.isRegularFile(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            MetricsFile.scan(
                    text,
                    (section, key, v) -> {
                        if ("mean".equals(section) || section.isEmpty() || "calibration".equals(section)) {
                            hostMean.putIfAbsent(key, v);
                        } else if ("count".equals(section)) {
                            hostCount.putIfAbsent(key, Math.round(v));
                        }
                    },
                    (dir, fqcn, ms) -> {});
        } catch (IOException | RuntimeException e) {
            Log.debug("parseHost: IOException|RuntimeException ignored", e);
        }
    }
}
