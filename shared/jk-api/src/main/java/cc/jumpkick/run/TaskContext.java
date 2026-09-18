// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Step → BuildPlan handle. Thread-safe for worker threads. Report {@link #progress}, grow
 * {@link #updateTicks}, set {@link #label}, poll {@link #cancelled}, emit {@link #warn}/{@link #error}.
 */
public interface TaskContext {

    /** Add {@code delta} to the plan's progress numerator. */
    void progress(int delta);

    /** Grow this step's ticks (and the plan denominator) when the estimate was too low. */
    void updateTicks(int additional);

    /**
     * Replace this step's bar weight once real work is known. Call early, before progress; no-op
     * for unweighted steps.
     */
    default void reweight(int newWeight) {
        /* dynamic-weight steps override via DefaultTaskContext */
    }

    /** Current sub-task label for the TUI; null/empty clears. */
    void label(@Nullable String description);

    /**
     * Durable free-form output for the view (not a transient status). Prefer this over writing
     * {@code System.out}/{@code System.err} directly.
     */
    void output(@Nullable String line);

    /**
     * A line the step's forked process printed outside its protocol. Not shown to the view — {@link
     * #output} is the shown channel — but kept by the run's record as the fork's last lines, so a
     * step a cancel interrupted can say what its fork was printing when it was killed.
     */
    default void forkOutput(String line) {}

    /** Mark outputs already up-to-date/cached; recorded as {@link TaskStatus#SKIPPED}. Idempotent. */
    default void cached() {}

    /**
     * Time this step spent blocked on a shared resource — a compiler worker's queue, a slot — as
     * opposed to doing its own work. Journaled beside the step's wall ({@code wait-ms} next to
     * {@code wall-ms}) and subtracted from it when learning per-unit rates: a rate learned from
     * queue wait prices the machine, not the work. Additive; call once per wait.
     */
    default void waited(Duration blocked) {}

    /** Accumulating warning for the run report. */
    void warn(String code, String message);

    /**
     * Accumulating non-fatal error for the report. Fatal failure is by throwing from
     * {@link Task#execute}.
     */
    void error(String code, String message);

    /**
     * {@link #warn(String, String)} with the tool's own key for the diagnostic ({@code
     * compiler.err.cant.resolve.location}); {@code ""} when the tool gave none.
     */
    default void keyedWarn(String code, String key, String message) {
        warn(code, message);
    }

    /** {@link #error(String, String)} with the tool's own key for the diagnostic. */
    default void keyedError(String code, String key, String message) {
        error(code, message);
    }

    /** Two-field test-failure form of {@link #error(String, String)} (label + exception class). */
    default void error(String code, String message, String test, String exceptionClass) {
        error(code, message);
    }

    /** Structured test failure (module / engine / class / method / stack). */
    default void error(String code, String message, @Nullable TestFailureInfo failure) {
        if (failure == null) {
            error(code, message);
            return;
        }
        error(code, message, TestFailureInfo.label(failure.module(), failure.method(), 0), failure.exceptionClass());
    }

    /** True when cancelled (sibling failure or Ctrl-C); poll in long loops. */
    boolean cancelled();

    /** Stash a value for later steps; one producer per key. */
    <T> void put(BuildPlanKey<T> key, T value);

    /** Previously stashed value, or empty if never put. */
    <T> Optional<T> get(BuildPlanKey<T> key);

    /** Required upstream value; throws {@link IllegalStateException} if missing. */
    <T> T require(BuildPlanKey<T> key);
}
