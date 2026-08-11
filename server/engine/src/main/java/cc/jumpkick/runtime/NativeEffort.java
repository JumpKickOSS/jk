// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.config.JkBuildParser;
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
 *   <li>Module-own measured wall (success, ≥ floor) — best
 *   <li>Host size model: learned ms/MB × closed-world input bytes + floor
 *   <li>Cold size model: product slope/floor × calibration {@link Calibration#cpuScale()}
 *   <li>Flat cold baseline × cpuScale (only when size unknown)
 * </ol>
 *
 * <p>Input size is Σ {@code File.length} of the native classpath (app jar or classes tree + runtime
 * dep jars that exist) — no fat jar required. Calibration probe data shapes cold floors/slopes via
 * {@code cpuScale()} (javac/hash vs reference host).
 */
public final class NativeEffort {

    /** Reject restore-noise walls below this (aligned with EffortWeights / journal). */
    public static final long WALL_FLOOR_MS = 5_000L;

    /**
     * Reference product: ~1.1 MB thin app jar → ~33 s native on a mid laptop → roughly 15 s floor +
     * 16 s/MB. Prefer slight over-estimate.
     */
    static final long BASELINE_FLOOR_MS = 15_000L;

    /** Product cold slope: ms per MiB of closed-world input. */
    static final double BASELINE_MS_PER_MIB = 16_000.0;

    /** Mild over-reserve so bar rarely under-counts (cannot reweight up). */
    static final double OVER_RESERVE = 1.08;

    /** Cap absurd size-model predictions. */
    static final long MAX_NATIVE_MS = 30 * 60_000L;

    private static final long MIB = 1024L * 1024L;

    /** Last successful native input-bytes for this module (process buffer → host learning). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_INPUT_BYTES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private NativeEffort() {}

    /** Weight units for bar / schedule. */
    public static int weight(Path moduleDir) {
        long ms = wallMillis(moduleDir, BuildMetrics.load(BuildMetrics.defaultFile()));
        return ms > 0 ? EffortWeights.flatWeight(ms) : EffortWeights.TOKEN;
    }

    /** Absolute wall-ms estimate. */
    public static long wallMillis(Path moduleDir) {
        return wallMillis(moduleDir, BuildMetrics.load(BuildMetrics.defaultFile()));
    }

    public static long wallMillis(Path moduleDir, BuildMetrics metrics) {
        String mod = moduleDir == null ? "" : moduleDir.toString();
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());

        // 1) Module-own measured wall
        long own = EffortWeights.stepOkAvgMillisOwn(metrics, mod, "native-image");
        if (own >= WALL_FLOOR_MS) return pad(own);

        long inputBytes = estimateInputBytes(moduleDir);

        // 2) Host size model from continuous learning / harvested rates
        long fromSize = sizeModelWallMs(inputBytes);
        if (fromSize > 0) return pad(fromSize);

        // 3) Host flat native wall (other modules) if credible
        long host = EffortWeights.stepOkAvgMillisHost(metrics, "native-image");
        if (host >= WALL_FLOOR_MS) {
            // Scale host wall by this module's size vs a reference if we have bytes
            if (inputBytes > 0) {
                long scaled = scaleHostWallToBytes(host, inputBytes);
                if (scaled >= WALL_FLOOR_MS) return pad(scaled);
            }
            return pad(host);
        }

        // 4) Cold size model with calibration host scale
        if (inputBytes > 0) return pad(coldSizeModelMs(inputBytes));

        // 5) Flat cold × cpuScale
        return pad(coldFlatMs());
    }

    /** Closed-world input bytes for native-image (app + runtime jars that exist). */
    public static long estimateInputBytes(Path moduleDir) {
        if (moduleDir == null || !Files.isDirectory(moduleDir)) return 0;
        try {
            JkBuild project = JkBuildParser.parse(moduleDir.resolve("jk.toml"));
            BuildLayout layout = BuildLayout.of(moduleDir, project);
            List<Path> paths = new ArrayList<>();
            Path mainJar = layout.mainJar();
            if (Files.isRegularFile(mainJar)) {
                paths.add(mainJar);
            } else if (Files.isDirectory(layout.classesDir())) {
                // Dirty rebuild before package: classes tree as app bytecode proxy
                paths.add(layout.classesDir());
            }
            Path lock = cc.jumpkick.lock.LockPaths.lockFile(moduleDir);
            Path cache = JkDirs.cache();
            if (Files.isRegularFile(lock)) {
                try {
                    for (Path p : BuildPlanner.assemblyDependencyJars(moduleDir, project, lock, cache)) {
                        if (p != null) paths.add(p);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // best-effort size
                }
            }
            return sumExistingBytes(paths);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Sum file sizes; directories use recursive size (bounded best-effort). */
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
        if (depth > 12) return 0; // avoid pathological trees
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
     * Host-learned or product size model. Uses {@link HostLearnedRates} ms/MB + floor when present;
     * else product baselines × {@link Calibration#cpuScale()}.
     */
    static long sizeModelWallMs(long inputBytes) {
        if (inputBytes <= 0) return 0;
        double mib = inputBytes / (double) MIB;
        try {
            Calibration cal = Calibration.load();
            double floor = BASELINE_FLOOR_MS;
            double slope = BASELINE_MS_PER_MIB;
            OptionalDouble learnedSlope = cal.learned().meanMs(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB);
            OptionalDouble learnedFloor = cal.learned().meanMs(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS);
            if (learnedSlope.isPresent() && learnedSlope.getAsDouble() > 0) {
                slope = learnedSlope.getAsDouble();
            } else if (cal.hasColdPriors()) {
                slope = BASELINE_MS_PER_MIB * cal.cpuScale();
            }
            if (learnedFloor.isPresent() && learnedFloor.getAsDouble() > 0) {
                floor = learnedFloor.getAsDouble();
            } else if (cal.hasColdPriors()) {
                floor = BASELINE_FLOOR_MS * cal.cpuScale();
            }
            long ms = Math.round(floor + slope * mib);
            return clampNativeMs(ms);
        } catch (RuntimeException e) {
            long ms = Math.round(BASELINE_FLOOR_MS + BASELINE_MS_PER_MIB * mib);
            return clampNativeMs(ms);
        }
    }

    static long coldSizeModelMs(long inputBytes) {
        return sizeModelWallMs(inputBytes);
    }

    static long coldFlatMs() {
        try {
            Calibration cal = Calibration.load();
            return clampNativeMs(cal.nativeImageMs());
        } catch (RuntimeException e) {
            return clampNativeMs(Calibration.BASELINE_NATIVE_IMAGE_MS);
        }
    }

    /** Scale a host absolute wall by this module's input size vs a 1 MiB reference. */
    static long scaleHostWallToBytes(long hostWallMs, long inputBytes) {
        if (hostWallMs <= 0 || inputBytes <= 0) return hostWallMs;
        double mib = Math.max(0.25, inputBytes / (double) MIB); // avoid zero-size blowups
        // Assume host average ≈ reference size ~1 MiB; scale mildly (sqrt to avoid wild swings)
        double scale = Math.sqrt(mib);
        return clampNativeMs(Math.round(hostWallMs * scale));
    }

    static long pad(long ms) {
        return clampNativeMs(Math.round(ms * OVER_RESERVE));
    }

    static long clampNativeMs(long ms) {
        if (ms < WALL_FLOOR_MS) return WALL_FLOOR_MS;
        return Math.min(MAX_NATIVE_MS, ms);
    }

    /** Remember input bytes after a real native SUCCESS for host learning. */
    public static void recordSuccessInputBytes(Path moduleDir, long inputBytes) {
        if (moduleDir == null || inputBytes < WALL_FLOOR_MS) return; // bytes, not ms — use 1KB floor
        if (inputBytes < 1024) return;
        LAST_INPUT_BYTES.put(moduleDir.toString(), inputBytes);
    }

    public static long takeLastInputBytes(String moduleDir) {
        if (moduleDir == null) return 0;
        Long v = LAST_INPUT_BYTES.remove(moduleDir);
        return v == null ? 0 : v;
    }

    /** Peek without consuming (journal may record after host learning takes). */
    public static long peekLastInputBytes(String moduleDir) {
        if (moduleDir == null) return 0;
        Long v = LAST_INPUT_BYTES.get(moduleDir);
        return v == null ? 0 : v;
    }

    /**
     * Host samples: ms per MiB and floor from one successful native run. {@code wallMs} must be a
     * real Graal wall (≥ {@link #WALL_FLOOR_MS}).
     */
    public static List<HostLearnedRates.HostSample> hostSamples(long wallMs, long inputBytes) {
        if (wallMs < WALL_FLOOR_MS || inputBytes < 1024) return List.of();
        double mib = inputBytes / (double) MIB;
        if (mib < 0.05) return List.of();
        // Attribute ~half of wall to floor, rest to size (rough; trimmed mean stabilizes).
        double floor = Math.min(wallMs * 0.45, wallMs - 1);
        double slope = (wallMs - floor) / mib;
        if (!(slope > 0) || !Double.isFinite(slope)) return List.of();
        return List.of(
                new HostLearnedRates.HostSample(HostLearnedRates.NATIVE_IMAGE_MS_PER_MIB, slope, 120_000),
                new HostLearnedRates.HostSample(HostLearnedRates.NATIVE_IMAGE_FLOOR_MS, floor, 120_000));
    }
}
