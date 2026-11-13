// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One measure's running mean across runs, as the estimate stores keep it: the mean, the newest
 * sample, how many samples were folded, and the store's run sequence when the last one was.
 *
 * <p>The first {@link #PLAIN_SAMPLES} samples are a plain mean; after them each sample moves the
 * mean by {@link #MIN_WEIGHT} of its distance (an exponential average, so the last few dozen runs
 * carry it). From the {@link #CLIP_AFTER}th sample on, a sample is clipped to within {@link #CLIP}
 * times the mean either way before it is folded, so one stalled or cache-restored run cannot drag
 * a settled mean.
 */
public record RunningMean(double mean, double last, long count, long seen) {

    /** Samples averaged plainly before the mean turns exponential. */
    static final int PLAIN_SAMPLES = 10;

    /** The weight of a new sample once the mean is exponential. */
    static final double MIN_WEIGHT = 1.0 / PLAIN_SAMPLES;

    /** Samples folded before a new one is clipped to the mean's range. */
    static final int CLIP_AFTER = 3;

    /** How far from the mean, as a factor either way, a sample is taken at face value. */
    static final double CLIP = 4.0;

    /** {@code v} folded into {@code before} (null: nothing yet) at run sequence {@code seq}. */
    public static RunningMean fold(@Nullable RunningMean before, double v, long seq) {
        if (before == null || before.count <= 0) return new RunningMean(v, v, 1, seq);
        double sample = v;
        if (before.count >= CLIP_AFTER && before.mean > 0) {
            sample = Math.max(before.mean / CLIP, Math.min(before.mean * CLIP, v));
        }
        double weight = Math.max(1.0 / (before.count + 1), MIN_WEIGHT);
        return new RunningMean(before.mean + weight * (sample - before.mean), v, before.count + 1, seq);
    }

    /** A number as the stores write it: integral values bare, others to three places. */
    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** {@code mean last count seen}, space separated. */
    String columns() {
        return fmt(mean) + ' ' + fmt(last) + ' ' + count + ' ' + seen;
    }

    /**
     * The running mean in {@code cols} from {@code from} on ({@code mean last count [seen]}); null
     * when they do not parse.
     */
    static @Nullable RunningMean parse(String[] cols, int from) {
        if (cols.length < from + 3) return null;
        try {
            double mean = Double.parseDouble(cols[from]);
            double last = Double.parseDouble(cols[from + 1]);
            long count = Long.parseLong(cols[from + 2]);
            long seen = cols.length > from + 3 ? Long.parseLong(cols[from + 3]) : 0;
            if (!Double.isFinite(mean) || !Double.isFinite(last) || count <= 0) return null;
            return new RunningMean(mean, last, count, seen);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
