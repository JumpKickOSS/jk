// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Prices {@code native-image} wall time for ETA and bar weights.
 *
 * <ol>
 *   <li>Module-own measured wall (success, ≥ floor) — unpadded, best
 *   <li>Host absolute native wall (unpadded) when size model is not clearly better
 *   <li>Size model on <em>effective</em> input bytes (app full + deps discounted)
 *   <li>Cold flat baseline × calibration {@link Calibration#cpuScale()}
 * </ol>
 *
 * <p>Dependency jars are counted at a discount: full classpath byte-sum over-predicted badly
 * (many MiB of jars ≠ linear Graal time). App jar/classes dominate the size signal.
 * Calibration {@code cpuScale()} shapes cold floor/slope only (not measured walls).
 */
public final class NativeEffort {

    /** Reject restore-noise walls below this (aligned with EffortWeights / journal). */
    public static final long WALL_FLOOR_MS = 5_000L;

    /**
     * Product cold floor (analysis/startup). Tuned with slope so ~1.1 MiB app + discounted deps ≈
     * mid-30s on a reference laptop (matches dogfood jk-cli).
     */
    static final long BASELINE_FLOOR_MS = 12_000L;

    /** Product cold slope: ms per effective MiB (see {@link #effectiveInputBytes}). */
    static final double BASELINE_MS_PER_MIB = 12_000.0;

    /**
     * Dep jars contribute this fraction of their bytes to the size model. Full-weight dep sum was
     * the main over-estimate (3–4 MiB raw → 50–80s model vs ~33s actual).
     */
    static final double DEP_BYTE_WEIGHT = 0.12;

    /** Cap absurd predictions. */
    static final long MAX_NATIVE_MS = 20 * 60_000L;

    private static final long MIB = 1024L * 1024L;

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_INPUT_BYTES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private NativeEffort() {}

    public static int weight(Path moduleDir) {
        long ms = wallMillis(moduleDir, BuildMetrics.load(BuildMetrics.defaultFile()));
        return ms > 0 ? EffortWeights.flatWeight(ms) : EffortWeights.TOKEN;
    }

    public static long wallMillis(Path moduleDir) {
        return wallMillis(moduleDir, BuildMetrics.load(BuildMetrics.defaultFile()));
    }

    public static long wallMillis(Path moduleDir, BuildMetrics metrics) {
        String mod = moduleDir == null ? "" : moduleDir.toString();
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());

        // 1) Module-own measured wall — truth, no pad
        long own = EffortWeights.stepOkAvgMillisOwn(metrics, mod, "native-image");
        if (own >= WALL_FLOOR_MS) return own;

        long effective = estimateInputBytes(moduleDir);
        long host = EffortWeights.stepOkAvgMillisHost(metrics, "native-image");
        if (host < WALL_FLOOR_MS) host = 0;

        // 2) Prefer host absolute wall when present — no pad.
        if (host > 0) {
            long fromSize = sizeModelWallMs(effective);
            if (fromSize > 0 && fromSize < host * 1.15) return fromSize;
            return host;
        }

        // 3) Size model when no host sample
        long fromSize = sizeModelWallMs(effective);
        if (fromSize > 0) return fromSize;

        // 4) Cold flat × cpuScale
        return coldFlatMs();
    }

    /**
     * Effective closed-world size for the model: full app jar/classes + discounted runtime dep
     * jars. Returned value is what we persist as {@code input-bytes} for learning consistency.
     */
    public static long estimateInputBytes(Path moduleDir) {
        InputSplit split = splitInputs(moduleDir);
        return effectiveInputBytes(split.appBytes, split.depBytes);
    }

    static long effectiveInputBytes(long appBytes, long depBytes) {
        long app = Math.max(0, appBytes);
        long dep = Math.max(0, depBytes);
        return app + Math.round(dep * DEP_BYTE_WEIGHT);
    }

    private record InputSplit(long appBytes, long depBytes) {}

    private static InputSplit splitInputs(Path moduleDir) {
        if (moduleDir == null || !Files.isDirectory(moduleDir)) return new InputSplit(0, 0);
        try {
            JkBuild project = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
            BuildLayout layout = BuildLayout.of(moduleDir, project);
            long app = 0;
            Path mainJar = layout.mainJar();
            if (Files.isRegularFile(mainJar)) app = Files.size(mainJar);
            else if (Files.isDirectory(layout.classesDir())) app = directorySize(layout.classesDir(), 0);

            long deps = 0;
            Path lock = cc.jumpkick.lock.LockPaths.lockFile(moduleDir);
            Path cache = JkDirs.cache();
            if (Files.isRegularFile(lock)) {
                try {
                    List<Path> jars = BuildPlanner.assemblyDependencyJars(moduleDir, project, lock, cache);
                    deps = sumExistingBytes(jars);
                } catch (IOException | RuntimeException ignored) {
                }
            }
            return new InputSplit(app, deps);
        } catch (Exception e) {
            return new InputSplit(0, 0);
        }
    }

    public static long sumExistingBytes(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return 0;
        long sum = 0;
        for (Path p : paths) {
            if (p == null) continue;
            try {
                if (Files.isRegularFile(p)) sum += Files.size(p);
                else if (Files.isDirectory(p)) sum += directorySize(p, 0);
            } catch (IOException ignored) {
            }
        }
        return sum;
    }

    private static long directorySize(Path dir, int depth) throws IOException {
        if (depth > 12) return 0;
        long sum = 0;
        try (var s = Files.list(dir)) {
            for (Path c : s.toList()) {
                if (Files.isRegularFile(c)) sum += Files.size(c);
                else if (Files.isDirectory(c)) sum += directorySize(c, depth + 1);
            }
        }
        return sum;
    }

    /**
     * Size model on effective MiB. Learned slope/floor when present; else product × cpuScale.
     * Never multiplies both learned rates and cpuScale (double scale).
     */
    static long sizeModelWallMs(long effectiveBytes) {
        if (effectiveBytes <= 0) return 0;
        double mib = effectiveBytes / (double) MIB;
        try {
            Calibration cal = Calibration.load();
            double floor = BASELINE_FLOOR_MS;
            double slope = BASELINE_MS_PER_MIB;
            OptionalDouble learnedSlope = cal.learned().meanMs(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB);
            OptionalDouble learnedFloor = cal.learned().meanMs(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS);
            boolean learned = false;
            if (learnedSlope.isPresent() && learnedSlope.getAsDouble() > 100) {
                slope = learnedSlope.getAsDouble();
                learned = true;
            }
            if (learnedFloor.isPresent() && learnedFloor.getAsDouble() >= WALL_FLOOR_MS) {
                floor = learnedFloor.getAsDouble();
                learned = true;
            }
            if (!learned && cal.hasColdPriors()) {
                double scale = cal.cpuScale();
                floor = BASELINE_FLOOR_MS * scale;
                slope = BASELINE_MS_PER_MIB * scale;
            }
            return clampNativeMs(Math.round(floor + slope * mib));
        } catch (RuntimeException e) {
            return clampNativeMs(Math.round(BASELINE_FLOOR_MS + BASELINE_MS_PER_MIB * mib));
        }
    }

    static long coldFlatMs() {
        try {
            return clampNativeMs(Calibration.load().nativeImageMs());
        } catch (RuntimeException e) {
            return clampNativeMs(Calibration.BASELINE_NATIVE_IMAGE_MS);
        }
    }

    static long clampNativeMs(long ms) {
        if (ms < WALL_FLOOR_MS) return WALL_FLOOR_MS;
        return Math.min(MAX_NATIVE_MS, ms);
    }

    public static void recordSuccessInputBytes(Path moduleDir, long inputBytes) {
        if (moduleDir == null || inputBytes < 1024) return;
        LAST_INPUT_BYTES.put(moduleDir.toString(), inputBytes);
    }

    public static long takeLastInputBytes(String moduleDir) {
        if (moduleDir == null) return 0;
        Long v = LAST_INPUT_BYTES.remove(moduleDir);
        return v == null ? 0 : v;
    }

    /**
     * Host samples from a real native SUCCESS. {@code inputBytes} should be {@link
     * #estimateInputBytes effective} bytes so slope matches prediction units.
     */
    public static List<HostLearnedRates.HostSample> hostSamples(long wallMs, long inputBytes) {
        if (wallMs < WALL_FLOOR_MS || inputBytes < 1024) return List.of();
        double mib = inputBytes / (double) MIB;
        if (mib < 0.05) return List.of();
        // Modest floor share so slope isn't wild on small apps.
        double floor = Math.min(wallMs * 0.35, wallMs - 1);
        double slope = (wallMs - floor) / mib;
        if (!(slope > 0) || !Double.isFinite(slope)) return List.of();
        // Cap slope samples so one fat CP run cannot poison forever.
        slope = Math.min(slope, 40_000);
        floor = Math.min(floor, 60_000);
        return List.of(
                new HostLearnedRates.HostSample(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB, slope, 80_000),
                new HostLearnedRates.HostSample(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS, floor, 90_000));
    }
}
