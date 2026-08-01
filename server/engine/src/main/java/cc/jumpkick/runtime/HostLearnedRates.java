// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;

/**
 * Continuous host-wide timing priors folded from successful builds into {@code calibration.toml}.
 * Each rate key retains a ring of samples; the published prior is a <strong>trimmed mean</strong>
 * (drop top/bottom 10% when n ≥ 10), matching {@link cc.jumpkick.cache.FetchTimings}.
 *
 * <p>Keys are absolute milliseconds (per unit or whole step), not residual weight rates.
 */
public final class HostLearnedRates {

    /** Cap samples per key so the file stays small. */
    public static final int MAX_SAMPLES = 80;

    /** Known rate keys written by success learning / used by cold ETA. */
    public static final String RUN_TESTS_PER_METHOD_MS = "run-tests-per-method-ms";

    public static final String RUN_TESTS_SUITE_STARTUP_MS = "run-tests-suite-startup-ms";
    public static final String COMPILE_JAVA_PER_SOURCE_MS = "compile-java-per-source-ms";
    public static final String COMPILE_TEST_PER_SOURCE_MS = "compile-test-per-source-ms";
    public static final String COMPILE_KOTLIN_PER_SOURCE_MS = "compile-kotlin-per-source-ms";
    public static final String COMPILE_GROOVY_PER_SOURCE_MS = "compile-groovy-per-source-ms";
    public static final String PACKAGE_JAR_MS = "package-jar-ms";
    public static final String PACKAGE_ASSEMBLY_MS = "package-assembly-ms";

    private final Map<String, List<Double>> samplesByKey;

    public HostLearnedRates() {
        this(Map.of());
    }

    public HostLearnedRates(Map<String, List<Double>> samplesByKey) {
        Map<String, List<Double>> copy = new LinkedHashMap<>();
        if (samplesByKey != null) {
            for (var e : samplesByKey.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
                List<Double> ring = new ArrayList<>();
                for (Double v : e.getValue()) {
                    if (v != null && v > 0 && Double.isFinite(v)) ring.add(v);
                }
                if (!ring.isEmpty()) copy.put(e.getKey(), List.copyOf(ring));
            }
        }
        this.samplesByKey = Map.copyOf(copy);
    }

    public boolean isEmpty() {
        return samplesByKey.isEmpty();
    }

    /** Trimmed-mean prior for {@code key}, or empty when no samples. */
    public OptionalDouble meanMs(String key) {
        if (key == null) return OptionalDouble.empty();
        List<Double> s = samplesByKey.get(key);
        if (s == null || s.isEmpty()) return OptionalDouble.empty();
        double m = trimmedMean(s);
        return m > 0 ? OptionalDouble.of(m) : OptionalDouble.empty();
    }

    public int sampleCount(String key) {
        List<Double> s = samplesByKey.get(key);
        return s == null ? 0 : s.size();
    }

    /** Immutable view of sample rings (for persist / tests). */
    public Map<String, List<Double>> samples() {
        return samplesByKey;
    }

    /**
     * Fold one observation. Non-positive / non-finite values are ignored. Outliers above
     * {@code maxSaneMs} are dropped so a hung suite cannot poison the host prior.
     */
    public HostLearnedRates withSample(String key, double ms, double maxSaneMs) {
        if (key == null || key.isBlank()) return this;
        if (!(ms > 0) || !Double.isFinite(ms)) return this;
        if (maxSaneMs > 0 && ms > maxSaneMs) return this;
        Map<String, List<Double>> next = new LinkedHashMap<>(samplesByKey);
        List<Double> ring = new ArrayList<>(next.getOrDefault(key, List.of()));
        ring.add(ms);
        while (ring.size() > MAX_SAMPLES) ring.remove(0);
        next.put(key, List.copyOf(ring));
        return new HostLearnedRates(next);
    }

