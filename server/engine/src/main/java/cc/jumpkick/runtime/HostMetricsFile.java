// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.host.Log;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.util.MinimalToml;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * A {@link Calibration} in the host's {@link HostStore}: the probe under {@link
 * HostStore#CALIBRATION} and the continuous rates under {@link HostStore#RATES}, each {@code key =
 * value} lines. {@code Calibration} owns the model; this class owns its sections' grammar.
 */
final class HostMetricsFile {

    private HostMetricsFile() {}

    /** The host's store. */
    static Path file() {
        return HostStore.defaultFile();
    }

    static Calibration readOrAbsent(long nowMillis) {
        return readFrom(file(), nowMillis);
    }

    static Calibration readFrom(Path f, long nowMillis) {
        Calibration absent = Calibration.absent();
        try {
            List<String> calLines = HostStore.section(f, HostStore.CALIBRATION);
            List<String> rateLines = HostStore.section(f, HostStore.RATES);
            if (calLines.isEmpty() && rateLines.isEmpty()) return absent;
            TomlParseResult t = Toml.parse("[calibration]\n" + String.join("\n", calLines) + "\n[mean]\n"
                    + String.join("\n", rateLines) + "\n");
            TomlTable cal = t.getTable("calibration") != null ? t.getTable("calibration") : t;
            double mpw = numberOr(cal, "ms-per-weight", 0);
            long updated = cal.getLong("updated") != null ? cal.getLong("updated") : 0L;
            String version = cal.getString("jk-version");
            HostLearnedRates learned = new HostLearnedRates();
            // The continuous rates (native-image-ms-per-mib, compile-*-per-source-ms, …), each a
            // single-sample learned prior.
            if (t.getTable("mean") != null) {
                TomlTable mean = t.getTable("mean");
                Map<String, List<Double>> rings = new LinkedHashMap<>(learned.samples());
                for (String key : mean.keySet()) {
                    Object v = mean.get(key);
                    if (v instanceof Number n && n.doubleValue() > 0) {
                        rings.putIfAbsent(key, List.of(n.doubleValue()));
                    }
                }
                if (!rings.isEmpty()) learned = new HostLearnedRates(rings);
            }
            if (mpw <= 0 && learned.isEmpty()) return absent;
            if (mpw <= 0) mpw = EffortWeights.MS_PER_WEIGHT;
            if (Calibration.stale(version, updated, nowMillis) && learned.isEmpty()) return absent;
            int schema = cal.getLong("schema") != null ? Math.toIntExact(cal.getLong("schema")) : 1;
            // A file at another schema is another jk's; ignoring it costs one re-probe, reading it
            // would mint a second reader.
            if (schema != Calibration.SCHEMA) return absent;
            long probeSuite = longOr(cal, "probe-test-suite-startup-ms", 0);
            long probeMethod = longOr(cal, "probe-test-method-ms", 0);
            long probeCompile = longOr(cal, "probe-compile-per-source-ms", 0);
            boolean measuredFlag = cal.getBoolean("measured") != null && cal.getBoolean("measured");
            if (probeSuite <= 0 || probeMethod <= 0 || probeCompile <= 0) {
                long jFork = longOr(cal, "junit-fork-ms", 0);
                long jRun = longOr(cal, "junit-run-ms", 0);
                long jPlat = longOr(cal, "junit-platform-ms", 0);
                long jvm = longOr(cal, "jvm-fork-ms", 0);
                long javac = longOr(cal, "javac-ms", 0);
                boolean jUsed = cal.getBoolean("junit-platform-used") != null && cal.getBoolean("junit-platform-used");
                if (probeSuite <= 0) probeSuite = Calibration.deriveSuiteStartup(jUsed, jPlat, jFork, jvm);
                if (probeMethod <= 0) probeMethod = Calibration.deriveMethodMs(jRun, jUsed, jPlat);
                if (probeCompile <= 0 && javac > 0) {
                    probeCompile = Math.max(1, Math.round(javac / (double) Math.max(1, HardwareProbe.JAVAC_SOURCES)));
                }
            }
            return Calibration.builder()
                    .msPerWeight(mpw)
                    .jvmForkMs(longOr(cal, "jvm-fork-ms", 0))
                    .javacMs(longOr(cal, "javac-ms", 0))
                    .diskIoMs(longOr(cal, "disk-io-ms", 0))
                    .hashCpuMs(longOr(cal, "hash-cpu-ms", 0))
                    .junitForkMs(longOr(cal, "junit-fork-ms", 0))
                    .junitRunMs(longOr(cal, "junit-run-ms", 0))
                    .junitPlatformMs(longOr(cal, "junit-platform-ms", 0))
                    .resolveMs(longOr(cal, "resolve-ms", 0))
                    .engineColdStartMs(longOr(cal, "engine-cold-start-ms", 0))
                    .loadAtCalibration(
                            cal.getDouble("load-at-calibration") != null ? cal.getDouble("load-at-calibration") : -1)
                    .cores(cal.getLong("cores") != null ? Math.toIntExact(cal.getLong("cores")) : 0)
                    .jdk(cal.getString("jdk"))
                    .jkVersion(version)
                    .updated(updated)
                    .measured(measuredFlag)
                    .junitPlatformUsed(
                            cal.getBoolean("junit-platform-used") != null && cal.getBoolean("junit-platform-used"))
                    .resolveUsed(cal.getBoolean("resolve-used") != null && cal.getBoolean("resolve-used"))
                    .schema(schema)
                    .probeTestSuiteStartupMs(probeSuite)
                    .probeTestMethodMs(probeMethod)
                    .probeCompilePerSourceMs(probeCompile)
                    .learned(learned)
                    .build();
        } catch (Exception e) {
            return absent;
        }
    }

