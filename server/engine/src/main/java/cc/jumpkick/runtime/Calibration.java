// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.jdk.GlobalDefaultJdk;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Machine-scoped cold ETA anchor for first builds before {@link StepTimings} has data (JK-1180).
 *
 * <p>On a fresh install, {@link #ensure} runs an offline multi-phase {@link HardwareProbe} (JVM
 * fork, javac, disk I/O, hash CPU, synthetic test-worker JVM) and stores <b>pessimistic</b> timings
 * under {@code ~/.jk/state/builds/calibration.toml}. Later real builds {@link #refine} the
 * {@code ms-per-weight} with EWMA. Optional {@link #withEngineColdStartMs} records client-measured
 * engine cold-start (CLI {@code jk engine calibrate}).
 */
public final class Calibration {

    /** EWMA recency for {@link #refine} — matches {@link StepTimings#DEFAULT_ALPHA}. */
    private static final double ALPHA = 0.4;

    /** Re-probe once a stored calibration is older than this (hardware/VM may have changed). */
    private static final long MAX_AGE_MILLIS = 60L * 86_400_000L; // ~60 days

    /** Schema for the richer multi-probe calibration file. */
    public static final int SCHEMA = 2;

    private static final AtomicReference<Calibration> MEMO = new AtomicReference<>();

    private final double msPerWeight;
    private final long jvmForkMs;
    private final long javacMs;
    private final long diskIoMs;
    private final long hashCpuMs;
    private final long junitForkMs;
    private final long junitRunMs;
    private final long engineColdStartMs;
    private final double loadAtCalibration;
    private final int cores;
    private final String jdk;
    private final String jkVersion;
    private final long updated;
    /**
     * True once a full multi-probe suite (or a real build refine) produced this anchor; false for
     * the legacy java/javac-only bootstrap.
     */
    private final boolean measured;

    private final int schema;

    private Calibration(
            double msPerWeight,
            long jvmForkMs,
            long javacMs,
            long diskIoMs,
            long hashCpuMs,
            long junitForkMs,
            long junitRunMs,
            long engineColdStartMs,
            double loadAtCalibration,
            int cores,
            String jdk,
            String jkVersion,
            long updated,
            boolean measured,
            int schema) {
        this.msPerWeight = msPerWeight;
        this.jvmForkMs = jvmForkMs;
        this.javacMs = javacMs;
        this.diskIoMs = diskIoMs;
        this.hashCpuMs = hashCpuMs;
        this.junitForkMs = junitForkMs;
        this.junitRunMs = junitRunMs;
        this.engineColdStartMs = engineColdStartMs;
        this.loadAtCalibration = loadAtCalibration;
        this.cores = cores;
        this.jdk = jdk;
        this.jkVersion = jkVersion;
        this.updated = updated;
        this.measured = measured;
        this.schema = schema;
    }

    /**
     * The effective cold-estimate anchor: the measured {@code ms-per-weight} when a fresh, valid
     * calibration exists, else {@link EffortWeights#MS_PER_WEIGHT}.
     */
    public double msPerWeight() {
        return msPerWeight > 0 ? msPerWeight : EffortWeights.MS_PER_WEIGHT;
    }

    public boolean present() {
        return msPerWeight > 0;
    }

    public boolean measured() {
        return measured;
    }

    public long jvmForkMs() {
        return jvmForkMs;
    }

    public long javacMs() {
        return javacMs;
    }

    public long diskIoMs() {
        return diskIoMs;
    }

    public long hashCpuMs() {
        return hashCpuMs;
    }

    public long junitForkMs() {
        return junitForkMs;
    }

    public long junitRunMs() {
        return junitRunMs;
    }

    public long engineColdStartMs() {
        return engineColdStartMs;
    }

    public double loadAtCalibration() {
        return loadAtCalibration;
    }

    public int cores() {
        return cores;
    }

    public String jdk() {
        return jdk;
    }

    public long updated() {
        return updated;
    }

    /** Test seam: construct an instance directly (bypasses the probe/IO). */
    static Calibration testInstance(double msPerWeight, boolean measured, String version, long updated) {
        return new Calibration(
                msPerWeight, 10, 20, 5, 8, 15, 40, 0, 1.5, 8, "jdk-x", version, updated, measured, SCHEMA);
    }

    /** Copy with client-measured engine cold-start wall (ms). */
    public Calibration withEngineColdStartMs(long coldMs) {
        long c = Math.max(0, coldMs);
        return new Calibration(
                msPerWeight,
                jvmForkMs,
                javacMs,
                diskIoMs,
                hashCpuMs,
                junitForkMs,
                junitRunMs,
                c,
                loadAtCalibration,
                cores,
                jdk,
                jkVersion,
                updated,
                measured,
                schema);
    }

    // --- load / ensure -------------------------------------------------------

    static Path file() {
        return JkDirs.builds().resolve("calibration.toml");
    }

    public static Calibration load() {
        Calibration cached = MEMO.get();
        if (cached != null) return cached;
        Calibration read = readOrAbsent();
        MEMO.set(read);
        return read;
    }

    /**
     * Return a usable calibration, running the multi-phase host probe when none is stored or the
     * stored one is stale. Never throws.
     */
    public static Calibration ensure(Path jdksDir) {
        return ensure(jdksDir, false);
    }

    /**
     * As {@link #ensure(Path)} with {@code force} — re-run the full suite even when a fresh
     * calibration already exists ({@code jk engine calibrate --force}).
     */
    public static Calibration ensure(Path jdksDir, boolean force) {
        Calibration current = load();
        // Skip when we already have a full multi-probe (schema ≥2) result, unless forced.
        if (!force && current.present() && current.measured && current.schema >= SCHEMA) return current;
        Calibration probed = probe(jdksDir);
        if (probed != null && probed.present()) {
            // Preserve engine cold-start if we already had one and this is an upgrade re-probe.
            if (current.engineColdStartMs > 0 && probed.engineColdStartMs == 0) {
                probed = probed.withEngineColdStartMs(current.engineColdStartMs);
            }
            persist(probed);
            MEMO.set(probed);
            return probed;
        }
        return current;
    }

    /**
     * Fold a completed build's measured throughput into the stored anchor. First real measurement
     * replaces an unmeasured probe; later ones EWMA-smooth.
     */
    public static void refine(double observedMsPerWeight, long nowMillis) {
        if (!(observedMsPerWeight > 0)) return;
        Calibration merged = foldRefine(load(), observedMsPerWeight, nowMillis);
        persist(merged);
        MEMO.set(merged);
    }

    /**
     * Persist {@code engineColdStartMs} into the current calibration (or a minimal present row).
     * Used by the CLI after timing a cold engine spawn.
     */
    public static Calibration recordEngineColdStart(long coldMs, long nowMillis) {
        long c = Math.max(0, coldMs);
        Calibration cur = load();
        Calibration next;
        if (cur.present()) {
            next = new Calibration(
                    cur.msPerWeight,
                    cur.jvmForkMs,
                    cur.javacMs,
                    cur.diskIoMs,
                    cur.hashCpuMs,
                    cur.junitForkMs,
                    cur.junitRunMs,
                    c,
                    cur.loadAtCalibration,
                    cur.cores,
                    cur.jdk,
                    JkVersion.VERSION,
                    nowMillis,
                    cur.measured,
                    Math.max(cur.schema, SCHEMA));
        } else {
            next = new Calibration(
                    EffortWeights.MS_PER_WEIGHT,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    c,
                    safeLoadAverage(),
                    Runtime.getRuntime().availableProcessors(),
                    null,
                    JkVersion.VERSION,
                    nowMillis,
                    false,
                    SCHEMA);
        }
        persist(next);
        MEMO.set(next);
        return next;
    }

    static Calibration foldRefine(Calibration prev, double observedMsPerWeight, long nowMillis) {
        double next = (prev.present() && prev.measured)
                ? ALPHA * observedMsPerWeight + (1 - ALPHA) * prev.msPerWeight
                : observedMsPerWeight;
        return new Calibration(
                next,
                prev.jvmForkMs,
                prev.javacMs,
                prev.diskIoMs,
                prev.hashCpuMs,
                prev.junitForkMs,
                prev.junitRunMs,
                prev.engineColdStartMs,
                prev.loadAtCalibration,
                prev.present() ? prev.cores : Runtime.getRuntime().availableProcessors(),
                prev.jdk,
                JkVersion.VERSION,
                nowMillis,
                true,
                Math.max(prev.schema, SCHEMA));
    }

    // --- the host probe ------------------------------------------------------

    private static Calibration probe(Path jdksDir) {
        try {
            Optional<Path> javaHome = resolveJavaHome(jdksDir);
            if (javaHome.isEmpty()) return null;
            Path home = javaHome.get();
            HardwareProbe.Result r = HardwareProbe.run(home);
            if (r == null || !(r.msPerWeight() > 0)) return null;
            String jdkId = JdkRegistry.identifierFor(home);
            return new Calibration(
                    r.msPerWeight(),
                    r.jvmForkMs(),
                    r.javacMs(),
                    r.diskIoMs(),
                    r.hashCpuMs(),
                    r.junitForkMs(),
                    r.junitRunMs(),
                    0,
                    Math.max(0.0, safeLoadAverage()),
                    Runtime.getRuntime().availableProcessors(),
                    jdkId,
                    JkVersion.VERSION,
                    System.currentTimeMillis(),
                    true, // full multi-probe suite
                    SCHEMA);
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static Optional<Path> resolveJavaHome(Path jdksDir) {
        try {
            JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
            var req = new JdkResolution.Request(
                    null,
                    cc.jumpkick.config.SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    null,
                    null,
                    0,
                    System::getenv);
            var r = JdkResolution.resolve(req, registry, GlobalDefaultJdk.current(), JdkLts.OFFLINE_LATEST_LTS);
            return r.jdk().map(cc.jumpkick.jdk.InstalledJdk::home);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Legacy helper kept for unit tests that pin the old (fork+javac)/weight formula.
     * Production uses {@link HardwareProbe#deriveMsPerWeight}.
     */
    static double deriveMsPerWeight(long forkMs, long javacMs) {
        return HardwareProbe.deriveMsPerWeight(forkMs, javacMs, 0, 0, 0, 0);
    }

    private static double safeLoadAverage() {
        try {
            return java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean stale(String version, long updated, long nowMillis) {
        if (!JkVersion.VERSION.equals(version)) return true;
        return updated > 0 && nowMillis - updated > MAX_AGE_MILLIS;
    }

    // --- IO ------------------------------------------------------------------

    private static Calibration readOrAbsent() {
        return readFrom(file(), System.currentTimeMillis());
    }

    static Calibration readFrom(Path f, long nowMillis) {
        Calibration absent = absent();
        try {
            if (!Files.isRegularFile(f)) return absent;
            TomlParseResult t = Toml.parse(f);
            double mpw = t.getDouble("ms-per-weight") != null ? t.getDouble("ms-per-weight") : 0;
            long updated = t.getLong("updated") != null ? t.getLong("updated") : 0L;
            String version = t.getString("jk-version");
            if (mpw <= 0) return absent;
            if (stale(version, updated, nowMillis)) return absent;
            int schema = t.getLong("schema") != null ? Math.toIntExact(t.getLong("schema")) : 1;
            return new Calibration(
                    mpw,
                    longOr(t, "jvm-fork-ms", 0),
                    longOr(t, "javac-ms", 0),
                    longOr(t, "disk-io-ms", 0),
                    longOr(t, "hash-cpu-ms", 0),
                    longOr(t, "junit-fork-ms", 0),
                    longOr(t, "junit-run-ms", 0),
                    longOr(t, "engine-cold-start-ms", 0),
                    t.getDouble("load-at-calibration") != null ? t.getDouble("load-at-calibration") : -1,
                    t.getLong("cores") != null ? Math.toIntExact(t.getLong("cores")) : 0,
                    t.getString("jdk"),
                    version,
                    updated,
                    t.getBoolean("measured") != null ? t.getBoolean("measured") : false,
                    schema);
        } catch (Exception e) {
            return absent;
        }
    }

    private static long longOr(TomlParseResult t, String key, long dflt) {
        Long v = t.getLong(key);
        return v != null ? v : dflt;
    }

    private static Calibration absent() {
        return new Calibration(0, 0, 0, 0, 0, 0, 0, 0, -1, 0, null, null, 0, false, 0);
    }

    private static void persist(Calibration c) {
        try {
            writeTo(file(), c);
        } catch (IOException | RuntimeException ignored) {
            // advisory
        }
    }

    static void writeTo(Path file, Calibration c) throws IOException {
        AtomicWrites.replace(file, c.render());
    }

    private String render() {
        return """
                # jk host calibration (JK-1180). Offline multi-probe; safe to delete (re-runs on next build).
                schema               = %d
                ms-per-weight        = %s
                jvm-fork-ms          = %d
                javac-ms             = %d
                disk-io-ms           = %d
                hash-cpu-ms          = %d
                junit-fork-ms        = %d
                junit-run-ms         = %d
                engine-cold-start-ms = %d
                load-at-calibration  = %s
                cores                = %d
                jdk                  = %s
                jk-version           = %s
                measured             = %s
                updated              = %d
                """
                .formatted(
                        schema <= 0 ? SCHEMA : schema,
                        round3(msPerWeight),
                        jvmForkMs,
                        javacMs,
                        diskIoMs,
                        hashCpuMs,
                        junitForkMs,
                        junitRunMs,
                        engineColdStartMs,
                        round3(loadAtCalibration),
                        cores,
                        quote(jdk == null ? "" : jdk),
                        quote(jkVersion == null ? "" : jkVersion),
                        measured,
                        updated);
    }

    /** Human-readable summary for {@code jk engine calibrate}. */
    public String summary() {
        if (!present()) return "uncalibrated (using static ms-per-weight=" + EffortWeights.MS_PER_WEIGHT + ")";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("ms-per-weight=%.1f (pessimistic host anchor)%n", msPerWeight()));
        if (jvmForkMs > 0) sb.append(String.format("  jvm-fork            %d ms%n", jvmForkMs));
        if (javacMs > 0) sb.append(String.format("  javac (micro)       %d ms%n", javacMs));
        if (diskIoMs > 0) sb.append(String.format("  disk io (4 MiB)     %d ms%n", diskIoMs));
        if (hashCpuMs > 0) sb.append(String.format("  sha-256 (8 MiB)     %d ms%n", hashCpuMs));
        if (junitForkMs > 0) sb.append(String.format("  test-worker fork    %d ms%n", junitForkMs));
        if (junitRunMs > 0) sb.append(String.format("  test-worker body    %d ms%n", junitRunMs));
        if (engineColdStartMs > 0) sb.append(String.format("  engine cold start   %d ms%n", engineColdStartMs));
        sb.append(String.format("  cores=%d  measured=%s  schema=%d%n", cores, measured, schema));
        return sb.toString().stripTrailing();
    }

    private static String quote(String s) {
        return cc.jumpkick.util.MinimalToml.quote(s);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    static void clearMemo() {
        MEMO.set(null);
    }
}
