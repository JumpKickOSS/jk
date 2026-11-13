// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.StampedMemo;
import cc.jumpkick.config.StampedMemo.FileStamp;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * Learned per-unit step rates ({@code floor + perUnit × count}) for progress-bar weights. For the
 * live cache root they are the host's continuous rates ({@link #fromAggregates}); cold steps fall
 * back to static estimates. Hermetic tests pass another root, whose rates are EWMA-smoothed in
 * {@code <root>/timings.toml} ({@link #record}).
 */
public final class StepTimings {

    /** EWMA alpha — recent-weighted but smoothed against one-off outliers. */
    public static final double DEFAULT_ALPHA = 0.4;

    /**
     * Synthetic module key for host-wide absolute averages (ms per unit), not residual rates.
     * Used for cold projects that have never timed a given step/method locally.
     */
    public static final String HOST_METHOD_MS_DIR = "__host__";

    /** Each ledger file as last read, while its stat holds: an engine that took over may have written it. */
    private static final StampedMemo<Path, FileStamp, StepTimings> MEMO = StampedMemo.bounded(16);

    /** A learned step rate plus when it was last refreshed (epoch millis; 0 = never stamped). */
    private record Entry(double perUnit, long updatedMillis) {}

    /** key = {@code dir step} → learned rate + timestamp. */
    private final Map<String, Entry> entries;

    private StepTimings(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** A hermetic root's timings file. */
    static Path file(Path root) {
        return root.toAbsolutePath().normalize().resolve("timings.toml");
    }

    /** Whether {@code rootOrCache} is the live cache root, whose rates are the host's. */
    private static boolean live(@Nullable Path rootOrCache) {
        return rootOrCache == null
                || rootOrCache
                        .toAbsolutePath()
                        .normalize()
                        .equals(JkDirs.cache().toAbsolutePath().normalize());
    }

    /**
     * Rates for {@code rootOrCache}: the host's for the live cache root, else the root's file, read
     * again once it moves. Missing/unreadable → empty (cold).
     */
    public static StepTimings load(Path rootOrCache) {
        if (live(rootOrCache)) return fromAggregates();
        Path f = file(rootOrCache);
        FileStamp stamp = FileStamp.of(f);
        return stamp == null ? readFile(f) : MEMO.get(f, stamp, () -> readFile(f));
    }

    /**
     * The host's continuous rates ({@code *-per-method-ms}, {@code *-per-source-ms}, …) from the
     * session aggregates, folded once per session-aggregate window rather than on every load.
     */
    static StepTimings fromAggregates() {
        return BuildMetrics.foldedForSession(StepTimings.class, StepTimings::fold);
    }

    private static StepTimings fold(AggregatedMetrics agg) {
        Map<String, Entry> m = new HashMap<>();
        long now = System.currentTimeMillis();
        for (var e : agg.hostMeanMap().entrySet()) {
            if (e.getValue() > 0) m.put(key(HOST_METHOD_MS_DIR, e.getKey()), new Entry(e.getValue(), now));
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
        if (samples == null || samples.isEmpty() || live(rootOrCache)) return;
        Path f = file(rootOrCache);
        try {
            FileLocks.withLock(ProjectBuilds.ledgerLock(f), () -> {
                Map<String, Entry> m = new HashMap<>(readFile(f).entries);
                for (Sample s : samples) {
                    // Near-zero (cache-hit / empty work) is ignored so rates stay about real work.
                    if (s.observedPerUnit() < 1e-6) continue;
                    String k = key(s.dir(), s.step());
                    Entry prev = m.get(k);
                    double next = prev == null
                            ? s.observedPerUnit()
                            : alpha * s.observedPerUnit() + (1 - alpha) * prev.perUnit();
                    m.put(k, new Entry(next, nowMillis));
                }
                write(f, m);
            });
        } catch (IOException | RuntimeException e) {
            // advisory store — never fail the build over it
            Log.debug("record: advisory store", e);
        }
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
        } catch (Exception e) {
            // unreadable/corrupt ledger → treat as cold
            Log.debug("readFile: unreadable/corrupt ledger → treat as cold", e);
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

    private static void write(Path file, Map<String, Entry> m) throws IOException {
        AtomicWrites.replace(file, render(m));
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
    public static void clearMemo() {
        MEMO.clear();
    }
}
