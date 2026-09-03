// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Learned schedule-contention bias: the EWMA of {@code actual wall / raw simulated schedule} for
 * successful multi-module builds, applied multiplicatively to {@link WorkSchedule}'s ideal
 * schedule in {@link BuildEta}. The simulation composes measured step walls into a perfect-overlap
 * timeline; reality pays JVM spawn queuing, {@code PluginSlots} gating, and cache/IO contention
 * the model cannot see — a structural, host-shaped gap (follow-up: the pipelined sim ran
 * ~25% hot on a 24-core monorepo rebuild). Learning the gap keeps the estimate honest across
 * future scheduler changes instead of baking in today's magic constant.
 *
 * <p>Same store discipline as {@link StepTimings}: a small file under {@link JkDirs#builds()},
 * success-only observations, clamped so one outlier cannot poison the fold.
 *
 * <p><strong>Keyed by project AND build shape.</strong> The gap the bias corrects is contention, so
 * it is not one number per project — it scales with how many modules are competing. Measured on the
 * dogfood build, one project wants three different corrections at once: a one-file incremental (3
 * modules) simulated ~20% hot, a mid-width cascade (13 modules) ~45% cold, and a full
 * {@code --redo} (31 modules) about right. A single EWMA cannot hold those; it chases whichever
 * shape ran last and mis-prices the other two, which is exactly what it did — one measurement pass
 * over-read a 21 s build by 2x purely because a wide cascade had just taught it 1.68.
 *
 * <p>So observations fold into a bucket by dirty-module count (rounded down to a power of two), and
 * a read falls back bucket → project-wide → {@code 1.0}. The fallback keeps a project that has only
 * ever built one shape useful for the others, and keeps stores written before bucketing readable.
 */
public final class ScheduleBias {

    /**
     * Recent-weighted but smoothed. Lower than {@link StepTimings}' 0.4 on purpose: this is a
     * multiplier, so sample noise is amplified by whatever the bias currently is. The wide bucket
     * on the dogfood build sits near 2, and at alpha 0.4 a single slow run walked it 2.14 → 2.27
     * and turned a 69 s build into a 93 s estimate. Bucketing also means each key sees fewer but
     * more alike samples, so there is less reason to chase the newest one.
     */
    static final double ALPHA = 0.25;

    /** Bias applied to estimates is kept inside sane bounds even if the store is hand-edited. */
    static final double MIN_BIAS = 0.9;

    /**
     * Matches the fold clamp below, on purpose: the fold is what guards against an outlier
     * observation, so this only has to guard a hand-edited store.
     *
     * <p>It was 2.0 and that bound was binding, not protective. With one bias per project a ratio
     * near 2 meant the store had mixed two build shapes and neither number was trustworthy, so
     * refusing to apply it was right. Now that the store is keyed by shape, 2 is just what the wide
     * bucket measures — the dogfood build's 13-module cascade folds to 2.02, because the schedule
     * model still predicts a native-image cache hit on a build that re-runs it. Clamping that back
     * to 2.0 threw away a real correction.
     */
    static final double MAX_BIAS = 2.5;

    /**
     * Observations only from builds big enough for contention to be signal, not noise.
     *
     * <p>This was 4, which meant a narrow incremental — the shape run most often — never taught the
     * bias anything and was always priced at 1.0. Bucketing makes a narrow observation safe to
     * learn from: it can no longer leak into the wide buckets. The wall thresholds below still keep
     * trivial builds out.
     */
    static final int MIN_MODULES = 1;

    static final long MIN_RAW_MS = 5_000;
    static final long MIN_ACTUAL_MS = 10_000;

    private ScheduleBias() {}

    static Path file() {
        return JkDirs.builds().resolve("schedule-bias.toml");
    }

    /**
     * The bias to multiply a raw simulated schedule by; {@code 1.0} until observations exist.
     *
     * @param dirtyModules how many modules this build will run, which selects the shape bucket;
     *     {@code <= 0} reads the project-wide entry only
     */
    public static double current(Path entryDir, int dirtyModules) {
        try {
            Map<String, Double> m = load(file());
            Double b = dirtyModules > 0 ? m.get(shapeKey(entryDir, dirtyModules)) : null;
            if (b == null) b = m.get(key(entryDir)); // pre-bucketing store, or an unseen shape
            if (b == null) return 1.0;
            return Math.max(MIN_BIAS, Math.min(MAX_BIAS, b));
        } catch (RuntimeException e) {
            return 1.0;
        }
    }

    /** Project-wide read, for callers with no module count to hand. */
    public static double current(Path entryDir) {
        return current(entryDir, 0);
    }

    /**
     * Dirty-module count rounded down to a power of two: contention grows with the count, but not
     * so sharply that 12 and 13 modules deserve separate histories, and coarse buckets fill up fast
     * enough on a real project to be worth having.
     */
    static int bucket(int dirtyModules) {
        int n = Math.max(1, dirtyModules);
        return Integer.highestOneBit(n);
    }

    private static String shapeKey(Path entryDir, int dirtyModules) {
        return key(entryDir) + "|w" + bucket(dirtyModules);
    }

    /**
     * Fold one successful build's outcome into the EWMA. No-ops for small builds, sub-threshold
     * walls, or nonsense ratios — the bias must only ever learn from runs where the schedule
     * model was genuinely exercised.
     */
    public static void observe(Path entryDir, long rawScheduleMs, long actualMs, int dirtyModules) {
        if (dirtyModules < MIN_MODULES || rawScheduleMs < MIN_RAW_MS || actualMs < MIN_ACTUAL_MS) return;
        double ratio = actualMs / (double) rawScheduleMs;
        // Wider than the read clamp: let the fold see mild over-estimates (ratio < 1) so the
        // bias can come back DOWN when the sim stops running hot.
        ratio = Math.max(0.5, Math.min(2.5, ratio));
        try {
            Path f = file();
            Map<String, Double> m = load(f);
            String k = shapeKey(entryDir, dirtyModules);
            Double prev = m.get(k);
            m.put(k, prev == null ? ratio : prev + ALPHA * (ratio - prev));
            write(f, m);
        } catch (RuntimeException | IOException ignored) {
            // best-effort — an unlearned bias just means the raw schedule is used
        }
    }

    private static String key(Path entryDir) {
        return entryDir.toAbsolutePath().normalize().toString();
    }

    private static Map<String, Double> load(Path f) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) return m;
        try {
            for (String line : Files.readAllLines(f)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                int eq = s.lastIndexOf('=');
                if (eq <= 0) continue;
                String rawKey = s.substring(0, eq).trim();
                if (rawKey.length() >= 2 && rawKey.startsWith("\"") && rawKey.endsWith("\"")) {
                    rawKey = rawKey.substring(1, rawKey.length() - 1);
                }
                try {
                    m.put(rawKey, Double.parseDouble(s.substring(eq + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // skip the row; the next observe rewrites it
                }
            }
        } catch (IOException ignored) {
            // unreadable store → empty
        }
        return m;
    }

    private static void write(Path f, Map<String, Double> m) throws IOException {
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder(
                "# schedule-bias — EWMA of actual/simulated wall per project|w<dirty-module bucket> (ScheduleBias)\n");
        for (Map.Entry<String, Double> e : m.entrySet()) {
            sb.append('"')
                    .append(e.getKey())
                    .append("\" = ")
                    .append(String.format(Locale.ROOT, "%.4f", e.getValue()))
                    .append('\n');
        }
        AtomicWrites.replace(f, sb.toString());
    }
}
