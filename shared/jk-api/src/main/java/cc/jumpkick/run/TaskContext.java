// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Optional;

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
    void label(String description);

    /**
     * Durable free-form output for the view (not a transient status). Prefer this over writing
     * {@code System.out}/{@code System.err} directly.
     */
    void output(String line);

    /** Mark outputs already up-to-date/cached; recorded as {@link TaskStatus#SKIPPED}. Idempotent. */
    default void cached() {}

    /** Accumulating warning for the run report. */
    void warn(String code, String message);

    /**
     * Accumulating non-fatal error for the report. Fatal failure is by throwing from
     * {@link Task#execute}.
     */
    void error(String code, String message);

    /** Legacy structured test-failure form of {@link #error(String, String)}. */
    default void error(String code, String message, String test, String exceptionClass) {
        error(code, message);
    }

    /** Structured test failure (module / engine / class / method / stack). */
    default void error(String code, String message, TestFailureInfo failure) {
        if (failure == null) {
            error(code, message);
            return;
        }
        String label = failure.module().isEmpty() ? failure.method() : failure.module() + " :: " + failure.method();
        error(code, message, label, failure.exceptionClass());
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
