// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Callbacks the BuildPlan scheduler emits as tasks progress. Every method has a no-op default —
 * implementations override only the ones they care about.
 *
 * <p>Threading: listeners are invoked on whatever thread emitted the event. For async tasks that
 * means a worker thread; for sync tasks the BuildPlan's caller thread. Listeners that need ordered
 * access to shared state (terminal rendering, file writes) must synchronise internally.
 *
 * <p>Order guarantees: within a single task, events are ordered as emitted. Across tasks, no
 * ordering is guaranteed beyond {@code stepStart} preceding any of that task's other events and
 * {@code stepFinish} following them.
 */
public interface BuildPlanListener {

    default void planStart(BuildPlanView view) {}

    /**
     * A task began. {@code group} is an optional free-form UI label (e.g. {@code compile}); null when
     * unset. Not a lifecycle slot.
     */
    default void stepStart(String step, @Nullable String group, int ticks) {}

    default void progress(String step, int delta, BuildPlanView view) {}

    default void tickUpdate(String step, int delta, BuildPlanView view) {}

    default void label(String step, String label) {}

    default void output(String step, String line) {}

    /** A line the step's fork printed outside its protocol; the run's record keeps the last of them, the view none. */
    default void forkOutput(String step, String line) {}

    default void warn(String step, String code, String message) {}

    default void error(String step, String code, String message) {}

    /** Two-field test-failure form (label + exception class only). Prefer {@link #error(String, String, String, TestFailureInfo)}. */
    default void error(String step, String code, String message, String test, String exceptionClass) {
        error(step, code, message);
    }

    /** Structured test failure (module / engine / class / method / stack). */
    default void error(String step, String code, String message, @Nullable TestFailureInfo failure) {
        if (failure == null) {
            error(step, code, message);
            return;
        }
        error(
                step,
                code,
                message,
                TestFailureInfo.label(failure.module(), failure.method(), 0),
                failure.exceptionClass());
    }

    /**
     * @param duration the step's wall clock, queue wait included
     * @param waited the part of that wall the step spent blocked on a shared resource ({@link
     *     TaskContext#waited}); {@code duration - waited} is the step's own work
     */
    default void stepFinish(
            String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {}

    default void planFinish(BuildPlanResult result) {}
}
