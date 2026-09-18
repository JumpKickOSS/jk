// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Single serial worker that walks project run directories, reaps old runs, and writes host-metrics
 * plus each project's project-metrics as scalar trimmed means and last-success values (no sample
 * rings on disk), with per-class test walls as one table per module ({@link MetricsFile}).
 *
 * <p>Every build finish calls {@link #request()}; concurrent requests coalesce into one re-run.
 */
public final class MetricsHarvest {

    /** Default: keep 50 runs per project. */
    public static final int DEFAULT_MAX_RUNS = 50;

    /** Default: drop runs older than 90 days. */
    public static final int DEFAULT_MAX_AGE_DAYS = 90;

    /**
     * The {@code host-metrics.toml} sections neither writer of that file owns, and which therefore
     * have to survive a rewrite by either of them. One constant for both writers: this one owns
     * {@code [mean]}'s run keys, {@code HostMetricsFile} owns {@code [calibration]}, and each
     * preserves the other's table plus these.
     */
    public static final List<String> FOREIGN_SECTIONS = List.of("bootstrap", "lock", "fetch");

    /** {@link #FOREIGN_SECTIONS} plus {@code [calibration]}, the table the other writer owns. */
    private static final List<String> HARVEST_PRESERVES =
            Stream.concat(FOREIGN_SECTIONS.stream(), Stream.of("calibration")).toList();

    private static final Pattern KEY_EQ_NUM =
            Pattern.compile("(?m)^([a-zA-Z0-9._:/-]+)\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)\\s*$");

    /** Joins a module's key spelling and a class name into one class-wall key. */
    private static final char CLASS_KEY_SEPARATOR = '\0';

    private static final MetricsHarvest INSTANCE = new MetricsHarvest();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);
    private final Object startLock = new Object();

    private volatile int maxRuns = DEFAULT_MAX_RUNS;
    private volatile long maxAgeMillis = DEFAULT_MAX_AGE_DAYS * 86_400_000L;

    private MetricsHarvest() {}

    public static MetricsHarvest get() {
        return INSTANCE;
    }

    public void configure(int maxRunsPerProject, int maxAgeDays) {
        if (maxRunsPerProject > 0) this.maxRuns = maxRunsPerProject;
        if (maxAgeDays > 0) this.maxAgeMillis = maxAgeDays * 86_400_000L;
    }

    /** Request a harvest pass; starts the worker if idle, else sets re-run. */
    public void request() {
        synchronized (startLock) {
            if (running.get()) {
                rerun.set(true);
                return;
            }
            running.set(true);
        }
        Thread t = new Thread(this::loop, "jk-metrics-harvest");
        t.setDaemon(true);
        t.start();
    }

    /** True while a harvest pass (or coalesced re-run) is in flight. */
    public boolean busy() {
        return running.get() || rerun.get();
    }

    /**
     * Block until harvest is idle or {@code timeoutMs} elapses. Used so idle-boundary {@code
     * System.gc()} trails harvest allocations.
     */
    public void awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (busy() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void loop() {
        try {
            do {
                rerun.set(false);
                try {
                    runOnce(JkDirs.builds());
                } catch (Exception e) {
                    // never fail the product over harvest
                    Log.debug("loop: never fail the product over harvest", e);
                }
            } while (rerun.get());
        } finally {
            running.set(false);
            if (rerun.get()) {
                request();
            }
        }
    }

    /** Exposed for tests. Uses live {@link JkDirs#builds()}. */
    public void runOnce() throws IOException {
        runOnce(JkDirs.builds());
    }

    /** Harvest under an explicit builds root (tests). */
    public void runOnce(Path buildsRoot) throws IOException {
        long now = System.currentTimeMillis();
        Map<String, List<Double>> hostSamples = new LinkedHashMap<>();
        // Reap every home; harvest only preferred homes per checkout path (stale re-keyed ids).
        for (Path home : ProjectBuilds.listProjectHomes(buildsRoot)) {
            reapProject(home, now);
        }
        for (Path home : ProjectBuilds.listProjectHomesForMetrics(buildsRoot)) {
            Map<String, Agg> project = new LinkedHashMap<>();
            Map<String, Double> last = new LinkedHashMap<>();
            Map<String, Long> counts = new LinkedHashMap<>();
            Map<String, Agg> classWalls = new LinkedHashMap<>();
            for (Path run : ProjectBuilds.listRuns(home)) {
                Path metrics = run.resolve(ProjectBuilds.METRICS);
                if (!Files.isRegularFile(metrics)) continue;
                parseRunMetrics(metrics, hostSamples, project, last, counts, classWalls);
            }
            writeProjectMetrics(home.resolve(ProjectBuilds.PROJECT_METRICS), project, last, counts, classWalls);
        }
        writeHostMetrics(ProjectBuilds.hostMetricsFile(buildsRoot), hostSamples);
    }

    private void reapProject(Path home, long now) {
        List<Path> runs = new ArrayList<>(ProjectBuilds.listRuns(home));
        for (Path run : List.copyOf(runs)) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(run, BasicFileAttributes.class);
                long created = attrs.creationTime().toMillis();
                if (created <= 0) created = attrs.lastModifiedTime().toMillis();
                if (now - created > maxAgeMillis) {
                    PathUtil.deleteRecursively(run);
                    runs.remove(run);
                }
            } catch (IOException ignored) {
            }
        }
        while (runs.size() > maxRuns) {
            Path oldest = runs.get(runs.size() - 1);
            PathUtil.deleteRecursively(oldest);
            runs.remove(runs.size() - 1);
        }
    }

    private static void parseRunMetrics(
            Path metricsFile,
            Map<String, List<Double>> hostSamples,
            Map<String, Agg> project,
            Map<String, Double> last,
            Map<String, Long> counts,
            Map<String, Agg> classWalls) {
        try {
            String text = Files.readString(metricsFile, StandardCharsets.UTF_8);
            MetricsFile.scan(
                    text,
                    (section, key, v) -> {
                        if (v < 0) return;
                        // Drop cache-restore blips for heavy steps (native-image "32ms" SUCCESS) so
                        // they never enter [mean]/[last]/[count] and poison ETA.
                        if (isImplausibleHeavyWall(key, v)) return;
                        project.computeIfAbsent(key, k -> new Agg()).add(v);
                        // Newest-first listing → first write wins as last-success.
                        last.putIfAbsent(key, v);
                        counts.merge(key, 1L, Long::sum);
                        if (isHostKey(key)) {
                            hostSamples
                                    .computeIfAbsent(key, k -> new ArrayList<>())
                                    .add(v);
                        }
                    },
                    (dir, fqcn, ms) -> {
                        if (ms > 0)
                            classWalls
                                    .computeIfAbsent(classKey(dir, fqcn), k -> new Agg())
                                    .add(ms);
                    });
        } catch (Exception e) {
            Log.debug("parseRunMetrics: Exception ignored", e);
        }
    }

    private static String classKey(String moduleDir, String fqcn) {
        return moduleDir + CLASS_KEY_SEPARATOR + fqcn;
    }

    /**
     * Host-wide keys only (no per-module paths). Used for cold ETA on alien projects and
     * lock/fetch/probe rates.
     */
    static boolean isHostKey(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith("lock.") || key.startsWith("fetch.") || key.startsWith("probe.")) return true;
        if (key.startsWith("task.") && !key.contains("module.")) return true;
        if (key.startsWith("phase.") && !key.contains("module.")) return true;
        // Absolute host rate keys from continuous learning
        return key.endsWith("-per-method-ms")
                || key.endsWith("-per-source-ms")
                || key.endsWith("-suite-startup-ms")
                || key.endsWith("-ms-per-mib")
                || key.equals("native-image-floor-ms")
                || key.equals("package-jar-ms")
                || key.equals("package-assembly-ms")
                || key.equals("ms-per-weight");
    }

    /**
     * Heavy-step walls below these floors are action-cache restore noise, not real work. Must stay
     * aligned with journal {@code isImplausibleHeavyWall} and EffortWeights heavy floors.
     */
    static boolean isImplausibleHeavyWall(String key, double ms) {
        if (key == null || !(ms > 0)) return false;
        String k = key.toLowerCase(Locale.ROOT);
        if (k.contains(TaskNames.NATIVE_IMAGE) || k.contains(".phase.native.")) return ms < 5_000.0;
        if (k.contains(TaskNames.WRITE_IMAGE) || k.contains(".phase.image.")) return ms < 3_000.0;
        return false;
    }

    /**
     * Rows a project ledger holds at most, per family: per-class test walls across every module's
     * table, and the scalar rows. Past the cap the best-sampled rows stay (most runs sampled, then
     * key order), so a ledger is bounded by the project's shape, not by how many checkouts have
     * built it.
     */
    public static final int MAX_TEST_CLASS_ROWS = 2_000;

    public static final int MAX_OTHER_ROWS = 4_000;

    /**
     * Write the ledger: {@code [mean]}, {@code [last]} and {@code [count]} for every kept scalar
     * row, then one {@code [test-class."<dir>"]} table per module holding each class's trimmed
     * mean wall — one value per class, the module named once as the header, no {@code [last]} or
     * {@code [count]} copy: a class wall is a scheduling weight, and two more copies of two
     * thousand class names were most of a large ledger's bytes.
     */
    static void writeProjectMetrics(
            Path file,
            Map<String, Agg> means,
            Map<String, Double> last,
            Map<String, Long> counts,
            Map<String, Agg> classWalls)
            throws IOException {
        List<String> kept = keptRows(means, last, counts);
        StringBuilder sb = new StringBuilder();
        sb.append("# project-metrics — derived by MetricsHarvest (scalars only)\n");
        sb.append("[mean]\n");
        for (String key : kept) {
            Agg agg = means.get(key);
            if (agg != null)
                sb.append(key).append(" = ").append(fmt(agg.trimmedMean())).append('\n');
        }
        sb.append("\n[last]\n");
        for (String key : kept) {
            Double v = last.get(key);
            if (v != null) sb.append(key).append(" = ").append(fmt(v)).append('\n');
        }
        sb.append("\n[count]\n");
        for (String key : kept) {
            Long n = counts.get(key);
            if (n != null) sb.append(key).append(" = ").append(n).append('\n');
        }
        appendClassWallTables(sb, classWalls);
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, sb.toString());
    }

    /** The class tables close the file: every row after a module's header is one of its classes. */
    private static void appendClassWallTables(StringBuilder sb, Map<String, Agg> classWalls) {
        Map<String, Long> sampled = new LinkedHashMap<>();
        for (var e : classWalls.entrySet())
            sampled.put(e.getKey(), (long) e.getValue().vals.size());
        String open = null;
        for (String key : keptClassRows(sampled)) {
            int at = key.indexOf(CLASS_KEY_SEPARATOR);
            String module = key.substring(0, at);
            if (!module.equals(open)) {
                sb.append('\n').append(MetricsFile.testClassHeader(module)).append('\n');
                open = module;
            }
            Agg agg = classWalls.get(key);
            if (agg != null)
                sb.append(key.substring(at + 1))
                        .append(" = ")
                        .append(fmt(agg.trimmedMean()))
                        .append('\n');
        }
    }

    /** The union of keys across the three scalar sections, capped and returned in key order. */
    static List<String> keptRows(Map<String, Agg> means, Map<String, Double> last, Map<String, Long> counts) {
        TreeSet<String> all = new TreeSet<>(means.keySet());
        all.addAll(last.keySet());
        all.addAll(counts.keySet());
        List<String> kept = new ArrayList<>(cap(new ArrayList<>(all), counts, MAX_OTHER_ROWS));
        kept.sort(Comparator.naturalOrder());
        return kept;
    }

    /**
     * The class-wall keys ({@code <module>\0<fqcn>}) kept under the cap, grouped by module in
     * module order and class order: the best-sampled classes stay when there are too many.
     */
    static List<String> keptClassRows(Map<String, Long> sampled) {
        List<String> kept =
                new ArrayList<>(cap(new ArrayList<>(new TreeSet<>(sampled.keySet())), sampled, MAX_TEST_CLASS_ROWS));
        kept.sort(Comparator.naturalOrder());
        return kept;
    }

    private static List<String> cap(List<String> keys, Map<String, Long> counts, int max) {
        if (keys.size() <= max) return keys;
        List<String> ranked = new ArrayList<>(keys);
        ranked.sort(Comparator.<String>comparingLong(k -> -counts.getOrDefault(k, 0L))
                .thenComparing(Comparator.naturalOrder()));
        return ranked.subList(0, max);
    }

    private static void writeHostMetrics(Path file, Map<String, List<Double>> samples) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# host-metrics — derived by MetricsHarvest (scalars only)\n");
        // Preserve bootstrap/probe/lock/fetch/calibration + language buckets (jk optimize).
        String preserved = "";
        String byLanguage = "";
        // Continuous Calibration rates (native-image-ms-per-mib, compile-*-per-source-ms, …)
        // live under [mean] but are not run-harvested — keep them across harvest rewrites.
        Map<String, Double> continuousMean = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                String existing = Files.readString(file, StandardCharsets.UTF_8);
                for (String section : HARVEST_PRESERVES) {
                    int idx = existing.indexOf("\n[" + section + "]");
                    if (idx < 0) idx = existing.startsWith("[" + section + "]") ? 0 : -1;
                    if (idx >= 0) {
                        int end = existing.indexOf("\n[", idx + 2);
                        String block = end < 0 ? existing.substring(idx) : existing.substring(idx, end);
                        if (!block.isBlank()) preserved += "\n" + block.strip() + "\n";
                    }
                }
                continuousMean.putAll(parseContinuousMeanKeys(existing));
                // Keep [mean.by_language.*] tables — not harvested from runs.
                StringBuilder lang = new StringBuilder();
                boolean inLang = false;
                for (String line : existing.split("\n", -1)) {
                    String t = line.trim();
                    if (t.startsWith("[mean.by_language.")) {
                        inLang = true;
                        lang.append(line).append('\n');
                    } else if (inLang) {
                        if (t.startsWith("[")) inLang = false;
                        else lang.append(line).append('\n');
                    }
                }
                if (!lang.isEmpty()) byLanguage = "\n" + lang;
            } catch (IOException ignored) {
            }
        }
        sb.append("[mean]\n");
        samples.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sb.append(e.getKey())
                .append(" = ")
                .append(fmt(trimmedMean(e.getValue())))
                .append('\n'));
        // Continuous rates not present in this harvest pass (run keys always win on collision).
        continuousMean.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (samples.containsKey(e.getKey())) return;
            sb.append(e.getKey()).append(" = ").append(fmt(e.getValue())).append('\n');
        });
        if (!preserved.isBlank()) sb.append(preserved);
        if (!byLanguage.isBlank()) sb.append(byLanguage);
        Files.createDirectories(file.getParent());
        AtomicWrites.replace(file, sb.toString());
    }

    /**
     * Mean keys written by continuous host learning ({@code Calibration.learnFromSuccess}), not by
     * run harvest. Harvested keys look like {@code task.*} / {@code phase.*} / {@code module.*}.
     */
    public static boolean isContinuousMeanKey(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith("task.")
                || key.startsWith("phase.")
                || key.startsWith("module.")
                || key.startsWith("invocation.")
                || key.startsWith("workspace.")) {
            return false;
        }
        return true;
    }

    /** Parse continuous (non-run) scalars from every {@code [mean]} block in {@code existing}. */
    static Map<String, Double> parseContinuousMeanKeys(String existing) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (existing == null || existing.isBlank()) return out;
        boolean inMean = false;
        for (String line : existing.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("[")) {
                inMean = t.equals("[mean]");
                continue;
            }
            if (!inMean || t.isEmpty() || t.startsWith("#")) continue;
            Matcher m = KEY_EQ_NUM.matcher(t);
            if (!m.matches()) continue;
            String key = m.group(1);
            if (!isContinuousMeanKey(key)) continue;
            try {
                double v = Double.parseDouble(m.group(2));
                if (v > 0 && Double.isFinite(v)) out.put(key, v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Trimmed mean: drop top/bottom 10% when n ≥ 10. */
    public static double trimmedMean(List<Double> samples) {
        if (samples == null || samples.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(samples);
        s.sort(Comparator.naturalOrder());
        if (s.size() >= 10) {
            int drop = Math.max(1, s.size() / 10);
            s = s.subList(drop, s.size() - drop);
        }
        double sum = 0;
        for (double v : s) sum += v;
        return sum / s.size();
    }

    private static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static final class Agg {
        final List<Double> vals = new ArrayList<>();

        void add(double v) {
            vals.add(v);
        }

        double trimmedMean() {
            return MetricsHarvest.trimmedMean(vals);
        }
    }
}
