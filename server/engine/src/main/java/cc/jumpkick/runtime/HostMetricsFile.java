// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.host.Log;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.MinimalToml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The {@code host-metrics.toml} grammar: where the file lives, how a {@link Calibration} is read
 * out of it, and the section-preserving merge that writes one back without clobbering what the
 * other writers ({@code MetricsHarvest} scalars, lock/fetch timings, {@code jk optimize} language
 * buckets) put there. {@code Calibration} owns the model; this class owns the file format.
 */
final class HostMetricsFile {

    private HostMetricsFile() {}

    /** Host metrics file (probe + continuous means): {@code host-metrics.toml}. */
    static Path file() {
        return JkDirs.builds().resolve("host-metrics.toml");
    }

    static Calibration readOrAbsent(long nowMillis) {
        return readFrom(file(), nowMillis);
    }

    static Calibration readFrom(Path f, long nowMillis) {
        Calibration absent = Calibration.absent();
        try {
            if (!Files.isRegularFile(f)) return absent;
            TomlParseResult t = Toml.parse(f);
            // Prefer [calibration] table in host-metrics.toml; fall back to root keys.
            TomlTable cal = t.getTable("calibration") != null ? t.getTable("calibration") : t;
            double mpw = numberOr(cal, "ms-per-weight", 0);
            long updated = cal.getLong("updated") != null ? cal.getLong("updated") : 0L;
            String version = cal.getString("jk-version");
            HostLearnedRates learned = HostLearnedRates.readFrom(t);
            // Fold continuous [mean] scalars (native-image-ms-per-mib, compile-*-per-source-ms, …)
            // as single-sample learned priors. Skip run-harvest keys (task.*/phase.*/module.*).
            if (t.getTable("mean") != null) {
                TomlTable mean = t.getTable("mean");
                Map<String, List<Double>> rings = new LinkedHashMap<>(learned.samples());
                for (String key : mean.keySet()) {
                    if (!MetricsHarvest.isContinuousMeanKey(key)) continue;
                    Object v = mean.get(key);
                    if (v instanceof Number n && n.doubleValue() > 0) {
                        rings.putIfAbsent(key, List.of(n.doubleValue()));
                    }
                }
                if (!rings.isEmpty()) learned = new HostLearnedRates(rings);
            }
            // Language buckets from jk optimize: mean.by_language.<lang>.compile_per_source_ms
            // seeds cold compile priors when continuous harvest has not yet measured that language.
            learned = foldLanguageBuckets(t, learned);
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

    /**
     * Fold {@code [mean.by_language.<lang>].compile_per_source_ms} into HostLearnedRates compile
     * keys when continuous means are still cold.
     */
    static HostLearnedRates foldLanguageBuckets(TomlParseResult t, HostLearnedRates learned) {
        if (t == null) return learned == null ? new HostLearnedRates() : learned;
        Map<String, List<Double>> rings = new LinkedHashMap<>(learned == null ? Map.of() : learned.samples());
        foldLang(t, "java", HostLearnedRates.COMPILE_JAVA_PER_SOURCE_MS, rings);
        foldLang(t, "kotlin", HostLearnedRates.COMPILE_KOTLIN_PER_SOURCE_MS, rings);
        foldLang(t, "groovy", HostLearnedRates.COMPILE_GROOVY_PER_SOURCE_MS, rings);
        return rings.isEmpty() ? (learned == null ? new HostLearnedRates() : learned) : new HostLearnedRates(rings);
    }

    private static void foldLang(TomlParseResult t, String lang, String rateKey, Map<String, List<Double>> rings) {
        if (rings.containsKey(rateKey)) return;
        // Nested table [mean.by_language.<lang>] — prefer dotted path (tomlj), then table walk.
        // Reads are type-tolerant per key: a mistyped value skips this bucket only, never the
        // whole calibration (readFrom's blanket catch would otherwise return absent).
        double ms = numberOr(t, "mean.by_language." + lang + ".compile_per_source_ms", 0);
        if (ms <= 0) {
            try {
                TomlTable mean = t.getTable("mean");
                TomlTable byLang = mean != null ? mean.getTable("by_language") : null;
                TomlTable tbl = byLang != null ? byLang.getTable(lang) : null;
                if (tbl != null) ms = numberOr(tbl, "compile_per_source_ms", 0);
            } catch (RuntimeException e) {
                Log.debug("foldLang: RuntimeException ignored", e);
            }
        }
        // Sanity: reject implausible compile_per_source_ms (must be 1–500).
        if (!(ms >= 1 && ms <= 500)) return;
        rings.put(rateKey, List.of(ms));
    }

    static void writeTo(Path file, Calibration c) throws IOException {
        // Merge [calibration] into host-metrics.toml; carry [lock], [fetch], [bootstrap] and the
        // language buckets verbatim, and fold this calibration's learned rates into the ONE [mean].
        // [mean] is co-owned: MetricsHarvest owns the run keys (task.*/phase.*/module.*/...), this
        // writer owns the continuous rates. Appending a second [mean] header instead of merging
        // makes the file illegal TOML and leaves the two readers disagreeing about which copy wins
        // (tomlj takes the first, MetricsHarvest.parseContinuousMeanKeys takes the last), so a
        // freshly written trimmed mean is discarded on the way back in.
        StringBuilder out = new StringBuilder();
        out.append("# host-metrics — probe + continuous means\n");
        // key -> verbatim line, so a carried-forward value never churns its formatting.
        Map<String, String> harvestKeys = new LinkedHashMap<>();
        Map<String, String> continuousKeys = new LinkedHashMap<>();
        StringBuilder foreign = new StringBuilder();
        if (Files.isRegularFile(file)) {
            try {
                String existing = Files.readString(file);
                splitMeanLines(existing, harvestKeys, continuousKeys);
                // Keep every section this writer does not own. The list is MetricsHarvest's, not
                // a second copy of it — [calibration] is absent because this writer regenerates it
                // below, and that is the only difference between the two writers' views.
                for (String section : MetricsHarvest.FOREIGN_SECTIONS) {
                    int idx = existing.indexOf("\n[" + section + "]");
                    if (idx < 0) idx = existing.startsWith("[" + section + "]") ? 0 : -1;
                    if (idx >= 0) {
                        int end = existing.indexOf("\n[", idx + 2);
                        String block = end < 0 ? existing.substring(idx) : existing.substring(idx, end);
                        if (!block.isBlank()) foreign.append(block.strip()).append('\n');
                    }
                }
                // Preserve mean.by_language.* tables written by jk optimize.
                foreign.append(extractByLanguageBlocks(existing));
            } catch (IOException ignored) {
            }
        }
        // Learned rates as scalar means under [mean] (no sample rings) — freshest value wins.
        if (c.learned() != null && !c.learned().isEmpty()) {
            for (var e : c.learned().samples().entrySet()) {
                double m = HostLearnedRates.trimmedMean(e.getValue());
                if (m > 0) continuousKeys.put(e.getKey(), e.getKey() + " = " + round3(m));
            }
        }
        out.append("\n[mean]\n");
        for (String line : harvestKeys.values()) out.append(line).append('\n');
        for (String line : continuousKeys.values()) out.append(line).append('\n');
        if (!foreign.isEmpty()) out.append('\n').append(foreign);
        out.append('\n').append(renderCalibrationSection(c));
        FileLocks.withLock(ProjectBuilds.ledgerLock(file), () -> AtomicWrites.replace(file, out.toString()));
    }

    /**
     * Split every {@code [mean]} scalar in {@code existing} into the run-harvest keys and the
     * continuous-rate keys, each mapped to its verbatim line. Sub-tables ({@code
     * [mean.by_language.*]}) are not {@code [mean]} and travel via {@link #extractByLanguageBlocks}.
     * The last occurrence of a key wins.
     */
    static void splitMeanLines(String existing, Map<String, String> harvest, Map<String, String> continuous) {
        if (existing == null || existing.isBlank()) return;
        boolean inMean = false;
        for (String raw : existing.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                inMean = line.equals("[mean]");
                continue;
            }
            if (!inMean || line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            if (key.isEmpty()) continue;
            (MetricsHarvest.isContinuousMeanKey(key) ? continuous : harvest).put(key, line);
        }
    }

    /** Extract contiguous {@code [mean.by_language.*]} tables from an existing host-metrics file. */
    static String extractByLanguageBlocks(String existing) {
        if (existing == null || existing.isBlank()) return "";
        StringBuilder lang = new StringBuilder();
        boolean in = false;
        for (String line : existing.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("[mean.by_language.")) {
                in = true;
                lang.append(line).append('\n');
                continue;
            }
            if (in) {
                if (t.startsWith("[")) {
                    in = false;
                } else {
                    lang.append(line).append('\n');
                }
            }
        }
        return lang.isEmpty() ? "" : "\n" + lang;
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
