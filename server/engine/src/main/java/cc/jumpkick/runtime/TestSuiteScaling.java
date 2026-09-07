// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

/**
 * Converts a measured suite wall to and from a runner-count-independent form.
 *
 * <p>A suite wall means nothing on its own, because it is the answer to "how long did this suite
 * take <em>on this many runners</em>". {@code server/engine} is the dogfood build's long pole and
 * measures both of these, three minutes apart:
 *
 * <ul>
 *   <li>{@code 45.5 s} on <strong>1</strong> runner, during a {@code --redo} where thirteen modules
 *       were building at once and auto-{@code -w} gave each of them a single test JVM;
 *   <li>{@code 17.4 s} on <strong>24</strong> runners, during a one-file incremental where only two
 *       modules were dirty so the same rule handed engine the whole machine.
 * </ul>
 *
 * <p>Storing either number as "engine's suite cost" mis-prices the other shape by 2.6x, and storing
 * a recency-weighted mean of the two mis-prices both. Worse, a mean wall beside a mean runner count
 * is not even a measurement: the pair describes no build that ever ran, and reconstructing "work"
 * from such a mismatched pair prices a 21 s build at 1 m 31 s.
 *
 * <p>So the normalized wall is what gets stored, and it is normalized at record time while the wall
 * and its runner count are still known to belong together. Averaging <em>that</em> across
 * heterogeneous runs is meaningful, which is the whole point.
 *
 * <p><strong>The exponent.</strong> Sharding a suite is nowhere near linear — 24x the runners bought
 * 2.6x above, not 24x — because JVM startup, class loading and the CAS all re-do per shard, and the
 * shards contend for one machine. A cube root fits the pair jk can actually measure: normalizing the
 * 24-runner observation gives {@code 17.4 x 24^(1/3) = 50.2 s} against the 1-runner observation's
 * {@code 45.5 s}, agreeing to about 10% — comfortably inside what {@link ScheduleBias} absorbs.
 * A square root would predict a 4.9x speedup and under-price a narrow build by half.
 */
public final class TestSuiteScaling {

    private TestSuiteScaling() {}

    /** Sub-linear sharding exponent; see the class doc for the two observations behind it. */
    private static final double SHARD_EXPONENT = 1.0 / 3.0;

    /**
     * The single-runner-equivalent of a wall measured on {@code runners}.
     *
     * @param wallMs measured wall, ms
     * @param runners runners that produced it; {@code <= 0} means unrecorded, and the wall is
     *     returned unchanged rather than scaled by a guess
     */
    public static long normalize(long wallMs, int runners) {
        if (wallMs <= 0 || runners <= 1) return Math.max(0, wallMs);
        return Math.round(wallMs * Math.pow(runners, SHARD_EXPONENT));
    }

    /**
     * The wall to expect from a single-runner-equivalent cost when the build will use
     * {@code runners}. Inverse of {@link #normalize}.
     */
    public static long forRunners(long wall1Ms, int runners) {
        if (wall1Ms <= 0 || runners <= 1) return Math.max(0, wall1Ms);
        return Math.max(1, Math.round(wall1Ms / Math.pow(runners, SHARD_EXPONENT)));
    }
}
