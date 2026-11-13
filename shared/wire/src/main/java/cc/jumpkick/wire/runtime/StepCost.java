// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.Objects;

/**
 * One step a module is priced to run, in {@link WorkSchedule} weight units (ms over {@link
 * WorkModel#MS_PER_WEIGHT}): its own price, so a live remainder is the sum of the steps still to
 * finish rather than a share of plan ticks, which are spread evenly over steps of very different
 * length. {@link Kind} places it in the module's shape: the compile prefix, the suite, or a
 * packaging tail that runs beside the suite. {@code spread} is the price's relative uncertainty, one
 * sigma of {@code ln(wall / price)} ({@link EtaSpread}): the layer that priced it, the step's own
 * run-to-run spread and how far this run is from its samples (JK-R0N5S). {@code workers} is, for a
 * suite, the test JVMs its price assumes ({@code 0} when unknown): the build picks the count when
 * the suite starts, from what else is running then, and a suite that gets other than it was priced
 * at is re-priced ({@link #scale(int, int, long, long)}).
 */
public record StepCost(String step, int weight, Kind kind, double spread, int workers, Rung rung) {

    /**
     * Which layer of what the estimate knows priced the step, narrowest first (JK-9BSXK): the step's
     * own recent walls replayed; this module's own rates (a filtered suite from its classes' walls,
     * a compile from its own full rebuild); the project's (a compile from the other modules'); the
     * host's means across projects; the host's calibration. {@link #TOKEN} is a touch, priced at
     * nothing to learn from. The scorer attributes error per rung, so a layer that is
     * systematically off shows as itself and not behind the steps it happens to price.
     */
    public enum Rung {
        REPLAY,
        MODULE,
        PROJECT,
        HOST,
        CALIBRATION,
        TOKEN;

        /** Whether a price from this rung is a prior for work never measured, which a bias may correct. */
        public boolean prior() {
            return this == PROJECT || this == HOST || this == CALIBRATION;
        }
    }

    /** Where the step sits in {@code prefix + max(suite, tail, lane)}. */
    public enum Kind {
        PRE,
        TEST,
        TAIL,
        /**
         * The module's guard chain (compile-guard, then guard): a leaf nothing in the module or
         * downstream waits for, so it runs beside the suite after the prefix, its steps one after
         * the other. Priced as prefix it put a 13 s guard ahead of the engine's 42 s suite, which
         * the build starts at the same moment.
         */
        LANE
    }

    public StepCost {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(kind, "kind");
        weight = Math.max(0, weight);
        spread = EtaSpread.clamp(spread);
        workers = Math.max(0, workers);
        Objects.requireNonNull(rung, "rung");
    }

    /** A step priced from its own walls at the replay's default spread, with no worker count. */
    public StepCost(String step, int weight, Kind kind) {
        this(step, weight, kind, EtaSpread.defaultFor(Rung.REPLAY), 0, Rung.REPLAY);
    }

    /** This step at another price, spread and worker count, from the same rung. */
    public StepCost repriced(int weight, double spread, int workers) {
        return new StepCost(step, weight, kind, spread, workers, rung);
    }

    /**
     * The most this step plausibly runs, in weight units: its price at two sigma, never under
     * {@link #MIN_HIGH} of it. A running step past its price counts down toward it.
     */
    public double high() {
        return weight * Math.max(MIN_HIGH, Math.exp(2 * spread));
    }

    /** The least {@link #high} reaches above the price. */
    public static final double MIN_HIGH = 1.15;

    /**
     * How a suite's wall goes with its test JVMs: as its parallel bound to this power. The engine's
     * integration suite took 239 s on 2 workers and 145 s on 4 in jk's 2026-11-12 corpus, its unit
     * suite 73 s on 2 in a first build and 37 s on 8 in a forced one: each sped up by less than its
     * workers, JVM start and contention taking their share.
     */
    public static final double WORKER_EXPONENT = 0.6;

    /**
     * What a suite's wall measured on {@code from} workers comes to on {@code to}: the ratio of its
     * parallel bounds, {@code max(longest class, class walls / workers)}, to {@link
     * #WORKER_EXPONENT}; {@code 1} when either count is unknown. A suite its longest class bounds
     * gains nothing from more workers: the CLI's integration suite took 636 s on 2 and 631 s on 8.
     * With no class walls ({@code classWallsMs <= 0}) the bound is the count alone.
     */
    public static double scale(int from, int to, long classWallsMs, long longestClassMs) {
        if (from <= 0 || to <= 0 || from == to) return 1;
        if (classWallsMs <= 0) return Math.pow((double) from / to, WORKER_EXPONENT);
        double longest = Math.max(0, longestClassMs);
        double then = Math.max(longest, (double) classWallsMs / from);
        double now = Math.max(longest, (double) classWallsMs / to);
        return then <= 0 ? 1 : Math.pow(now / then, WORKER_EXPONENT);
    }
}
