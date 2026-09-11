// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.util.DirKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of harvested {@code project-metrics.toml} + {@code host-metrics.toml} scalars.
 * Prefer {@code [last]} then {@code [mean]} for ETA ladders; {@code [count]} for confidence.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class AggregatedMetrics {

    private static final Pattern SECTION = Pattern.compile("(?m)^\\[([a-zA-Z0-9._-]+)\\]\\s*$");
    private static final Pattern KEY_EQ =
            Pattern.compile("(?m)^([a-zA-Z0-9._:/-]+)\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");

    private final Map<String, Double> mean;
    private final Map<String, Double> last;
    private final Map<String, Long> count;
    private final Map<String, Double> hostMean;

    public static AggregatedMetrics empty() {
        return new AggregatedMetrics(Map.of(), Map.of(), Map.of(), Map.of());
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
        parseProjectFile(home.resolve(ProjectBuilds.PROJECT_METRICS), mean, last, count);
        Map<String, Double> hostMean = new LinkedHashMap<>();
        parseHostMean(ProjectBuilds.hostMetricsFile(buildsRoot), hostMean);
        return new AggregatedMetrics(mean, last, count, hostMean);
    }

    /**
     * Scan every project's metrics (for global step tiers / dashboards).
     *
     * <p>Module paths are absolute, so the same checkout can appear under multiple project
     * identity keys (stale re-key, old hash). Naïve last-wins would let a one-sample outlier
     * (e.g. 126s {@code run-tests}) overwrite a well-sampled ~33s mean and inflate ETA. Merge
     * prefers the row with the higher {@code [count]}.
     */
    public static AggregatedMetrics loadAll(Path buildsRoot) {
        Map<String, Double> mean = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        Map<String, Long> count = new LinkedHashMap<>();
        // Prefer one home per checkout path so stale re-keyed identities do not re-enter.
        for (Path home : ProjectBuilds.listProjectHomesForMetrics(buildsRoot)) {
            Map<String, Double> m = new LinkedHashMap<>();
            Map<String, Double> l = new LinkedHashMap<>();
            Map<String, Long> c = new LinkedHashMap<>();
            parseProjectFile(home.resolve(ProjectBuilds.PROJECT_METRICS), m, l, c);
            mergePreferHigherCount(mean, last, count, m, l, c);
        }
        Map<String, Double> hostMean = new LinkedHashMap<>();
        parseHostMean(ProjectBuilds.hostMetricsFile(buildsRoot), hostMean);
        return new AggregatedMetrics(mean, last, count, hostMean);
    }

    /**
     * Fold one project's scalars into the global maps. Prefer higher sample {@code count} so a
     * stale project identity cannot poison ETA with a single long wall.
     */
    static void mergePreferHigherCount(
            Map<String, Double> mean,
            Map<String, Double> last,
            Map<String, Long> count,
            Map<String, Double> srcMean,
            Map<String, Double> srcLast,
            Map<String, Long> srcCount) {
        // Per-key merge over the union of source keys. mean/last/count move together; a row
        // with a real mean outranks a mean-less row (count is the mean's sample size).
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (srcMean != null) keys.addAll(srcMean.keySet());
        if (srcLast != null) keys.addAll(srcLast.keySet());
        for (String key : keys) {
            Double newMean = srcMean != null ? srcMean.get(key) : null;
            Double newLast = srcLast != null ? srcLast.get(key) : null;
            boolean newHasMean = newMean != null && newMean > 0;
            boolean newHasLast = newLast != null && newLast > 0;
            if (!newHasMean && !newHasLast) continue;
            long newC = Math.max(1L, srcCount != null ? srcCount.getOrDefault(key, 1L) : 1L);
            long oldC = count.getOrDefault(key, 0L);
            boolean oldHasMean = mean.containsKey(key);
            boolean win = newC > oldC || oldC == 0;
            if (newHasMean && !oldHasMean) win = true;
            if (!newHasMean && oldHasMean) win = false;
            if (!win) continue;
            if (newHasMean) mean.put(key, newMean);
            else mean.remove(key);
            if (newHasLast) last.put(key, newLast);
            else last.remove(key);
            count.put(key, newC);
        }
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
        return count.getOrDefault(key, 0L);
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
        OptionalDouble v = value("module." + sanitize(dir) + ".test-class." + sanitize(fqcn) + ".wall-ms");
        return v.isPresent() ? OptionalLong.of(Math.round(v.getAsDouble())) : OptionalLong.empty();
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
        return DirKeys.slashes(s).replaceAll("[^a-zA-Z0-9._:/-]+", "_");
    }

    private static void parseProjectFile(
            Path file, Map<String, Double> mean, Map<String, Double> last, Map<String, Long> count) {
        if (!Files.isRegularFile(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String section = "";
            for (String line : text.split("\n")) {
                Matcher sm = SECTION.matcher(line);
                if (sm.matches()) {
                    section = sm.group(1);
                    continue;
                }
                Matcher km = KEY_EQ.matcher(line);
                if (!km.matches()) continue;
                String key = km.group(1);
                double v = Double.parseDouble(km.group(2));
                switch (section) {
                    case "mean" -> mean.put(key, v);
                    case "last" -> last.put(key, v);
                    case "count" -> count.put(key, Math.round(v));
                    default -> {
                        // flat keys without section → treat as mean
                        if (section.isEmpty()) mean.putIfAbsent(key, v);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void parseHostMean(Path file, Map<String, Double> hostMean) {
        if (!Files.isRegularFile(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String section = "";
            for (String line : text.split("\n")) {
                Matcher sm = SECTION.matcher(line);
                if (sm.matches()) {
                    section = sm.group(1);
                    continue;
                }
                Matcher km = KEY_EQ.matcher(line);
                if (!km.matches()) continue;
                String key = km.group(1);
                double v = Double.parseDouble(km.group(2));
                if ("mean".equals(section) || section.isEmpty() || "calibration".equals(section)) {
                    hostMean.putIfAbsent(key, v);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
