// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.util.FileLocks;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Learned schedule-contention bias: the EWMA of {@code actual wall / raw simulated schedule} for
 * successful multi-module builds, applied multiplicatively to {@link WorkSchedule}'s ideal
 * schedule in {@code BuildEta}. The simulation composes measured step walls into a perfect-overlap
 * timeline; reality pays JVM spawn queuing, worker-memory leases, and cache/IO contention
 * the model cannot see, and it also overlaps work the model serializes. Learning the gap in
 * either direction keeps the estimate honest across scheduler changes instead of baking in
 * today's magic constant.
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

    /**
     * The bounds an observation is folded within and a stored bias is read within: the same pair,
     * so a correction the fold recorded is applied as recorded, and only a hand-edited store is
     * clamped on read. A simulator running 2x hot or 2x cold is a real shape of a real project
     * (a wide dogfood rebuild measured 0.6, a 13-module cascade 2.0), so both halves are wide, and
     * they are reciprocal: the fold runs in log space, where 2.5x hot and 2.5x cold are the same
     * distance from 1.
     */
    static final double MIN_BIAS = 0.25;

    static final double MAX_BIAS = 2.5;

    /**
     * Observations only from builds big enough for contention to be signal, not noise. Bucketing
     * makes a narrow observation safe to learn from: it can no longer leak into the wide buckets.
     */
    public static final int MIN_MODULES = 1;

    /**
     * The smallest simulated schedule worth learning from. The gate is on the prediction, not on
     * the wall: a build predicted at 36 s that took 5 s is an over-estimate to learn, and a floor
     * on the actual wall would discard exactly the observations that pull the bias down.
     */
    static final long MIN_RAW_MS = 250;

    private ScheduleBias() {}

    static Path file() {
        return JkDirs.builds().resolve("schedule-bias.toml");
    }

    /**
     * The bias to multiply a raw simulated schedule by; {@code 1.0} until observations exist.
     *
     * @param dirtyModules how many modules this build will run
     * @param realWork how many of them do real work rather than a cache-hit recheck; with {@code
     *     dirtyModules}, the shape bucket. A 212-module cascade of rechecks and a 223-module cold
     *     build share a module count and nothing else: one shared correction taught each the
     *     other's, and the cascade read 3x high. An unseen shape reads {@code 1.0}, the model as
     *     simulated, rather than another shape's correction.
     */
    public static double current(Path entryDir, int dirtyModules, int realWork, boolean full, boolean workerWarm) {
        return current(entryDir, dirtyModules, realWork, Shape.of(full), workerWarm);
    }

    /** {@link #current(Path, int, int, boolean, boolean)} for a build of {@code shape}. */
    public static double current(Path entryDir, int dirtyModules, int realWork, Shape shape, boolean workerWarm) {
        try {
            Map<String, Double> m = load(file());
            Double b = dirtyModules > 0 ? m.get(shapeKey(entryDir, dirtyModules, realWork, shape, workerWarm)) : null;
            if (b == null) return 1.0;
            return Math.max(MIN_BIAS, Math.min(MAX_BIAS, b));
        } catch (RuntimeException e) {
            return 1.0;
        }
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

    /** Whether a correction has been learned for this shape: until one has, the model runs unaided. */
    public static boolean learned(Path entryDir, int dirtyModules, int realWork, boolean full, boolean workerWarm) {
        return learned(entryDir, dirtyModules, realWork, Shape.of(full), workerWarm);
    }

    /** {@link #learned(Path, int, int, boolean, boolean)} for a build of {@code shape}. */
    public static boolean learned(Path entryDir, int dirtyModules, int realWork, Shape shape, boolean workerWarm) {
        try {
            return dirtyModules > 0
                    && load(file()).containsKey(shapeKey(entryDir, dirtyModules, realWork, shape, workerWarm));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * What kind of build a correction is learned for. An API change that recompiles 211 dependents
     * has a cold build's width and real-work count, and twice its correction: their compiles are
     * measured under a cold build's contention, which the cold build's schedule then counts again.
     * And a cascade whose dependents compile whole over their own outputs (aggregating processors, a
     * removed member) prices them as whole compiles where an added member prices them as partial
     * ones: the same wall against twice the schedule, so the two halve each other's correction.
     */
    public enum Shape {
        /** Everything from nothing: forced, or most compiles have no analysis to compile against. */
        // "F", not the "f" a full build was filed under when any whole compile made one: those
        // corrections mix in cascades and are left unread rather than carried over.
        FULL("F"),
        /** Most compiles go whole over outputs the modules already have. */
        WHOLE("w"),
        /** Everything else. */
        INCREMENTAL("i");

        private final String tag;

        Shape(String tag) {
            this.tag = tag;
        }

        public static Shape of(boolean full) {
            return full ? FULL : INCREMENTAL;
        }
    }

    private static String shapeKey(Path entryDir, int dirtyModules, int realWork, Shape shape, boolean workerWarm) {
        return key(entryDir) + "|w" + bucket(dirtyModules) + "|r" + bucket(realWork) + "|" + shape.tag
                + (workerWarm ? "" : "|cold");
    }

    /**
     * Fold one successful build's outcome into the EWMA of {@code ln(actual / simulated)}. No-ops
     * for builds whose simulated schedule is too small to have exercised the model.
     */
    public static void observe(
            Path entryDir,
            long rawScheduleMs,
            long actualMs,
            int dirtyModules,
            int realWork,
            boolean full,
            boolean workerWarm) {
        observe(entryDir, rawScheduleMs, actualMs, dirtyModules, realWork, Shape.of(full), workerWarm);
    }

    /** {@link #observe(Path, long, long, int, int, boolean, boolean)} for a build of {@code shape}. */
    public static void observe(
            Path entryDir,
            long rawScheduleMs,
            long actualMs,
            int dirtyModules,
            int realWork,
            Shape shape,
            boolean workerWarm) {
        if (dirtyModules < MIN_MODULES || rawScheduleMs < MIN_RAW_MS || actualMs <= 0) return;
        // Clamped first, so one observation moves the EWMA by at most a quarter of the way to a
        // bound: a build that finished in a seventh of its schedule teaches 0.4, not 0.15.
        double ratio = Math.max(MIN_BIAS, Math.min(MAX_BIAS, actualMs / (double) rawScheduleMs));
        // Read-fold-write under the file's lock: two builds finishing together, in one engine or
        // two, otherwise each rewrite the whole file from their own read and one loses its row.
        try {
            Path f = file();
            FileLocks.withLock(lockFile(f), () -> {
                Map<String, Double> m = load(f);
                String k = shapeKey(entryDir, dirtyModules, realWork, shape, workerWarm);
                Double prev = m.get(k);
                m.put(k, prev == null ? ratio : fold(prev, ratio));
                write(f, m);
            });
        } catch (RuntimeException | IOException e) {
            // best-effort — an unlearned bias just means the raw schedule is used
            Log.debug("observe: best-effort", e);
        }
    }

    /**
     * One EWMA step in log space: a ratio is a multiplier, so halving and doubling must pull the
     * bias equally far. A stored value outside the bounds (a hand edit) is clamped first.
     */
    static double fold(double prev, double ratio) {
        double from = Math.log(Math.max(MIN_BIAS, Math.min(MAX_BIAS, prev)));
        return Math.exp(from + ALPHA * (Math.log(ratio) - from));
    }

    private static Path lockFile(Path f) {
        return f.resolveSibling(f.getFileName() + ".lock");
    }

    private static String key(Path entryDir) {
        return entryDir.toAbsolutePath().normalize().toString();
    }

    /**
     * The project's learned close-out: from the last module finishing to the build reporting done
     * (verdicts, the clean memo, the journal, the run snapshot). It is additive, not proportional:
     * about a quarter of a second whether one module ran or two hundred, so on a one-module edit it
     * was a fifth of the wall the estimate never counted. {@code 0} until a build has measured it.
     */
    public static long tailMs(Path entryDir) {
        try {
            Double t = load(file()).get(key(entryDir) + TAIL);
            return t == null ? 0 : Math.round(Math.max(0, Math.min(MAX_TAIL_MS, t)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Fold one successful build's close-out into the project's tail (a linear EWMA). */
    public static void observeTail(Path entryDir, long tailMs) {
        if (tailMs < 0) return;
        double observed = Math.min(MAX_TAIL_MS, tailMs);
        try {
            Path f = file();
            FileLocks.withLock(lockFile(f), () -> {
                Map<String, Double> m = load(f);
                String k = key(entryDir) + TAIL;
                Double prev = m.get(k);
                m.put(k, prev == null ? observed : prev + ALPHA * (observed - prev));
                write(f, m);
            });
        } catch (RuntimeException | IOException e) {
            Log.debug("observeTail: best-effort", e);
        }
    }

    private static final String TAIL = "|tail";

    /** A close-out longer than this is a stall, not the shape of the project's close-out. */
    private static final long MAX_TAIL_MS = 10_000;

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
        OwnerOnlyFiles.createDirectories(Objects.requireNonNull(f.getParent(), "a file has a directory"));
        StringBuilder sb = new StringBuilder(
                "# schedule-bias — EWMA of actual/simulated wall per project|w<dirty-module bucket>|r<real-work bucket>|f(ull) or i(ncremental) (ScheduleBias)\n");
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
