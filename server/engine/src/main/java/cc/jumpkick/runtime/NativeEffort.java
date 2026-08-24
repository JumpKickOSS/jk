// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Prices {@code native-image} wall time for ETA and bar weights.
 *
 * <ol>
 *   <li><b>Module-own</b> measured wall (success, ≥ floor) — unpadded, always wins
 *   <li><b>Size model</b> on effective input bytes — host-learned floor/slope when present,
 *       else <em>reference</em> product anchors × calibration {@link Calibration#cpuScale()}
 *   <li><b>Host absolute</b> native wall — only when size is unknown (no jar/deps yet); raw
 *       cross-project mean is <em>not</em> size-normalized, so it must not beat the size model
 *   <li><b>Cold flat</b> product baseline × cpuScale when nothing else is available
 * </ol>
 *
 * <p>There is <strong>no</strong> install-time {@code native-image} probe. Reference product
 * anchors ({@link #REF_FLOOR_MS}, {@link #REF_MS_PER_MIB}) are dogfood guesses for a mid-range
 * laptop; they are never used raw — cold paths always scale them by host probe {@code
 * cpuScale()} (javac/hash vs reference). That is imperfect vs Graal, but tracks M-series vs
 * slow Windows hosts far better than a fixed 14 s floor.
 *
 * <p>Successful runs record size-normalized host samples ({@code native-image-ms-per-mib} +
 * {@code native-image-floor-ms}) so alien projects on the same machine get a sized prior without
 * a huge monorepo native wall poisoning a tiny app (and vice versa).
 *
 * <p>Effective bytes = full app jar/classes + discounted runtime dep jars.
 */
public final class NativeEffort {

    /** Reject restore-noise walls below this (aligned with EffortWeights / journal). */
    public static final long WALL_FLOOR_MS = 5_000L;

    /**
     * Reference-host product floor (analysis/startup) — <em>not</em> a measured constant for
     * every machine. Scaled by {@link Calibration#cpuScale()} on cold paths. Chosen so that with
     * {@link #REF_MS_PER_MIB} a ~1.4 MiB-effective dogfood CLI lands mid-30s at scale=1.
     */
    static final long REF_FLOOR_MS = 14_000L;

    /**
     * Reference-host product slope: ms per effective MiB. Scaled by {@code cpuScale()} on cold
     * paths. See {@link #REF_FLOOR_MS}.
     */
    static final double REF_MS_PER_MIB = 12_000.0;

    /**
     * Dep jars contribute this fraction of their bytes to the size model. Full-weight dep sum was
     * the main over-estimate (3–4 MiB raw → 50–80s model vs ~33s actual).
     */
    static final double DEP_BYTE_WEIGHT = 0.12;

    /**
     * Mild over-reserve on cold / size-model paths only (never on measured own walls). Reweight
     * can shrink mid-run; the bar cannot grow.
     */
    static final double MODEL_PAD = 1.08;

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

        // 1) Module-own measured wall — project history supersedes host / baselines
        long own = EffortWeights.stepOkAvgMillisOwn(metrics, mod, TaskNames.NATIVE_IMAGE);
        if (own >= WALL_FLOOR_MS) return own;

        long effective = estimateInputBytes(moduleDir);

        // 2) Size-normalized model (learned host rates → reference × cpuScale)
        long fromSize = sizeModelWallMs(effective);
        if (fromSize > 0) return fromSize;

        // 3) Host absolute wall only when we cannot size the closed world (no jar/deps yet).
        // Raw task.native-image.wall-ms is not size-normalized — do not use it when bytes exist.
        long host = EffortWeights.stepOkAvgMillisHost(metrics, TaskNames.NATIVE_IMAGE);
        if (host >= WALL_FLOOR_MS) return host;

        // 4) Cold flat: reference × cpuScale
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
            JkBuild project = JkBuildParser.parse(moduleDir.resolve(ManifestPaths.MANIFEST));
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
     * Size model on effective MiB. Preference:
     *
     * <ol>
     *   <li>Host-learned floor and/or slope (size-normalized continuous rates) — already
     *       host-shaped; never also × cpuScale
     *   <li>Reference product anchors × full {@link Calibration#cpuScale()} (both inflate and
     *       deflate) so cold ETA tracks calibration rather than a magic fixed floor
     * </ol>
     *
     * Mild {@link #MODEL_PAD}. Returns 0 when {@code effectiveBytes ≤ 0}.
     */
    static long sizeModelWallMs(long effectiveBytes) {
        if (effectiveBytes <= 0) return 0;
        double mib = effectiveBytes / (double) MIB;
        try {
            Calibration cal = Calibration.load();
            double floor = REF_FLOOR_MS;
            double slope = REF_MS_PER_MIB;
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
            if (!learned) {
                // Full host scale (min..max): faster probe → lower cold native ETA; slower → higher.
                // No native-image install probe exists — javac/hash is the available host signal.
                double scale = nativeColdScale(cal.hasColdPriors() ? cal.cpuScale() : 1.0);
                floor = REF_FLOOR_MS * scale;
                slope = REF_MS_PER_MIB * scale;
            } else if (learnedSlope.isEmpty() || learnedFloor.isEmpty()) {
                // Partial learn: fill the missing side from scaled reference so one rate cannot
                // leave the other stuck at an unscaled dogfood constant.
                double scale = nativeColdScale(cal.hasColdPriors() ? cal.cpuScale() : 1.0);
                if (learnedSlope.isEmpty()) slope = REF_MS_PER_MIB * scale;
                if (learnedFloor.isEmpty()) floor = REF_FLOOR_MS * scale;
            }
            return modelPad(Math.round(floor + slope * mib));
        } catch (RuntimeException e) {
            return modelPad(Math.round(REF_FLOOR_MS + REF_MS_PER_MIB * mib));
        }
    }

    static long coldFlatMs() {
        try {
            return modelPad(Calibration.load().nativeImageMs());
        } catch (RuntimeException e) {
            return modelPad(Calibration.BASELINE_NATIVE_IMAGE_MS);
        }
    }

    /**
     * Host scale for cold native product anchors: full {@link Calibration#clampScale} range so
     * calibration probe data shapes the guess (faster host → lower; slower → higher).
     */
    static double nativeColdScale(double cpuScale) {
        return Calibration.clampScale(cpuScale);
    }

    /** Apply mild cold/size pad and clamp. */
    static long modelPad(long ms) {
        return clampNativeMs(Math.round(ms * MODEL_PAD));
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
     * #estimateInputBytes effective} bytes so slope matches prediction units. These are the
     * size-normalized host priors alien projects reuse.
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
