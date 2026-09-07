// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlValues;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Learned per-unit step rates ({@code floor + perUnit × count}) for progress-bar weights. Rates
 * are EWMA-smoothed in {@code ~/.jk/state/builds/timings.toml} (beside {@code calibration.toml} /
 * {@code metrics.json}) so they survive cache GC and {@code jk clean}. Cold steps fall back to
 * static estimates. Reads are memoized per store file; {@link #record} is a single load-update-write
 * at build end.
 *
 * <p>Callers hand this the cache root and {@link #file(Path)} remaps it to the state ledger, so
 * the numbers survive {@code jk clean}. Hermetic tests may pass a non-default root; then the file
 * is {@code <root>/timings.toml}.
 */
public final class StepTimings {

    /** EWMA alpha — recent-weighted but smoothed against one-off outliers. */
    public static final double DEFAULT_ALPHA = 0.4;

    /**
     * Synthetic module key for host-wide absolute averages (ms per unit), not residual rates.
     * Used for cold projects that have never timed a given step/method locally.
     */
    public static final String HOST_METHOD_MS_DIR = "__host__";

    private static final ConcurrentHashMap<Path, StepTimings> MEMO = new ConcurrentHashMap<>();

    /** A learned step rate plus when it was last refreshed (epoch millis; 0 = never stamped). */
    private record Entry(double perUnit, long updatedMillis) {}

    /** key = {@code dir step} → learned rate + timestamp. */
    private final Map<String, Entry> entries;

    private StepTimings(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /**
     * Production rates hydrate from harvested metrics.
     * Hermetic tests still use isolated roots via {@link #file(Path)}.
     */
    public static Path defaultFile() {
        return JkDirs.builds().resolve("timings.toml");
    }

    /**
     * Resolve the timings file for {@code rootOrCache}. Production callers pass the jk cache root
     * ({@link JkDirs#cache()}); that maps to {@link #defaultFile()} under state. A distinct root
     * (tests, isolated dirs) keeps {@code <root>/timings.toml}.
     */
    public static Path file(Path rootOrCache) {
        if (rootOrCache == null) return defaultFile();
        Path p = rootOrCache.toAbsolutePath().normalize();
        if (p.getFileName() != null && "timings.toml".equals(p.getFileName().toString())) {
            return p;
        }
        Path liveCache = JkDirs.cache().toAbsolutePath().normalize();
        if (p.equals(liveCache)) {
            return defaultFile();
        }
        // Hermetic / overridden roots: keep the ledger next to the test fixture.
        return p.resolve("timings.toml");
    }

    /**
     * Read-only ledger for {@code rootOrCache}, memoized for the process. Missing/unreadable → empty
     * (cold). See {@link #file(Path)}.
     */
    public static StepTimings load(Path rootOrCache) {
        Path f = file(rootOrCache);
        if (f.equals(defaultFile()) || isLiveBuildsTimings(f)) {
            return fromAggregates();
        }
        return MEMO.computeIfAbsent(f, StepTimings::readFile);
    }

    private static boolean isLiveBuildsTimings(Path f) {
        try {
            return f != null
                    && "timings.toml".equals(f.getFileName().toString())
                    && f.getParent() != null
                    && f.getParent().equals(JkDirs.builds().toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Hydrate per-unit rates from harvested project/host metrics. */
    static StepTimings fromAggregates() {
        // Same project-preferring aggregates as BuildMetrics (stale identity homes must not poison rates).
        AggregatedMetrics agg = BuildMetrics.aggregatesForSession();
        Map<String, Entry> m = new HashMap<>();
        long now = System.currentTimeMillis();
        for (var e : agg.meanMap().entrySet()) {
            String k = e.getKey();
            double v = e.getValue();
            if (!(v > 0)) continue;
            if (k.endsWith(".per-unit-ms") && k.startsWith("module.") && k.contains(".task.")) {
                String body = k.substring("module.".length(), k.length() - ".per-unit-ms".length());
                int taskAt = body.indexOf(".task.");
                if (taskAt > 0) {
                    String dir = body.substring(0, taskAt);
                    String task = body.substring(taskAt + ".task.".length());
                    m.put(key(dir, task), new Entry(v, now));
                }
            } else if (k.startsWith("task.") && k.endsWith(".per-unit-ms")) {
                String task = k.substring("task.".length(), k.length() - ".per-unit-ms".length());
                m.put(key(HOST_METHOD_MS_DIR, task), new Entry(v, now));
            } else if (k.endsWith("-per-method-ms") || k.endsWith("-per-source-ms")) {
                m.put(key(HOST_METHOD_MS_DIR, k), new Entry(v, now));
            }
        }
        for (var e : agg.hostMeanMap().entrySet()) {
            if (e.getValue() > 0) {
                m.putIfAbsent(key(HOST_METHOD_MS_DIR, e.getKey()), new Entry(e.getValue(), now));
            }
        }
        return new StepTimings(m);
    }

    /** True when nothing has been learned yet (cold) — the caller can't show a trustworthy ETA. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** True when any of {@code dirs} has a learned rate (countdown ETA is trustworthy). */
    public boolean hasTimingsFor(Collection<String> dirs) {
        for (String d : dirs) {
            String prefix = d + ' ';
            for (String k : entries.keySet()) {
                if (k.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /**
     * The learned per-unit weight for a module's step, or empty when unseen (cold → caller's
     * fallback).
     */
    public OptionalDouble perUnit(String dir, String step) {
        Entry e = entries.get(key(dir, step));
        return e == null ? OptionalDouble.empty() : OptionalDouble.of(e.perUnit());
    }

    /** Host-wide median per-unit rate for {@code step}, or empty when never recorded. */
    public OptionalDouble medianPerUnit(String step) {
        String suffix = ' ' + step;
        double[] rates = entries.entrySet().stream()
                .filter(e -> e.getKey().endsWith(suffix))
                // Exclude absolute-ms host keys from residual-rate medians.
                .filter(e -> !e.getKey().startsWith(HOST_METHOD_MS_DIR + ' '))
                .mapToDouble(e -> e.getValue().perUnit())
                .sorted()
                .toArray();
        if (rates.length == 0) return OptionalDouble.empty();
        int n = rates.length;
        return OptionalDouble.of(n % 2 == 1 ? rates[n / 2] : (rates[n / 2 - 1] + rates[n / 2]) / 2.0);
    }

    /**
     * Host-wide average milliseconds per successful test method (absolute, not residual weight).
     * Used when a module has no local run-tests rate yet but peers have trained the host prior.
     */
    public OptionalDouble hostAvgTestMethodMs() {
        Entry e = entries.get(key(HOST_METHOD_MS_DIR, "test-method-ms"));
        return e == null || e.perUnit() <= 0 ? OptionalDouble.empty() : OptionalDouble.of(e.perUnit());
    }

    /** Project-local median per-unit rate for {@code step} across {@code dirs}. */
    public OptionalDouble medianPerUnit(String step, Collection<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return OptionalDouble.empty();
        double[] rates = dirs.stream()
                .distinct()
                .map(d -> entries.get(key(d, step)))
                .filter(Objects::nonNull)
                .mapToDouble(Entry::perUnit)
                .sorted()
                .toArray();
        if (rates.length == 0) return OptionalDouble.empty();
        int n = rates.length;
        return OptionalDouble.of(n % 2 == 1 ? rates[n / 2] : (rates[n / 2 - 1] + rates[n / 2]) / 2.0);
    }

    /** A measured per-unit rate for one step of one module, to fold into the ledger. */
    public record Sample(String dir, String step, double observedPerUnit) {}

    /**
     * EWMA-fold samples into the ledger and persist (best-effort; advisory only).
     *
     * <p>near-zero residuals (cache-hit / skipped work) are ignored so they cannot poison
     * learned rates toward zero. Alpha defaults to {@link #DEFAULT_ALPHA}.
     */
    public static void record(Path rootOrCache, List<Sample> samples, double alpha, long nowMillis) {
        if (samples == null || samples.isEmpty()) return;
        Path f = file(rootOrCache);
        // Production: per-run metrics.toml + harvest own durable rates.
        if (f.equals(defaultFile()) || isLiveBuildsTimings(f)) {
            return;
        }
        Map<String, Entry> m = new HashMap<>(readFile(f).entries);
        for (Sample s : samples) {
            // Ignore negative and near-zero (cache-hit / empty work) so rates stay about real work.
            if (s.observedPerUnit() < 1e-6) continue;
            String k = key(s.dir(), s.step());
            Entry prev = m.get(k);
            double next =
                    prev == null ? s.observedPerUnit() : alpha * s.observedPerUnit() + (1 - alpha) * prev.perUnit();
            m.put(k, new Entry(next, nowMillis));
        }
        try {
            write(f, m);
            MEMO.remove(f); // next load in this process sees the update
        } catch (IOException | RuntimeException ignored) {
            // advisory store — never fail the build over it
        }
    }

    // --- GC -----------------------------------------------------------------

    /** Bounds for {@link #prune}: a byte ceiling and a max entry age. */
    public record Limits(long maxBytes, long maxAgeMillis) {
        static final long DEFAULT_MAX_MB = 100;
        static final long DEFAULT_MAX_AGE_DAYS = 730; // 2 years

        /** Env → user config → defaults (100 MB / 2 years). */
        public static Limits resolve(Path userConfig, Function<String, @Nullable String> env) {
            long mb = envLong(env, "JK_TIMINGS_MAX_SIZE_MB")
                    .orElseGet(() -> tomlLong(userConfig, "timings-max-size-mb").orElse(DEFAULT_MAX_MB));
            long days = envLong(env, "JK_TIMINGS_MAX_AGE_DAYS")
                    .orElseGet(
                            () -> tomlLong(userConfig, "timings-max-age-days").orElse(DEFAULT_MAX_AGE_DAYS));
            return new Limits(Math.max(0, mb) * 1024L * 1024L, Math.max(0, days) * 86_400_000L);
        }
    }

    /** What a {@link #prune} pass evicted. */
    public record PruneReport(int evictedByAge, int evictedBySize, int kept, long finalBytes) {
        static final PruneReport EMPTY = new PruneReport(0, 0, 0, 0);
    }

    /** Evict by age then by size cap; rewrites unless {@code dryRun}. */
    public static PruneReport prune(Path rootOrCache, Limits limits, long nowMillis, boolean dryRun) {
        Path file = file(rootOrCache);
        if (!Files.isRegularFile(file)) return PruneReport.EMPTY;
        Map<String, Entry> m = new HashMap<>(readFile(file).entries);
        int total = m.size();

        int byAge = 0;
        if (limits.maxAgeMillis() > 0) {
            var it = m.entrySet().iterator();
            while (it.hasNext()) {
                Entry e = it.next().getValue();
                if (e.updatedMillis() > 0 && nowMillis - e.updatedMillis() > limits.maxAgeMillis()) {
                    it.remove();
                    byAge++;
                }
            }
        }

        int bySize = 0;
        long bytes = renderedBytes(m);
        if (limits.maxBytes() > 0 && bytes > limits.maxBytes() && !m.isEmpty()) {
            long avg = Math.max(1, bytes / m.size());
            int keep = (int) Math.min(m.size(), Math.max(0, limits.maxBytes() / avg));
            if (keep < m.size()) {
                // Drop the oldest (smallest updatedMillis) first.
                List<Map.Entry<String, Entry>> sorted = new ArrayList<>(m.entrySet());
                sorted.sort(Comparator.comparingLong(en -> en.getValue().updatedMillis()));
                for (int i = 0; i < sorted.size() - keep; i++) {
                    m.remove(sorted.get(i).getKey());
                    bySize++;
                }
            }
        }

        if ((byAge > 0 || bySize > 0) && !dryRun) {
            try {
                if (m.isEmpty()) Files.deleteIfExists(file);
                else write(file, m);
                MEMO.remove(file);
            } catch (IOException | RuntimeException ignored) {
                // advisory — leave the file as-is on failure
            }
        }
        return new PruneReport(byAge, bySize, m.size(), renderedBytes(m));
    }

    // --- IO -----------------------------------------------------------------

    private static StepTimings readFile(Path f) {
        Map<String, Entry> m = new HashMap<>();
        try {
            if (Files.isRegularFile(f)) {
                TomlParseResult toml = Toml.parse(f);
                TomlArray arr = toml.getArray("timing");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        TomlTable e = arr.getTable(i);
                        String dir = e.getString("dir");
                        String step = e.getString("step");
                        Double pu = e.contains("per-unit-weight") ? e.getDouble("per-unit-weight") : null;
                        long updated =
                                e.contains("updated") && e.getLong("updated") != null ? e.getLong("updated") : 0L;
                        if (dir != null && step != null && pu != null && pu >= 0) {
                            m.put(key(dir, step), new Entry(pu, updated));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable/corrupt ledger → treat as cold
        }
        return new StepTimings(m);
    }

    private static String render(Map<String, Entry> m) {
        StringBuilder out = new StringBuilder(256);
        m.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            int sep = e.getKey().indexOf(' ');
            String dir = e.getKey().substring(0, sep);
            String step = e.getKey().substring(sep + 1);
            out.append("[[timing]]\n")
                    .append("dir             = ")
                    .append(quote(dir))
                    .append('\n')
                    .append("step           = ")
                    .append(quote(step))
                    .append('\n')
                    .append("per-unit-weight = ")
                    .append(round3(e.getValue().perUnit()))
                    .append('\n')
                    .append("updated         = ")
                    .append(e.getValue().updatedMillis())
                    .append('\n')
                    .append('\n');
        });
        return out.toString();
    }

    private static long renderedBytes(Map<String, Entry> m) {
        return render(m).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void write(Path file, Map<String, Entry> m) throws IOException {
        AtomicWrites.replace(file, render(m));
    }

    private static OptionalLong envLong(Function<String, @Nullable String> env, String name) {
        return toOptionalLong(EnvValues.longValue(env, name));
    }

    private static OptionalLong tomlLong(Path userConfig, String key) {
        // The [cache] table of the user-global ~/.jk/config.toml; missing/malformed → empty.
        Optional<Long> v = TomlValues.parse(userConfig)
                .map(toml -> toml.getTable("cache"))
                .flatMap(cache -> TomlValues.optLong(cache, key));
        return toOptionalLong(v);
    }

    private static OptionalLong toOptionalLong(Optional<Long> v) {
        return v.isPresent() ? OptionalLong.of(v.get()) : OptionalLong.empty();
    }

    private static String key(String dir, String step) {
        return dir + ' ' + step;
    }

    private static String quote(String s) {
        return MinimalToml.quote(s);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Test seam: drop the per-process load memo so a freshly-written file is re-read. */
    static void clearMemo() {
        MEMO.clear();
    }
}
