// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.runtime.base.StepLedger;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * What a build's compiles cost on a fresh compiler worker against a lingering one (JK-D3785). The
 * resident worker lingers between builds; after an engine start, or once its linger ran out, the
 * build's first compile starts a JVM and every compile runs on a cold JIT for a while. Measured by
 * forcing the same build on a fresh engine and again on the lingering worker ({@code
 * bench/eta/compile-warmth.sh}), over nine projects and jk's own tree: the first compile took about
 * 0.45 s more (268 ms against 43 on one project, 434 against 34 on jk); the next few ran about twice
 * their warm wall, which is little on a small compile; after about nine they were within a tenth of
 * warm, and jk's later ones ran 1.10 times warm. A lingering worker is warm enough.
 *
 * <p>A compile replays walls measured on the same kind of worker where the ledger has them ({@link
 * StepLedger#compilerBorrowed}); only a price carried from the other kind is moved: by {@link
 * Direction#ADD} for warm walls (or a prior, which is a warm price) on a fresh worker, by {@link
 * Direction#REMOVE} for fresh walls on a lingering one. The charge is the position's in the build:
 * {@link #FIRST_EXTRA_MS} on the first compile, {@link #EARLY_FACTOR} on the next {@link #EARLY},
 * {@link #TAIL} on the rest. Adding the warm-up to every compile, whatever its walls, counted it twice
 * where the ledger's walls already carried it, and made a 3 s project's cold build read +161%.
 */
final class CompilerWarmUp {

    static final long FIRST_EXTRA_MS = 450;
    static final int EARLY = 8;
    static final double EARLY_FACTOR = 2.0;
    static final double TAIL = 1.10;

    /** How a compile's price is carried to this build's worker. */
    enum Direction {
        NONE,
        ADD,
        REMOVE
    }

    /** The warmth a build started with, bound around its pricing, which runs beside the build. */
    private static final ScopedValue<Integer> STARTED_WITH = ScopedValue.newInstance();

    static {
        // The forecast the estimate walks may hop to a pool; the warmth goes with it.
        ContextPropagator.carry(STARTED_WITH);
    }

    /**
     * The resident compiler's warmth now ({@code JavaCompilerHost#warmth}), for a pricing no build
     * bound: {@code jk explain} prices the build it would start now. Static because the estimate
     * reads it where no compiler host is passed; {@link #warmthForTests} is the seam a test pins it
     * with, since a test engine has no lingering worker.
     */
    private static volatile IntSupplier live = JavaCompilerHost::warmth;

    static void warmthForTests(IntSupplier pinned) {
        live = pinned;
    }

    /**
     * Run {@code pricing} as the build that started with {@code warmth} sees it. The estimate is
     * priced beside the build, by when the build's first compiles may have warmed the worker.
     */
    static <T> T startedWith(int warmth, Supplier<T> pricing) {
        return ScopedValue.where(STARTED_WITH, warmth).call(pricing::get);
    }

    /** The warmth of the build being priced: the one it started with, else the worker's now. */
    static int warmth() {
        return STARTED_WITH.isBound() ? STARTED_WITH.get() : live.getAsInt();
    }

    private final int firstExtra;
    private int seen;

    CompilerWarmUp(double cpuScale) {
        double s = cpuScale > 0 && Double.isFinite(cpuScale) ? cpuScale : 1;
        this.firstExtra = (int) Math.max(1, Math.round(FIRST_EXTRA_MS * s / WorkModel.MS_PER_WEIGHT));
    }

    /**
     * {@code own} with each javac compile carried by {@code direction} to this build's worker, at
     * its position among the build's compiles, the plan's order.
     */
    List<StepCost> apply(List<StepCost> own, Function<StepCost, Direction> direction) {
        List<StepCost> out = new ArrayList<>(own.size());
        for (StepCost c : own) {
            if (!StepLedger.javacStep(c.step())) {
                out.add(c);
                continue;
            }
            int n = seen++;
            Direction d = direction.apply(c);
            out.add(d == Direction.NONE ? c : carried(c, n, d == Direction.ADD));
        }
        return out;
    }

    private StepCost carried(StepCost c, int n, boolean add) {
        if (n == 0) {
            int delta = add ? firstExtra : -firstExtra;
            return c.repriced(
                    Math.max(1, c.weight() + delta),
                    Math.max(1, c.low() + delta),
                    Math.max(1, c.high() + delta),
                    c.workers());
        }
        double f = n <= EARLY ? EARLY_FACTOR : TAIL;
        double by = add ? f : 1 / f;
        return c.repriced(
                (int) Math.max(1, Math.round(c.weight() * by)),
                (int) Math.max(1, Math.round(c.low() * by)),
                (int) Math.max(1, Math.round(c.high() * by)),
                c.workers());
    }
}