    private static long longOr(TomlTable t, String key, long dflt) {
        Long v = t.getLong(key);
        return v != null ? v : dflt;
    }

    /** tomlj is type-strict: bare integers are Long, so {@code getDouble} throws. */
    private static double numberOr(TomlTable t, String key, double dflt) {
        if (t == null || key == null) return dflt;
        try {
            Double d = t.getDouble(key);
            if (d != null) return d;
        } catch (RuntimeException e) {
            Log.debug("numberOr: RuntimeException ignored", e);
        }
        try {
            Long l = t.getLong(key);
            if (l != null) return l.doubleValue();
        } catch (RuntimeException e) {
            Log.debug("numberOr: RuntimeException ignored", e);
        }
        return dflt;
    }

    static void writeTo(Path file, Calibration c) {
        // The rates replace what this calibration learned and keep the others; the probe section is
        // this calibration's whole.
        if (c.learned() != null && !c.learned().isEmpty()) {
            Map<String, String> fresh = new LinkedHashMap<>();
            for (var e : c.learned().samples().entrySet()) {
                double m = HostLearnedRates.trimmedMean(e.getValue());
                if (m > 0) fresh.put(e.getKey(), e.getKey() + " = " + round3(m));
            }
            HostStore.update(file, HostStore.RATES, lines -> {
                Map<String, String> all = new LinkedHashMap<>();
                for (String line : lines) {
                    int eq = line.indexOf('=');
                    if (eq > 0) all.put(line.substring(0, eq).strip(), line);
                }
                all.putAll(fresh);
                return List.copyOf(all.values());
            });
        }
        HostStore.update(
                file,
                HostStore.CALIBRATION,
                lines -> renderCalibrationSection(c).lines().skip(1).toList());
    }

    private static String renderCalibrationSection(Calibration c) {
        return """
                [calibration]
                schema               = %d
                ms-per-weight        = %s
                jvm-fork-ms          = %d
                javac-ms             = %d
                disk-io-ms           = %d
                hash-cpu-ms          = %d
                junit-fork-ms        = %d
                junit-run-ms         = %d
                junit-platform-ms    = %d
                resolve-ms           = %d
                engine-cold-start-ms = %d
                probe-test-suite-startup-ms = %d
                probe-test-method-ms        = %d
                probe-compile-per-source-ms = %d
                load-at-calibration  = %s
                cores                = %d
                jdk                  = %s
                jk-version           = %s
                measured             = %s
                junit-platform-used  = %s
                resolve-used         = %s
                updated              = %d
                """.formatted(
                        Calibration.SCHEMA,
                        round3(c.msPerWeightRaw()),
                        c.jvmForkMs(),
                        c.javacMs(),
                        c.diskIoMs(),
                        c.hashCpuMs(),
                        c.junitForkMs(),
                        c.junitRunMs(),
                        c.junitPlatformMs(),
                        c.resolveMs(),
                        c.engineColdStartMs(),
                        c.probeTestSuiteStartupMs(),
                        c.probeTestMethodMs(),
                        c.probeCompilePerSourceMs(),
                        round3(c.loadAtCalibration()),
                        c.cores(),
                        MinimalToml.quote(c.jdk() == null ? "" : c.jdk()),
                        MinimalToml.quote(c.jkVersion() == null ? "" : c.jkVersion()),
                        c.measured(),
                        c.junitPlatformUsed(),
                        c.resolveUsed(),
                        c.updated());
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
