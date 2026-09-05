// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildEnv;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * Machine-scoped cold ETA priors + continuous host learning).
 *
 * <p><b>Bootstrap:</b> {@link #ensure} runs a multi-phase {@link HardwareProbe} when no usable
 * {@code ~/.jk/state/builds/host-metrics.toml} exists (or on {@code --force}). Network probes
 * (JUnit jar fetch + resolve micro-GET) are <strong>on by default</strong>; opt out with global
 * {@code --offline}.
 *
 * <p><b>Continuous:</b> successful builds fold absolute step/unit walls into {@link
 * HostLearnedRates} (trimmed means). Those absolute rates win over cold baselines when present.
 *
 * <p><b>Cold ETA model:</b> product <em>baselines</em> (realistic unit-test / compile costs) ×
 * <em>host scale</em> (how this machine's probe compares to a reference laptop). Micro-probes
 * never become absolute method costs — empty {@code @Test}s run in ~5 ms and would under-shoot
 * real suites by 10×+. Prefer a mild over-estimate over a large under-estimate.
 */
public final class Calibration {

    /** EWMA recency for {@link #refine} — matches {@link StepTimings#DEFAULT_ALPHA}. */
    private static final double ALPHA = 0.4;

    /** Re-probe once a stored calibration is older than this (hardware/VM may have changed). */
    private static final long MAX_AGE_MILLIS = 60L * 86_400_000L; // ~60 days

    /**
     * Schema 4 = probe components + continuous {@code learned-*} sample rings. Schema 3 files
     * still load; missing learned rings are empty.
     */
    public static final int SCHEMA = 4;

    /** Floor/ceiling for <em>learned</em> absolute method samples (not cold baselines). */
    static final long METHOD_MS_FLOOR = 5;

    static final long METHOD_MS_CEIL = 200;
    static final long SUITE_STARTUP_MS_FLOOR = 50;

    // --- Product baselines (reference-host absolute guesses for real project work) -------------
    // These are NOT micro-probe residuals. Prefer slight over-estimate for cold explain.

    /**
     * Typical unit-test method wall on the reference host (AssertJ/temp-dir style, not empty).
     * Fit against the jk monorepo cold full rebuild (~2m wall for ~3.3k tests / 27 modules);
     * prior 125ms overshot ~3× when combined with serial cold test parallel.
     */
    static final long BASELINE_METHOD_MS = 45;

    /** Suite / worker JVM + JUnit Platform + classpath warm-up for one module test step. */
    static final long BASELINE_SUITE_STARTUP_MS = 280;

    /** javac-ish ms per source file including classpath / AP overhead (not micro-probe only). */
    static final long BASELINE_COMPILE_PER_SOURCE_MS = 18;

    /** package-jar fixed cost on the reference host. */
    static final long BASELINE_PACKAGE_JAR_MS = 90;

    /**
     * Reference-host cold Graal wall for a typical ~1–1.5 MiB-effective app when size is unknown.
     * Always × {@link #cpuScale()} via {@link #nativeImageMs()} — not used raw. Prefer {@link
     * NativeEffort} size model when classpath bytes are known. Dogfood at scale=1 is mid-30s.
     */
    static final long BASELINE_NATIVE_IMAGE_MS = 35_000;

    /** Cold OCI image build wall (Jib-style) on the reference host. */
    static final long BASELINE_OCI_IMAGE_MS = 30_000;

    /**
     * Uncalibrated / EffortWeights fallback constants — same product baselines (host scale = 1).
     * Kept as aliases so call sites and older comments stay readable.
     */
    static final long STATIC_METHOD_MS = BASELINE_METHOD_MS;

    static final long STATIC_SUITE_STARTUP_MS = BASELINE_SUITE_STARTUP_MS;
    static final long STATIC_COMPILE_PER_SOURCE_MS = BASELINE_COMPILE_PER_SOURCE_MS;
    static final long STATIC_PACKAGE_JAR_MS = BASELINE_PACKAGE_JAR_MS;

    // --- Reference probe anchors (mid-range developer laptop; scale = host / ref) --------------
    // Higher host/ref → slower host → longer cold ETA. Values are typical warm-max probe walls.

    static final long REF_JVM_FORK_MS = 40;
    static final long REF_JAVAC_MS = 300; // HardwareProbe.JAVAC_SOURCES micro sources
    static final long REF_DISK_IO_MS = 15;
    static final long REF_HASH_CPU_MS = 8;
    static final long REF_JUNIT_PLATFORM_MS = 250;

    /** Never assume this host is more than ~35% faster than the baseline product costs. */
    static final double HOST_SCALE_MIN = 0.65;

    /** Cap how much a slow probe inflates cold ETA (still prefer over- under-shoot). */
    static final double HOST_SCALE_MAX = 2.0;

    /**
     * Thin cold-path pad only. Learned / measured step walls do not use this. Kept at 1.0 after
     * monorepo cold-ETA overshoot (baselines carry the uncertainty, not a second pad).
     */
    static final double COLD_BIAS = 1.0;

    /**
     * Cap within-module test workers for cold ETA. Runtime may use more; cold forecasts allow
     * modest parallelism so test-heavy monorepos are not estimated as fully serial method walls.
     */
    static final int COLD_MAX_TEST_PARALLEL = 4;

    private static final AtomicReference<@Nullable Calibration> MEMO = new AtomicReference<>();

    private final double msPerWeight;
    private final long jvmForkMs;
    private final long javacMs;
    private final long diskIoMs;
    private final long hashCpuMs;
    private final long junitForkMs;
    private final long junitRunMs;
    private final long junitPlatformMs;
    private final long resolveMs;
    private final long engineColdStartMs;
    private final double loadAtCalibration;
    private final int cores;
    private final @Nullable String jdk;
    private final @Nullable String jkVersion;
    private final long updated;
    private final boolean measured;
    private final boolean junitPlatformUsed;
    private final boolean resolveUsed;
    private final int schema;
    /** Probe-derived absolute priors (0 = unset). */
    private final long probeTestSuiteStartupMs;

    private final long probeTestMethodMs;
    private final long probeCompilePerSourceMs;
    private final HostLearnedRates learned;

    @Builder(toBuilder = true, access = AccessLevel.PACKAGE)
    private Calibration(
            double msPerWeight,
            long jvmForkMs,
            long javacMs,
            long diskIoMs,
            long hashCpuMs,
            long junitForkMs,
            long junitRunMs,
            long junitPlatformMs,
            long resolveMs,
            long engineColdStartMs,
            double loadAtCalibration,
            int cores,
            @Nullable String jdk,
            @Nullable String jkVersion,
            long updated,
            boolean measured,
            boolean junitPlatformUsed,
            boolean resolveUsed,
            int schema,
            long probeTestSuiteStartupMs,
            long probeTestMethodMs,
            long probeCompilePerSourceMs,
            HostLearnedRates learned) {
        this.msPerWeight = msPerWeight;
        this.jvmForkMs = jvmForkMs;
        this.javacMs = javacMs;
        this.diskIoMs = diskIoMs;
        this.hashCpuMs = hashCpuMs;
        this.junitForkMs = junitForkMs;
        this.junitRunMs = junitRunMs;
        this.junitPlatformMs = junitPlatformMs;
        this.resolveMs = resolveMs;
        this.engineColdStartMs = engineColdStartMs;
        this.loadAtCalibration = loadAtCalibration;
        this.cores = cores;
        this.jdk = jdk;
        this.jkVersion = jkVersion;
        this.updated = updated;
        this.measured = measured;
        this.junitPlatformUsed = junitPlatformUsed;
        this.resolveUsed = resolveUsed;
        this.schema = schema;
        this.probeTestSuiteStartupMs = probeTestSuiteStartupMs;
        this.probeTestMethodMs = probeTestMethodMs;
        this.probeCompilePerSourceMs = probeCompilePerSourceMs;
        this.learned = learned == null ? new HostLearnedRates() : learned;
    }

    public double msPerWeight() {
        return msPerWeight > 0 ? msPerWeight : EffortWeights.MS_PER_WEIGHT;
    }

    /** The stored anchor without the uncalibrated fallback — what {@link HostMetricsFile} writes. */
    double msPerWeightRaw() {
        return msPerWeight;
    }

    public boolean present() {
        return msPerWeight > 0;
    }

    /** True when bootstrap probe or continuous learning gives a usable host prior. */
    public boolean hasColdPriors() {
        return present()
                || !learned.isEmpty()
                || probeTestMethodMs > 0
                || probeTestSuiteStartupMs > 0
                || probeCompilePerSourceMs > 0;
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

    public long junitPlatformMs() {
        return junitPlatformMs;
    }

    public long resolveMs() {
        return resolveMs;
    }

    public boolean junitPlatformUsed() {
        return junitPlatformUsed;
    }

    public boolean resolveUsed() {
        return resolveUsed;
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

    public String jkVersion() {
        return jkVersion;
    }

    public int schema() {
        return schema;
    }

    public long updated() {
        return updated;
    }

    public HostLearnedRates learned() {
        return learned;
    }

    public long probeTestSuiteStartupMs() {
        return probeTestSuiteStartupMs;
    }

    public long probeTestMethodMs() {
        return probeTestMethodMs;
    }

    public long probeCompilePerSourceMs() {
        return probeCompilePerSourceMs;
    }

    // --- cold priors for ETA -------------------------------------------------

    /**
     * Effective suite startup ms for cold ETA: learned absolute → baseline × fork scale.
     * Probe residual startup is diagnostic only (empty-suite launcher ≠ real module classpath).
     */
    public long testSuiteStartupMs() {
        OptionalDouble learned = this.learned.meanMs(HostLearnedRates.RUN_TESTS_SUITE_STARTUP_MS);
        if (learned.isPresent()) {
            return Math.max(SUITE_STARTUP_MS_FLOOR, Math.round(learned.getAsDouble()));
        }
        return scaleBaseline(BASELINE_SUITE_STARTUP_MS, forkScale());
    }

    /**
     * Effective ms/method for cold ETA: learned absolute → baseline × CPU scale.
     * Never uses empty-probe residual as absolute cost (that under-shoots real tests badly).
     */
    public long testMethodMs() {
        OptionalDouble learned = this.learned.meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS);
        if (learned.isPresent()) {
            return clamp(Math.round(learned.getAsDouble()), METHOD_MS_FLOOR, METHOD_MS_CEIL * 5);
        }
        return scaleBaseline(BASELINE_METHOD_MS, cpuScale());
    }

    /**
     * Effective compile ms/source: learned absolute → baseline × CPU scale. Micro-javac residual is
     * a host-speed signal only, not the product cost for monorepo sources.
     */
    public long compilePerSourceMs(String step) {
        String key =
                switch (step == null ? "" : step) {
                    case TaskNames.COMPILE_KOTLIN -> HostLearnedRates.COMPILE_KOTLIN_PER_SOURCE_MS;
                    case TaskNames.COMPILE_GROOVY -> HostLearnedRates.COMPILE_GROOVY_PER_SOURCE_MS;
                    case TaskNames.COMPILE_TEST -> HostLearnedRates.COMPILE_TEST_PER_SOURCE_MS;
                    default -> HostLearnedRates.COMPILE_JAVA_PER_SOURCE_MS;
                };
        OptionalDouble learned = this.learned.meanMs(key);
        if (learned.isPresent()) return Math.max(1, Math.round(learned.getAsDouble()));
        if (!HostLearnedRates.COMPILE_JAVA_PER_SOURCE_MS.equals(key)) {
            OptionalDouble java = this.learned.meanMs(HostLearnedRates.COMPILE_JAVA_PER_SOURCE_MS);
            if (java.isPresent()) return Math.max(1, Math.round(java.getAsDouble()));
        }
        return scaleBaseline(BASELINE_COMPILE_PER_SOURCE_MS, cpuScale());
    }

    public long packageJarMs() {
        OptionalDouble learned = this.learned.meanMs(HostLearnedRates.PACKAGE_JAR_MS);
        if (learned.isPresent()) return Math.max(1, Math.round(learned.getAsDouble()));
        return scaleBaseline(BASELINE_PACKAGE_JAR_MS, ioScale());
    }

    public long packageAssemblyMs() {
        OptionalDouble learned = this.learned.meanMs(HostLearnedRates.PACKAGE_ASSEMBLY_MS);
        if (learned.isPresent()) return Math.max(1, Math.round(learned.getAsDouble()));
        return scaleBaseline(EffortWeights.ASSEMBLY_RUN * (long) EffortWeights.MS_PER_WEIGHT, ioScale());
    }

    /**
     * Cold native-image wall when input size is unknown. Prefer {@link NativeEffort} when classpath
     * bytes are known. Host-learned size rates (floor/slope at 1 MiB) win; else reference product
     * baseline × full {@link #cpuScale()} (install never probes Graal — this is the available
     * host-speed signal).
     */
    public long nativeImageMs() {
        OptionalDouble learnedFloor = this.learned.meanMs(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS);
        OptionalDouble learnedSlope = this.learned.meanMs(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB);
        // If we only have learned rates without size, use floor + slope×1MiB as a typical app.
        if (learnedFloor.isPresent() || learnedSlope.isPresent()) {
            double scale = NativeEffort.nativeColdScale(cpuScale());
            double floor = learnedFloor.isPresent() ? learnedFloor.getAsDouble() : NativeEffort.REF_FLOOR_MS * scale;
            double slope = learnedSlope.isPresent() ? learnedSlope.getAsDouble() : NativeEffort.REF_MS_PER_MIB * scale;
            long ms = Math.round(floor + slope * 1.0); // 1 MiB reference app
            return Math.max(NativeEffort.WALL_FLOOR_MS, Math.min(NativeEffort.MAX_NATIVE_MS, ms));
        }
        return scaleBaseline(BASELINE_NATIVE_IMAGE_MS, cpuScale());
    }

    /** Cold OCI image wall (host-scaled product baseline). */
    public long ociImageMs() {
        return scaleBaseline(BASELINE_OCI_IMAGE_MS, ioScale());
    }

    /**
     * Predicted wall-ms for a cold step with unit {@code count} (source/method count). Learned
     * host rates win; otherwise product baseline × host scale — never the legacy 1.2s/method model
     * and never empty-probe residual as absolute ms.
     */
    public long coldStepWallMs(String step, int count) {
        return coldStepWallMs(step, count, 1);
    }

    /**
     * As {@link #coldStepWallMs(String, int)} with within-module test workers. Suite startup is
     * paid once; method body is divided by a <em>capped</em> worker count so cold ETA does not
     * assume linear Mill-style speedup.
     */
    public long coldStepWallMs(String step, int count, int testWorkers) {
        String s = step == null ? "" : step;
        int n = Math.max(0, count);
        int w = coldTestParallel(testWorkers);
        return switch (s) {
            case TaskNames.RUN_TESTS -> {
                long body = (long) n * testMethodMs();
                yield testSuiteStartupMs() + Math.max(0, (body + w - 1) / w);
            }
            case TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY, TaskNames.COMPILE_TEST ->
                compilePerSourceMs(s) * Math.max(1, n);
            case TaskNames.PACKAGE_JAR -> packageJarMs();
            case TaskNames.PACKAGE_ASSEMBLY -> packageAssemblyMs();
            case TaskNames.NATIVE_IMAGE -> nativeImageMs();
            case TaskNames.WRITE_IMAGE -> ociImageMs();
            default -> 0L;
        };
    }

    /** Cap cold within-module test parallel for ETA (not runtime). */
    static int coldTestParallel(int testWorkers) {
        return Math.max(1, Math.min(COLD_MAX_TEST_PARALLEL, Math.max(1, testWorkers)));
    }

    /**
     * CPU-bound host scale (javac + hash vs reference). {@code >1} = slower than reference → longer
     * cold ETA.
     */
    double cpuScale() {
        return hostScale(ratio(javacMs, REF_JAVAC_MS), ratio(hashCpuMs, REF_HASH_CPU_MS));
    }

    /** Fork / suite-startup host scale (JVM spawn + JUnit Platform open). */
    double forkScale() {
        return hostScale(ratio(jvmForkMs, REF_JVM_FORK_MS), ratio(junitPlatformMs, REF_JUNIT_PLATFORM_MS));
    }

    /** Disk I/O host scale (package / CAS-ish work). */
    double ioScale() {
        return hostScale(ratio(diskIoMs, REF_DISK_IO_MS));
    }

    /**
     * Geometric mean of positive ratios, clamped to [{@link #HOST_SCALE_MIN}, {@link
     * #HOST_SCALE_MAX}]. Empty → 1.0 (uncalibrated host uses pure baseline × {@link #COLD_BIAS}).
     */
    static double hostScale(double... ratios) {
        double logSum = 0;
        int n = 0;
        if (ratios != null) {
            for (double r : ratios) {
                if (r > 0 && Double.isFinite(r)) {
                    logSum += Math.log(r);
                    n++;
                }
            }
        }
        if (n == 0) return 1.0;
        double geo = Math.exp(logSum / n);
        return clampScale(geo);
    }

    static double clampScale(double scale) {
        if (!(scale > 0) || !Double.isFinite(scale)) return 1.0;
        return Math.max(HOST_SCALE_MIN, Math.min(HOST_SCALE_MAX, scale));
    }

    /** hostMs / refMs when both positive; else NaN (ignored by {@link #hostScale}). */
    static double ratio(long hostMs, long refMs) {
        if (hostMs <= 0 || refMs <= 0) return Double.NaN;
        return hostMs / (double) refMs;
    }

    /** {@code baseline × scale × COLD_BIAS}, floored at 1 ms. */
    static long scaleBaseline(long baselineMs, double scale) {
        long base = Math.max(1, baselineMs);
        double s = clampScale(scale);
        return Math.max(1, Math.round(base * s * COLD_BIAS));
    }

    /**
     * {@code true} when the next {@link #ensure} is likely to run the multi-second bootstrap probe
     * (client may show "Calibrating host…").
     */
    public static boolean needsProbe() {
        Calibration c = load();
        if (c.present() && c.measured && c.schema >= 3) return false;
        return !failedRecently();
    }

    /** Test seam: construct an instance directly (bypasses the probe/IO). */
    static Calibration testInstance(double msPerWeight, boolean measured, String version, long updated) {
        return testInstance(msPerWeight, measured, version, updated, new HostLearnedRates(), 100, 15, 2);
    }

    /** Test seam with explicit learned rates. */
    static Calibration testInstance(
            double msPerWeight,
            boolean measured,
            String version,
            long updated,
            HostLearnedRates learned,
            long probeStartup,
            long probeMethod,
            long probeCompile) {
        return builder()
                .msPerWeight(msPerWeight)
                .jvmForkMs(10)
                .javacMs(20)
                .diskIoMs(5)
                .hashCpuMs(8)
                .junitForkMs(15)
                .junitRunMs(40)
                .loadAtCalibration(1.5)
                .cores(8)
                .jdk("jdk-x")
                .jkVersion(version)
                .updated(updated)
                .measured(measured)
                .schema(SCHEMA)
                .probeTestSuiteStartupMs(probeStartup)
                .probeTestMethodMs(probeMethod)
                .probeCompilePerSourceMs(probeCompile)
                .learned(learned)
                .build();
    }

    public Calibration withEngineColdStartMs(long coldMs) {
        return toBuilder().engineColdStartMs(Math.max(0, coldMs)).build();
    }

    private Calibration withLearned(HostLearnedRates next) {
        return toBuilder().schema(Math.max(schema, SCHEMA)).learned(next).build();
    }

    // --- load / ensure -------------------------------------------------------

    public static Calibration load() {
        Calibration cached = MEMO.get();
        if (cached != null) return cached;
        Calibration read = HostMetricsFile.readOrAbsent(System.currentTimeMillis());
        MEMO.set(read);
        return read;
    }

    /** Drop the process memo (e.g. after user config.toml mtime change). Next {@link #load} re-reads disk. */
    public static void invalidateMemo() {
        MEMO.set(null);
    }

    /**
     * Ensure a usable calibration is on disk. Network is allowed unless the ambient session is
     * {@code --offline}. Cheap when a current measured file already exists.
     */
    public static Calibration ensure(@Nullable Path jdksDir) {
        return ensure(jdksDir, false, !sessionOffline());
    }

    public static Calibration ensure(@Nullable Path jdksDir, boolean force) {
        return ensure(jdksDir, force, !sessionOffline());
    }

    /**
     * Full ensure. {@code allowNetwork} (on unless global {@code --offline}) enables the resolve
     * HTTP probe and a JUnit jar fetch into the artifact store when the pinned jars are missing.
     */
    public static Calibration ensure(@Nullable Path jdksDir, boolean force, boolean allowNetwork) {
        Calibration current = load();
        // Skip when we already have a current-schema multi-probe result, unless forced.
        if (!force && current.present() && current.measured && current.schema >= 3) return current;
        if (!force && failedRecently()) return current;
        Calibration probed = probe(jdksDir, allowNetwork);
        if (probed != null && probed.present()) {
            // Preserve engine cold-start + continuous learned rates across re-probe.
            if (current.engineColdStartMs > 0 && probed.engineColdStartMs == 0) {
                probed = probed.withEngineColdStartMs(current.engineColdStartMs);
            }
            if (!current.learned.isEmpty()) {
                probed = probed.withLearned(current.learned);
            }
            clearFailureMarker();
            persist(probed);
            MEMO.set(probed);
            return probed;
        }
        recordFailure();
        return current;
    }

    private static boolean sessionOffline() {
        try {
            return SessionContext.current().offline();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final long FAILURE_BACKOFF_MS = TimeUnit.HOURS.toMillis(24);

    static Path failureMarker() {
        return JkDirs.builds().resolve("calibration.failed");
    }

    private static boolean failedRecently() {
        try {
            Path marker = failureMarker();
            if (!Files.isRegularFile(marker)) return false;
            long at = Long.parseLong(Files.readString(marker).trim());
            return System.currentTimeMillis() - at < FAILURE_BACKOFF_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private static void recordFailure() {
        try {
            Files.createDirectories(failureMarker().getParent());
            Files.writeString(failureMarker(), Long.toString(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private static void clearFailureMarker() {
        try {
            Files.deleteIfExists(failureMarker());
        } catch (IOException ignored) {
        }
    }

    /**
     * Fold a completed build's measured throughput into the stored {@code ms-per-weight} (diagnostic
     * / legacy). Continuous step rates use {@link #learnFromSuccess}.
     */
    public static void refine(double observedMsPerWeight, long nowMillis) {
        if (!(observedMsPerWeight > 0)) return;
        Calibration merged = foldRefine(load(), observedMsPerWeight, nowMillis);
        persist(merged);
        MEMO.set(merged);
    }

    /**
     * Fold absolute host samples from a successful (non-cancelled) build into continuous learned
     * rates. Best-effort; never throws.
     */
    public static void learnFromSuccess(List<HostLearnedRates.HostSample> samples) {
        if (samples == null || samples.isEmpty()) return;
        try {
            Calibration cur = load();
            HostLearnedRates next = cur.learned.withSamples(samples);
            if (next == cur.learned) return;
            Calibration updated =
                    cur.present() ? cur.withLearned(next) : minimalWithLearned(next, System.currentTimeMillis());
            // Bump updated so the file is not treated as stale solely from age of last probe.
            updated = updated.touch(System.currentTimeMillis());
            persist(updated);
            MEMO.set(updated);
        } catch (RuntimeException ignored) {
            // advisory
        }
    }

    private Calibration touch(long nowMillis) {
        return toBuilder()
                .msPerWeight(msPerWeight > 0 ? msPerWeight : EffortWeights.MS_PER_WEIGHT)
                .cores(cores > 0 ? cores : Runtime.getRuntime().availableProcessors())
                .jkVersion(JkVersion.VERSION)
                .updated(nowMillis)
                .measured(measured || !learned.isEmpty())
                .schema(Math.max(schema, SCHEMA))
                .build();
    }

    private static Calibration minimalWithLearned(HostLearnedRates learned, long now) {
        return builder()
                .msPerWeight(EffortWeights.MS_PER_WEIGHT)
                .loadAtCalibration(safeLoadAverage())
                .cores(Runtime.getRuntime().availableProcessors())
                .jkVersion(JkVersion.VERSION)
                .updated(now)
                .schema(SCHEMA)
                .learned(learned)
                .build();
    }

    public static Calibration recordEngineColdStart(long coldMs, long nowMillis) {
        long c = Math.max(0, coldMs);
        Calibration cur = load();
        Calibration next;
        if (cur.present()) {
            next = cur.withEngineColdStartMs(c).touch(nowMillis);
        } else {
            next = builder()
                    .msPerWeight(EffortWeights.MS_PER_WEIGHT)
                    .engineColdStartMs(c)
                    .loadAtCalibration(safeLoadAverage())
                    .cores(Runtime.getRuntime().availableProcessors())
                    .jkVersion(JkVersion.VERSION)
                    .updated(nowMillis)
                    .schema(SCHEMA)
                    .learned(cur.learned)
                    .build();
        }
        persist(next);
        MEMO.set(next);
        return next;
    }

    static Calibration foldRefine(Calibration prev, double observedMsPerWeight, long nowMillis) {
        double next = (prev.present() && prev.measured)
                ? ALPHA * observedMsPerWeight + (1 - ALPHA) * prev.msPerWeight
                : observedMsPerWeight;
        return prev.toBuilder()
                .msPerWeight(next)
                .cores(prev.present() ? prev.cores : Runtime.getRuntime().availableProcessors())
                .jkVersion(JkVersion.VERSION)
                .updated(nowMillis)
                .measured(true)
                .schema(Math.max(prev.schema, SCHEMA))
                .build();
    }

    // --- the host probe ------------------------------------------------------

    private static @Nullable Calibration probe(Path jdksDir, boolean allowNetwork) {
        try {
            Optional<Path> javaHome = resolveJavaHome(jdksDir);
            if (javaHome.isEmpty()) return null;
            Path home = javaHome.get();
            HardwareProbe.Result r = HardwareProbe.run(home, HardwareProbe.Options.of(allowNetwork));
            if (r == null || !(r.msPerWeight() > 0)) return null;
            String jdkId = JdkRegistry.identifierFor(home);
            int platformMethods =
                    r.junitPlatformMethods() > 0 ? r.junitPlatformMethods() : (r.junitPlatformUsed() ? 1 : 0);
            long suite = deriveSuiteStartup(
                    r.junitPlatformUsed(), r.junitPlatformMs(), platformMethods, r.junitForkMs(), r.jvmForkMs());
            long method = deriveMethodMs(r.junitRunMs(), r.junitPlatformUsed(), r.junitPlatformMs(), platformMethods);
            long compilePer = r.javacMs() > 0
                    ? Math.max(1, Math.round(r.javacMs() / (double) Math.max(1, HardwareProbe.JAVAC_SOURCES)))
                    : 0;
            return new Calibration(
                    r.msPerWeight(),
                    r.jvmForkMs(),
                    r.javacMs(),
                    r.diskIoMs(),
                    r.hashCpuMs(),
                    r.junitForkMs(),
                    r.junitRunMs(),
                    r.junitPlatformMs(),
                    r.resolveMs(),
                    0,
                    Math.max(0.0, safeLoadAverage()),
                    Runtime.getRuntime().availableProcessors(),
                    jdkId,
                    JkVersion.VERSION,
                    System.currentTimeMillis(),
                    true,
                    r.junitPlatformUsed(),
                    r.resolveUsed(),
                    SCHEMA,
                    suite,
                    method,
                    compilePer,
                    new HostLearnedRates());
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    static long deriveSuiteStartup(
            boolean junitPlatformUsed, long junitPlatformMs, int platformMethods, long junitForkMs, long jvmForkMs) {
        if (junitPlatformUsed && junitPlatformMs > 0) {
            int n = Math.max(1, platformMethods);
            // Attribute most of a multi-method Platform wall to startup; residual → method slope.
            long methodGuess = Math.max(METHOD_MS_FLOOR, junitPlatformMs / (n * 4L));
            long startup = junitPlatformMs - methodGuess * n;
            return Math.max(SUITE_STARTUP_MS_FLOOR, startup);
        }
        if (junitForkMs > 0) return Math.max(SUITE_STARTUP_MS_FLOOR, junitForkMs);
        if (jvmForkMs > 0) return Math.max(SUITE_STARTUP_MS_FLOOR, jvmForkMs);
        return 0;
    }

    static long deriveSuiteStartup(boolean junitPlatformUsed, long junitPlatformMs, long junitForkMs, long jvmForkMs) {
        return deriveSuiteStartup(junitPlatformUsed, junitPlatformMs, 1, junitForkMs, jvmForkMs);
    }

    static long deriveMethodMs(long junitRunMs, boolean junitPlatformUsed, long junitPlatformMs, int platformMethods) {
        if (junitPlatformUsed && junitPlatformMs > 0 && platformMethods > 0) {
            long startup = deriveSuiteStartup(true, junitPlatformMs, platformMethods, 0, 0);
            long residual = Math.max(0, junitPlatformMs - startup);
            long per = Math.max(1, Math.round(residual / (double) platformMethods));
            return clamp(per, METHOD_MS_FLOOR, METHOD_MS_CEIL);
        }
        if (junitRunMs > 0) {
            return clamp(
                    Math.max(1, Math.round(junitRunMs / (double) Math.max(1, HardwareProbe.WORKER_METHODS))),
                    METHOD_MS_FLOOR,
                    METHOD_MS_CEIL);
        }
        return 0;
    }

    static long deriveMethodMs(long junitRunMs, boolean junitPlatformUsed, long junitPlatformMs) {
        return deriveMethodMs(junitRunMs, junitPlatformUsed, junitPlatformMs, junitPlatformUsed ? 1 : 0);
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Optional<Path> resolveJavaHome(Path jdksDir) {
        try {
            JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
            // No module context here, so the ambient layer: request env, then this process's.
            var env = BuildEnv.ambient();
            var req = new JdkResolution.Request(
                    null, SessionContext.current().jdkSpec(), null, null, null, 0, env::apply);
            var r = JdkResolution.resolve(req, registry, JdkInventory.current(), JdkLts.OFFLINE_LATEST_LTS);
            return r.jdkOpt().map(InstalledJdk::home);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static double deriveMsPerWeight(long forkMs, long javacMs) {
        return HardwareProbe.deriveMsPerWeight(forkMs, javacMs, 0, 0, 0, 0);
    }

    private static double safeLoadAverage() {
        try {
            return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean stale(@Nullable String version, long updated, long nowMillis) {
        if (!JkVersion.VERSION.equals(version)) return true;
        return updated > 0 && nowMillis - updated > MAX_AGE_MILLIS;
    }

    // --- IO (format owner: HostMetricsFile) ----------------------------------

    static Calibration absent() {
        return builder().loadAtCalibration(-1).learned(new HostLearnedRates()).build();
    }

    private static void persist(Calibration c) {
        try {
            HostMetricsFile.writeTo(HostMetricsFile.file(), c);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    public String summary() {
        if (!present() && learned.isEmpty()) {
            return "uncalibrated (using static cold floors)";
        }
        StringBuilder sb = new StringBuilder();
        if (present()) {
            sb.append(String.format("ms-per-weight=%.1f (host anchor)%n", msPerWeight()));
        }
        if (jvmForkMs > 0) sb.append(String.format("  jvm-fork            %d ms%n", jvmForkMs));
        if (javacMs > 0) sb.append(String.format("  javac (micro)       %d ms%n", javacMs));
        if (diskIoMs > 0) sb.append(String.format("  disk io (4 MiB)     %d ms%n", diskIoMs));
        if (hashCpuMs > 0) sb.append(String.format("  sha-256 (8 MiB)     %d ms%n", hashCpuMs));
        if (junitForkMs > 0) sb.append(String.format("  test-worker fork    %d ms%n", junitForkMs));
        if (junitRunMs > 0) sb.append(String.format("  test-worker body    %d ms%n", junitRunMs));
        if (junitPlatformUsed && junitPlatformMs > 0) {
            sb.append(String.format("  junit-platform      %d ms  (real @Test via Launcher)%n", junitPlatformMs));
        } else {
            sb.append("  junit-platform      (skipped — no Jupiter jars; retry online or without --offline)\n");
        }
        if (resolveUsed && resolveMs > 0) {
            sb.append(String.format("  resolve (HTTP)      %d ms  (Maven Central micro-GET)%n", resolveMs));
        } else {
            sb.append("  resolve (HTTP)      (skipped — use without --offline to measure)\n");
        }
        sb.append(String.format(
                "  cold ETA prior      startup=%d ms  method=%d ms  compile/src=%d ms%n",
                testSuiteStartupMs(), testMethodMs(), compilePerSourceMs(TaskNames.COMPILE_JAVA)));
        sb.append(String.format(
                "  host scale          cpu=%.2f  fork=%.2f  io=%.2f  (baseline×scale×%.2f)%n",
                cpuScale(), forkScale(), ioScale(), COLD_BIAS));
        if (probeTestMethodMs > 0 || probeTestSuiteStartupMs > 0) {
            sb.append(String.format(
                    "  probe residual      startup=%d ms  empty-method=%d ms  (diagnostic only)%n",
                    probeTestSuiteStartupMs, probeTestMethodMs));
        }
        learned.meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS)
                .ifPresent(m -> sb.append(String.format(
                        "  learned test/method %.1f ms (n=%d)%n",
                        m, learned.sampleCount(HostLearnedRates.RUN_TESTS_PER_METHOD_MS))));
        if (engineColdStartMs > 0) sb.append(String.format("  engine cold start   %d ms%n", engineColdStartMs));
        sb.append(String.format("  cores=%d  measured=%s  schema=%d%n", cores, measured, schema));
        return sb.toString().stripTrailing();
    }

    static void clearMemo() {
        MEMO.set(null);
    }

    /**
     * Install a calibration for the current process (tests). Pass {@link #absentForTest()} for empty
     * priors. Pair with {@link #clearMemo()} in {@code finally}.
     */
    static void installForTest(Calibration cal) {
        MEMO.set(cal == null ? absent() : cal);
    }

    /** Empty calibration (no probe, no learned rates) for unit tests. */
    static Calibration absentForTest() {
        return absent();
    }
}
