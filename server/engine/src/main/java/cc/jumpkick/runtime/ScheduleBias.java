// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.util.AtomicWrites;
import cc.jumpkick.util.JkDirs;
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
 * the model cannot see — a structural, host-shaped gap (JK-2216 follow-up: the pipelined sim ran
 * ~25% hot on a 24-core monorepo rebuild). Learning the gap keeps the estimate honest across
 * future scheduler changes instead of baking in today's magic constant.
 *
 * <p>Same store discipline as {@link StepTimings}: a small file under {@link JkDirs#builds()},
 * keyed by project dir; success-only observations, clamped so one outlier cannot poison the fold.
 */
public final class ScheduleBias {

    /** Recent-weighted but smoothed — same alpha discipline as {@link StepTimings}. */
    static final double ALPHA = 0.4;

    /** Bias applied to estimates is kept inside sane bounds even if the store is hand-edited. */
    static final double MIN_BIAS = 0.9;

    static final double MAX_BIAS = 2.0;

    /** Observations only from builds big enough for contention to be signal, not noise. */
    static final int MIN_MODULES = 4;

    static final long MIN_RAW_MS = 5_000;
    static final long MIN_ACTUAL_MS = 10_000;

    private ScheduleBias() {}

    static Path file() {
        return JkDirs.builds().resolve("schedule-bias.toml");
    }

    /** The bias to multiply a raw simulated schedule by; {@code 1.0} until observations exist. */
    public static double current(Path entryDir) {
        try {
            Double b = load(file()).get(key(entryDir));
            if (b == null) return 1.0;
            return Math.max(MIN_BIAS, Math.min(MAX_BIAS, b));
        } catch (RuntimeException e) {
            return 1.0;
        }
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
            String k = key(entryDir);
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
        StringBuilder sb =
                new StringBuilder("# schedule-bias — EWMA of actual/simulated wall per project (ScheduleBias)\n");
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
