// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The tree's one poll-until-true helper: a deadline, a 10 ms beat, and an {@link AssertionError}
 * naming the timeout when the condition never came true.
 *
 * <p>Why this exists rather than a {@code Thread.sleep} at each call site: a fixed sleep asserts a
 * duration, not a condition. It is simultaneously too short on a loaded CI box (a flake) and too
 * long on an idle one (dead test time), and it can never prove a *negative* — "sleep then assert
 * the file is absent" passes whenever the write is merely late. Poll for the condition you mean,
 * and let the deadline be the only wall-clock number in the test.
 *
 * <p>Seven test classes in {@code :cli} and {@code :engine} carried a private copy of this loop
 * with three different beats and three different messages. It is not per-module behaviour, so it
 * is not per-module code.
 */
public final class Await {

    /** Poll beat. Short enough that a fast condition costs nothing, long enough not to spin. */
    private static final long BEAT_MILLIS = 10;

    private Await() {}

    /** Poll {@code condition} until true, or fail after {@code timeout}. */
    public static void until(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        until(timeout, condition, () -> "");
    }

    /**
     * As {@link #until(Duration, BooleanSupplier)}, appending {@code detail}'s value to the failure
     * message. The supplier is evaluated only on timeout, so a caller may render an accumulated
     * failure log in it without paying for that on the happy path.
     */
    public static void until(Duration timeout, BooleanSupplier condition, Supplier<String> detail)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                String extra = detail.get();
                throw new AssertionError("condition not met within " + timeout + (extra.isEmpty() ? "" : "; " + extra));
            }
            Thread.sleep(BEAT_MILLIS);
        }
    }
}
