// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.util.Objects;

/**
 * How the forecast answers "will this step run?" without running it. Every step a module's build
 * plan holds declares one, and a module is scheduled exactly when one of its steps says it runs.
 * See {@code docs/contributors/lifecycle.md}.
 */
public sealed interface StepForecast {

    /**
     * Rides with its module: it runs, or hits its own cache, whenever the module is scheduled, and
     * its staleness alone never schedules the module (parsing, stamping, a hook with no outputs).
     */
    StepForecast RIDES = new Rides();

    /** Priced against the caches the step keys on: cached, or why it runs. */
    StepForecast PRICED = new Priced();

    /** No action key can prove it done, so it runs on every invocation and schedules its module. */
    static StepForecast always(String why) {
        return new Always(why);
    }

    record Rides() implements StepForecast {}

    record Priced() implements StepForecast {}

    record Always(String why) implements StepForecast {
        public Always {
            Objects.requireNonNull(why, "why");
        }
    }
}