    public HostLearnedRates withSamples(List<HostSample> samples) {
        if (samples == null || samples.isEmpty()) return this;
        HostLearnedRates cur = this;
        for (HostSample s : samples) {
            if (s == null) continue;
            cur = cur.withSample(s.key(), s.ms(), s.maxSaneMs());
        }
        return cur;
    }

    /**
     * One absolute-ms observation for a host rate key.
     *
     * @param maxSaneMs drop samples above this (0 = no cap)
     */
    public record HostSample(String key, double ms, double maxSaneMs) {
        public HostSample(String key, double ms) {
            this(key, ms, 0);
        }
    }

    /** Trimmed mean: drop lowest/highest 10% when n ≥ 10 (at least one each side). */
    public static double trimmedMean(List<Double> samples) {
        if (samples == null || samples.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int n = sorted.size();
        int trim = n >= 10 ? Math.max(1, n / 10) : 0;
        if (trim * 2 >= n) trim = 0;
        double sum = 0;
        int count = 0;
        for (int i = trim; i < n - trim; i++) {
            sum += sorted.get(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    /** Well-known rate keys (plus any extra discovered via keySet). */
    private static final List<String> KNOWN_KEYS = List.of(
            RUN_TESTS_PER_METHOD_MS,
            RUN_TESTS_SUITE_STARTUP_MS,
            COMPILE_JAVA_PER_SOURCE_MS,
            COMPILE_TEST_PER_SOURCE_MS,
            COMPILE_KOTLIN_PER_SOURCE_MS,
            COMPILE_GROOVY_PER_SOURCE_MS,
            PACKAGE_JAR_MS,
            PACKAGE_ASSEMBLY_MS);

    /** Parse {@code learned-<key> = [ … ]} arrays from a calibration TOML root. */
    public static HostLearnedRates readFrom(TomlParseResult t) {
        if (t == null) return new HostLearnedRates();
        Map<String, List<Double>> map = new LinkedHashMap<>();
        // tomlj keySet() may omit some dotted/hyphenated array keys — probe known keys first.
        for (String rateKey : KNOWN_KEYS) {
            readArray(t, "learned-" + rateKey, rateKey, map);
        }
        for (String key : t.keySet()) {
            if (key == null || !key.startsWith("learned-")) continue;
            String rateKey = key.substring("learned-".length());
            if (rateKey.isEmpty() || map.containsKey(rateKey)) continue;
            readArray(t, key, rateKey, map);
        }
        return new HostLearnedRates(map);
    }

    private static void readArray(TomlParseResult t, String tomlKey, String rateKey, Map<String, List<Double>> map) {
        TomlArray arr = t.getArray(tomlKey);
        if (arr == null) return;
        List<Double> ring = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            // tomlj stores bare integers as Long; getDouble throws on type mismatch.
            double d;
            try {
                Long l = arr.getLong(i);
                if (l != null) {
                    d = l.doubleValue();
                } else {
                    Double boxed = arr.getDouble(i);
                    if (boxed == null) continue;
                    d = boxed;
                }
            } catch (RuntimeException e) {
                try {
                    Double boxed = arr.getDouble(i);
                    if (boxed == null) continue;
                    d = boxed;
                } catch (RuntimeException e2) {
                    continue;
                }
            }
            if (d > 0 && Double.isFinite(d)) ring.add(d);
        }
        if (!ring.isEmpty()) map.put(rateKey, ring);
    }

    /** TOML lines for each ring (appended under bootstrap fields). */
    public String renderToml() {
        if (samplesByKey.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n# Continuous host priors from successful builds (trimmed-mean samples)\n");
        for (var e : samplesByKey.entrySet()) {
            sb.append("learned-").append(e.getKey()).append(" = [");
            List<Double> ring = e.getValue();
            for (int i = 0; i < ring.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatMs(ring.get(i)));
            }
            sb.append("]\n");
        }
        return sb.toString();
    }

    private static String formatMs(double v) {
        // Always write a decimal form so TOML parsers treat values as floats (tomlj getLong
        // vs getDouble is type-strict).
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
