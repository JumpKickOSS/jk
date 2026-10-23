// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * How the forecast answers "will this step run?" without running it. Every step a module's build
 * plan holds declares one, built by the same factory as the step's body, and a module is scheduled
 * exactly when one of its steps says it runs. See {@code docs/contributors/lifecycle.md}.
 */
public sealed interface StepForecast {

    /**
     * Rides with its module: it runs, or hits its own cache, whenever the module is scheduled, and
     * its staleness alone never schedules the module (parsing, stamping, a hook with no outputs).
     */
    StepForecast RIDES = new Rides();

    /** Priced by {@code predictor} against the caches the step keys on: cached, or why it runs. */
    static StepForecast priced(Predictor predictor) {
        return new Priced(predictor);
    }

    /** No action key can prove it done, so it runs on every invocation and schedules its module. */
    static StepForecast always(String why) {
        return new Always(why);
    }

    record Rides() implements StepForecast {}

    record Priced(Predictor predictor) implements StepForecast {
        public Priced {
            Objects.requireNonNull(predictor, "predictor");
        }
    }

    record Always(String why) implements StepForecast {
        public Always {
            Objects.requireNonNull(why, "why");
        }
    }

    /** A step's price, read off the caches; the forecast names the verdict after the step. */
    @FunctionalInterface
    interface Predictor {
        /** The verdict, or null when the step has nothing to do this build (no sources, not configured). */
        TaskForecast.@Nullable Task predict(Scope scope) throws Exception;
    }

    /**
     * The module's forecast as far as the walk has priced it: what each predictor reads of the
     * steps it requires, and the workspace state it keys siblings by. The engine supplies it.
     */
    interface Scope {}
}
