// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.builds.HostStore;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.EtaSpread;
import cc.jumpkick.wire.runtime.StepCost;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

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
 * <p>Beside the bias, every rung but a touch keeps the scale of its spread per kind (JK-R0N5S): how
 * far its steps' {@code ln(wall / price)} reach against the spread each was priced with, the root
 * of their price-weighted mean square {@code (residual / spread)²}, moved {@link #ALPHA_SPREAD} of
 * the way toward each run's. The layer's spread is its {@link EtaSpread#defaultFor default} times
 * that scale ({@link #spread}), so a step's spread carries how far that layer has missed for that
 * kind of step on this host, the bias it was corrected by included, while what a step's own samples
 * and their distance from the run add is not counted twice: a suite read across loads misses by
 * more than one replayed under its own shape, and was priced wider for it.
 * And the host keeps how strongly its steps' errors go the same way ({@link #correlation}): a run's
 * price-weighted mean residual against what it would be were the errors independent, or one.
 *
 * <p>One small file per host ({@link #defaultFile}), one line per rung and kind ({@code rung kind
 * log scale runs}) and one {@code correlation meanSquare independent correlated runs}: read
 * whole when an estimate is priced, rewritten once per finished run.
 */
public final class RungBias {

    /** The file's name beside the host's other build metrics. */
    public static final String FILE = HostStore.FILE;

    /** How far each run's correction moves the bias: a half-life of about 1.4 runs. */
    static final double ALPHA = 0.4;

    /** Steps shorter than this say more about noise than about the layer that priced them. */
    static final long MIN_WALL_MS = 1_000;

    static final double MIN_FACTOR = 0.25;
    static final double MAX_FACTOR = 4.0;

    private static final double MAX_LOG = Math.log(MAX_FACTOR);

    /**
     * How far each run's mean square moves the spread: slower than the bias, since a spread is read
     * from the squares of a handful of steps.
     */
    static final double ALPHA_SPREAD = 0.3;

    /** A run teaches a spread fully from this many steps of the kind; fewer move it less. */
    static final int SPREAD_STEPS = 3;

    /** A run teaches the correlation from this many priced steps at least. */
    static final int CORRELATION_STEPS = 3;

    /** The correlation is kept within this much of 0 and 1: neither is ever quite true. */
    static final double CORRELATION_MARGIN = 0.05;

    /** The line that carries the correlation, in the same section as the rungs. */
    static final String CORRELATION = "correlation";

    /** The least and most a layer's spread is scaled by. */
    static final double MIN_SCALE = 0.25;

    static final double MAX_SCALE = 10;

    /**
     * One rung and kind: the log of its bias factor (prior rungs only), the scale of its spread, and
     * the runs it was learned from.
     */
    record Entry(double log, double scale, int runs) {}

    /**
     * The host's correlation, as three running means over its runs: the square of a run's
     * price-weighted mean residual, and what it would be with every step's error independent and
     * with every one the same ({@link #correlation}).
     */
    record Correlation(double meanSquare, double independent, double correlated, int runs) {
        static final Correlation NONE = new Correlation(0, 0, 0, 0);
    }

    /**
     * One priced step of a finished run: the rung that priced it, its kind, its price, its wall and
     * the spread it was priced with.
     */
    public record Pair(StepCost.Rung rung, String step, long pricedMs, long wallMs, double spread) {

        /** A pair priced at its rung's default spread. */
        public Pair(StepCost.Rung rung, String step, long pricedMs, long wallMs) {
            this(rung, step, pricedMs, wallMs, EtaSpread.defaultFor(rung));
        }
    }

    private final Map<String, Entry> entries;
    private final Correlation correlation;

    private RungBias(Map<String, Entry> entries, Correlation correlation) {
        this.entries = entries;
        this.correlation = correlation;
    }

    /** The host's store, whose {@link HostStore#BIAS} section the biases are. */
    public static Path defaultFile() {
        return HostStore.defaultFile();
    }

    /** The host's biases. */
    public static RungBias load() {
        return load(defaultFile());
    }

    /** The biases in {@code file}; none when it is missing or unreadable. */
    public static RungBias load(Path file) {
        List<String> lines = HostStore.section(file, HostStore.BIAS);
        return new RungBias(parse(lines), parseCorrelation(lines));
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
     * How far a {@code rung} price for {@code step} misses on this host, one sigma of {@code
     * ln(wall / price)}: the rung's {@link EtaSpread#defaultFor default} at the scale this host
     * learned for the kind, or at none while it has learned none.
     */
    public double spread(StepCost.Rung rung, String step) {
        Entry e = rung == StepCost.Rung.TOKEN ? null : entries.get(key(rung, kindOf(step)));
        if (e == null || e.runs() <= 0) return EtaSpread.defaultFor(rung);
        return EtaSpread.clamp(EtaSpread.defaultFor(rung) * e.scale());
    }

    /**
     * How strongly this host's step errors go the same way, {@code 0} (independent) to {@code 1}
     * (one error): where the runs' mean squared residual lies between what independent and what
     * identical errors would give ({@code (m − i) / (c − i)}), kept off both ends by {@link
     * #CORRELATION_MARGIN}; {@link EtaSpread#CORRELATION} until a run teaches it.
     */
    public double correlation() {
        Correlation c = correlation;
        if (c.runs() <= 0 || c.correlated() - c.independent() <= 1e-9) return EtaSpread.CORRELATION;
        double rho = (c.meanSquare() - c.independent()) / (c.correlated() - c.independent());
        return Math.max(CORRELATION_MARGIN, Math.min(1 - CORRELATION_MARGIN, rho));
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
     * Per rung (a touch aside) and kind, the price-weighted mean of the squared residuals over the
     * squared spreads they were priced with, which rescales the layer's spread, moved by {@link
     * #ALPHA_SPREAD}, less for a run with fewer than {@link #SPREAD_STEPS} of the kind. Over all of them, the run's correlation terms ({@link #correlation}).
     */
    public static void learn(Path file, List<Pair> pairs) {
        Map<String, List<double[]>> residuals = new HashMap<>();
        double weights = 0, mean = 0, independent = 0, correlated = 0;
        int steps = 0;
        for (Pair p : pairs) {
            if (p.rung() == StepCost.Rung.TOKEN || p.pricedMs() <= 0 || p.wallMs() < MIN_WALL_MS) continue;
            double r = Math.max(-MAX_LOG, Math.min(MAX_LOG, Math.log((double) p.wallMs() / p.pricedMs())));
            double w = p.pricedMs();
            residuals
                    .computeIfAbsent(key(p.rung(), kindOf(p.step())), k -> new ArrayList<>())
                    .add(new double[] {r, w, p.spread()});
            weights += w;
            mean += w * r;
            independent += Math.pow(w * p.spread(), 2);
            correlated += w * p.spread();
            steps++;
        }
        if (residuals.isEmpty()) return;
        Correlation observed = steps < CORRELATION_STEPS
                ? null
                : new Correlation(
                        Math.pow(mean / weights, 2),
                        independent / (weights * weights),
                        Math.pow(correlated / weights, 2),
                        1);
        HostStore.update(file, HostStore.BIAS, lines -> {
            Map<String, Entry> entries = new TreeMap<>(parse(lines));
            for (Map.Entry<String, List<double[]>> e : residuals.entrySet()) {
                entries.put(e.getKey(), learned(e.getKey(), entries.get(e.getKey()), e.getValue()));
            }
            Correlation was = parseCorrelation(lines);
            Correlation now = observed == null ? was : moved(was, observed);
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                out.add(e.getKey()
                        + ' '
                        + String.format(Locale.ROOT, "%.4f", e.getValue().log())
                        + ' '
                        + String.format(Locale.ROOT, "%.4f", e.getValue().scale())
                        + ' '
                        + e.getValue().runs());
            }
            if (now.runs() > 0) {
                out.add(String.format(
                        Locale.ROOT,
                        "%s %.6f %.6f %.6f %d",
                        CORRELATION,
                        now.meanSquare(),
                        now.independent(),
                        now.correlated(),
                        now.runs()));
            }
            return out;
        });
    }

    /**
     * {@code was} (null on a key's first run) moved by one run's residuals {@code {residual, weight,
     * spread}}.
     */
    private static Entry learned(String key, @Nullable Entry was, List<double[]> v) {
        boolean prior =
                StepCost.Rung.valueOf(key.substring(0, key.indexOf(' '))).prior();
        double log = was == null ? 0 : was.log();
        if (prior) {
            List<Double> rs = new ArrayList<>(v.size());
            for (double[] r : v) rs.add(r[0]);
            double residual = median(rs);
            log = was == null ? residual : log + ALPHA * residual;
            log = Math.max(-MAX_LOG, Math.min(MAX_LOG, log));
        }
        double z2 = 0, weights = 0;
        for (double[] r : v) {
            double z = Math.min(EtaSpread.MAX, Math.abs(r[0])) / Math.max(EtaSpread.MIN, r[2]);
            z2 += r[1] * z * z;
            weights += r[1];
        }
        z2 /= weights;
        // The spreads were priced at the old scale: the scale that would have fit them is the old
        // one times the root of their mean square.
        double old = was == null ? 1 : was.scale();
        double fit = old * old * z2;
        double alpha = ALPHA_SPREAD * Math.min(1, (double) v.size() / SPREAD_STEPS);
        double squared = was == null ? fit : old * old + alpha * (fit - old * old);
        double scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.sqrt(squared)));
        return new Entry(log, scale, was == null ? 1 : was.runs() + 1);
    }

    /** {@code was} moved {@link #ALPHA_SPREAD} toward one run's terms; a first run sets them. */
    private static Correlation moved(Correlation was, Correlation run) {
        if (was.runs() <= 0) return run;
        return new Correlation(
                was.meanSquare() + ALPHA_SPREAD * (run.meanSquare() - was.meanSquare()),
                was.independent() + ALPHA_SPREAD * (run.independent() - was.independent()),
                was.correlated() + ALPHA_SPREAD * (run.correlated() - was.correlated()),
                was.runs() + 1);
    }

    private static String key(StepCost.Rung rung, String kind) {
        return rung.name() + " " + kind;
    }

    private static Map<String, Entry> parse(List<String> lines) {
        Map<String, Entry> out = new HashMap<>();
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 5) continue;
            try {
                StepCost.Rung.valueOf(f[0]);
                out.put(
                        f[0] + " " + f[1],
                        new Entry(Double.parseDouble(f[2]), Double.parseDouble(f[3]), Integer.parseInt(f[4])));
            } catch (IllegalArgumentException malformed) {
                // a line of another rung, the correlation, or a torn number: skipped, the rest still read
            }
        }
        return out;
    }

    private static Correlation parseCorrelation(List<String> lines) {
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 5 || !f[0].equals(CORRELATION)) continue;
            try {
                return new Correlation(
                        Double.parseDouble(f[1]),
                        Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]),
                        Integer.parseInt(f[4]));
            } catch (NumberFormatException torn) {
                return Correlation.NONE;
            }
        }
        return Correlation.NONE;
    }

    private static double median(List<Double> v) {
        List<Double> sorted = new ArrayList<>(v);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
