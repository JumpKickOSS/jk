// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.StepCost;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How far each prior layer of the estimate misses on this host, per step kind: the factor its next
 * prices are corrected by (JK-626GS, JK-D3785). A step priced from a prior ({@link
 * StepCost.Rung#prior}: the project's rates, the host's means, the calibration) is a guess at work
 * never measured; once it runs, its wall against its price says how far that layer guesses wrong for
 * that kind of step here, and every project built on the host teaches it.
 *
 * <p>The bias is kept as a log ratio, so a step priced at half its wall and one at twice weigh the
 * same. Each finished run is one observation per rung and kind: the median of its steps' residuals,
 * so a first build with fifty cold compiles moves the compile bias once, not fifty times. The bias
 * moves {@link #ALPHA} of the way toward each run's correction, so recent runs dominate and one odd
 * run is smoothed; a key's first run sets it outright. The factor is clamped to {@link #MIN_FACTOR}
 * to {@link #MAX_FACTOR}.
 *
 * <p>One small file per host ({@link #defaultFile}), one line per rung and kind: read whole when an
 * estimate is priced, rewritten once per finished run.
 */
public final class RungBias {

    /** The file's name beside the host's other build metrics. */
    public static final String FILE = "rung-bias.txt";

    /** How far each run's correction moves the bias: a half-life of about 1.4 runs. */
    static final double ALPHA = 0.4;

    /** Steps shorter than this say more about noise than about the layer that priced them. */
    static final long MIN_WALL_MS = 1_000;

    static final double MIN_FACTOR = 0.25;
    static final double MAX_FACTOR = 4.0;

    private static final double MAX_LOG = Math.log(MAX_FACTOR);

    private static final ReentrantLock WRITE = new ReentrantLock();

    /** One rung and kind's bias: the log of the factor, and the runs it was learned from. */
    record Entry(double log, int runs) {}

    /** One priced step of a finished run: the rung that priced it, its kind, its price and its wall. */
    public record Pair(StepCost.Rung rung, String step, long pricedMs, long wallMs) {}

    private final Map<String, Entry> entries;

    private RungBias(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** The host's bias file. */
    public static Path defaultFile() {
        return JkDirs.builds().resolve(FILE);
    }

    /** The host's biases. */
    public static RungBias load() {
        return load(defaultFile());
    }

    /** The biases in {@code file}; none when it is missing or unreadable. */
    public static RungBias load(Path file) {
        return new RungBias(read(file));
    }

    /**
     * What a {@code rung} price for {@code step} is multiplied by: {@code 1} for a rung that is no
     * prior, or a rung and kind this host has not learned.
     */
    public double factor(StepCost.Rung rung, String step) {
        if (!rung.prior()) return 1;
        Entry e = entries.get(key(rung, kindOf(step)));
        if (e == null) return 1;
        return Math.exp(Math.max(-MAX_LOG, Math.min(MAX_LOG, e.log())));
    }

    /**
     * The kind of work a step is, which a rung's bias is kept per: compile, suite, package, guard or
     * other. {@code bench/eta/score.py} reports its rung attribution under the same kinds.
     */
    public static String kindOf(String step) {
        if (step.startsWith(TaskNames.COMPILE_GUARD) || step.startsWith(TaskNames.GUARD)) return "guard";
        if (step.startsWith("compile-")) return "compile";
        if (TaskNames.isRunTests(step)) return "suite";
        if (step.startsWith("package-") || step.equals(TaskNames.NATIVE_IMAGE) || step.equals(TaskNames.WRITE_IMAGE))
            return "package";
        return "other";
    }

    /**
     * Teach {@code file} a finished run's priced steps: per prior rung and kind, the median of the
     * steps' {@code ln(wall / price)}, moved into the bias by {@link #ALPHA}. The price already
     * carries the bias it was corrected by, so the median is the residual the bias still misses by.
     */
    public static void learn(Path file, List<Pair> pairs) {
        Map<String, List<Double>> residuals = new HashMap<>();
        for (Pair p : pairs) {
            if (!p.rung().prior() || p.pricedMs() <= 0 || p.wallMs() < MIN_WALL_MS) continue;
            double r = Math.log((double) p.wallMs() / p.pricedMs());
            residuals
                    .computeIfAbsent(key(p.rung(), kindOf(p.step())), k -> new ArrayList<>())
                    .add(Math.max(-MAX_LOG, Math.min(MAX_LOG, r)));
        }
        if (residuals.isEmpty()) return;
        WRITE.lock();
        try {
            Map<String, Entry> entries = new TreeMap<>(read(file));
            for (Map.Entry<String, List<Double>> e : residuals.entrySet()) {
                double residual = median(e.getValue());
                Entry was = entries.get(e.getKey());
                double log = was == null ? residual : was.log() + ALPHA * residual;
                log = Math.max(-MAX_LOG, Math.min(MAX_LOG, log));
                entries.put(e.getKey(), new Entry(log, was == null ? 1 : was.runs() + 1));
            }
            StringBuilder sb = new StringBuilder("# rung kind log-factor runs: how far each prior misses here\n");
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                sb.append(e.getKey())
                        .append(' ')
                        .append(String.format(Locale.ROOT, "%.4f", e.getValue().log()))
                        .append(' ')
                        .append(e.getValue().runs())
                        .append('\n');
            }
            Files.createDirectories(file.getParent());
            AtomicWrites.replace(file, sb.toString());
        } catch (IOException | RuntimeException e) {
            Log.debug("RungBias.learn: not recorded", e);
        } finally {
            WRITE.unlock();
        }
    }

    private static String key(StepCost.Rung rung, String kind) {
        return rung.name() + " " + kind;
    }

    private static Map<String, Entry> read(Path file) {
        Map<String, Entry> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.trim().split("\\s+");
                if (f.length != 4) continue;
                try {
                    StepCost.Rung.valueOf(f[0]);
                    out.put(f[0] + " " + f[1], new Entry(Double.parseDouble(f[2]), Integer.parseInt(f[3])));
                } catch (IllegalArgumentException malformed) {
                    // a line of another rung or a torn number: skipped, the rest still read
                }
            }
        } catch (IOException e) {
            Log.debug("RungBias: unreadable " + file, e);
        }
        return out;
    }

    private static double median(List<Double> v) {
        List<Double> sorted = new ArrayList<>(v);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
