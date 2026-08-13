// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.time.Duration;

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
    default void stepStart(String step, String group, int ticks) {}

    default void progress(String step, int delta, BuildPlanView view) {}

    default void tickUpdate(String step, int delta, BuildPlanView view) {}

    default void label(String step, String label) {}

    default void output(String step, String line) {}

    default void warn(String step, String code, String message) {}

    default void error(String step, String code, String message) {}

    /** Legacy test-failure form (label + exception class only). Prefer {@link #error(String, String, String, TestFailureInfo)}. */
    default void error(String step, String code, String message, String test, String exceptionClass) {
        error(step, code, message);
    }

    /** Structured test failure (module / engine / class / method / stack). */
    default void error(String step, String code, String message, TestFailureInfo failure) {
        if (failure == null) {
            error(step, code, message);
            return;
        }
        String label = failure.module().isEmpty()
                ? failure.method()
                : failure.module() + " :: " + failure.method();
        error(step, code, message, label, failure.exceptionClass());
    }

    default void stepFinish(String step, String group, TaskStatus status, Duration duration) {}

    default void planFinish(BuildPlanResult result) {}
}
