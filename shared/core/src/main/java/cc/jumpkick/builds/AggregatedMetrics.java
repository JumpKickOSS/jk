// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only view of harvested {@code project-metrics.toml} + {@code host-metrics.toml} scalars.
 * Prefer {@code [last]} then {@code [mean]} for ETA ladders; {@code [count]} for confidence.
 */
public final class AggregatedMetrics {

    private static final Pattern SECTION = Pattern.compile("(?m)^\\[([a-zA-Z0-9._-]+)\\]\\s*$");
    private static final Pattern KEY_EQ =
            Pattern.compile("(?m)^([a-zA-Z0-9._:/-]+)\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");

    private final Map<String, Double> mean;
    private final Map<String, Double> last;
    private final Map<String, Long> count;
    private final Map<String, Double> hostMean;

    private AggregatedMetrics(
            Map<String, Double> mean, Map<String, Double> last, Map<String, Long> count, Map<String, Double> hostMean) {
        this.mean = mean;
        this.last = last;
        this.count = count;
        this.hostMean = hostMean;
    }

    public static AggregatedMetrics empty() {
        return new AggregatedMetrics(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** Load project metrics for {@code coord}+{@code projectDir} plus host means. */
    public static AggregatedMetrics load(String coord, Path projectDir) {
        return load(ProjectBuilds.buildsRoot(), coord, projectDir);
    }

    public static AggregatedMetrics load(Path buildsRoot, String coord, Path projectDir) {
        Path home = ProjectBuilds.projectHome(buildsRoot, coord, projectDir);
        Map<String, Double> mean = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        Map<String, Long> count = new LinkedHashMap<>();
        parseProjectFile(home.resolve(ProjectBuilds.PROJECT_METRICS), mean, last, count);
        Map<String, Double> hostMean = new LinkedHashMap<>();
        parseHostMean(ProjectBuilds.hostMetricsFile(buildsRoot), hostMean);
        return new AggregatedMetrics(mean, last, count, hostMean);
    }

    /** Scan every project's metrics (for global step tiers / dashboards). */
    public static AggregatedMetrics loadAll(Path buildsRoot) {
        Map<String, Double> mean = new LinkedHashMap<>();
        Map<String, Double> last = new LinkedHashMap<>();
        Map<String, Long> count = new LinkedHashMap<>();
        for (Path home : ProjectBuilds.listProjectHomes(buildsRoot)) {
            parseProjectFile(home.resolve(ProjectBuilds.PROJECT_METRICS), mean, last, count);
        }
        Map<String, Double> hostMean = new LinkedHashMap<>();
        parseHostMean(ProjectBuilds.hostMetricsFile(buildsRoot), hostMean);
        return new AggregatedMetrics(mean, last, count, hostMean);
    }

    /** Prefer last-success, then trimmed mean. */
    public OptionalDouble value(String key) {
        if (key == null) return OptionalDouble.empty();
        Double l = last.get(key);
        if (l != null && l > 0) return OptionalDouble.of(l);
        Double m = mean.get(key);
        if (m != null && m > 0) return OptionalDouble.of(m);
        Double h = hostMean.get(key);
        if (h != null && h > 0) return OptionalDouble.of(h);
        return OptionalDouble.empty();
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
        if (step == null || step.isBlank()) return OptionalLong.empty();
        if (dir != null && !dir.isBlank()) {
            OptionalDouble v = value("module." + sanitize(dir) + ".step." + sanitize(step) + ".wall-ms");
            if (v.isPresent()) return OptionalLong.of(Math.round(v.getAsDouble()));
        }
        OptionalDouble v = value("step." + sanitize(step) + ".wall-ms");
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
        if (dir != null && !dir.isBlank()) {
            OptionalDouble v = value("module." + sanitize(dir) + ".step." + sanitize(step) + ".per-unit-ms");
            if (v.isPresent()) return v;
        }
        return value("step." + sanitize(step) + ".per-unit-ms");
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

    public static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._:/-]+", "_");
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
                if ("mean".equals(section)
                        || section.isEmpty()
                        || "probe".equals(section)
                        || "calibration".equals(section)) {
                    hostMean.putIfAbsent(key, v);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
