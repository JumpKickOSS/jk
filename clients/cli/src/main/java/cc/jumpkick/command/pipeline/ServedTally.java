// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * One module's run-tests step, watched for a replay: the engine labels the step {@link
 * TaskNames#TESTS_UP_TO_DATE} when it serves the suite's green marker and then finishes it
 * SKIPPED. A SKIPPED suite under any other label — no tests, a {@code --class} that matched
 * nothing here — was not served anything. Several modules' tallies may share one counter, which
 * is how the workspace line counts the modules served.
 */
final class ServedTally implements BuildPlanListener {

    private final AtomicInteger served;
    private volatile boolean replayed;

    ServedTally(AtomicInteger served) {
        this.served = served;
    }

    ServedTally() {
        this(new AtomicInteger());
    }

    /** Suites served from the action cache on the counter this tally writes. */
    int served() {
        return served.get();
    }

    @Override
    public void label(String step, String label) {
        if (TaskNames.RUN_TESTS.equals(step)) replayed = TaskNames.TESTS_UP_TO_DATE.equals(label);
    }

    @Override
    public void stepFinish(String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
        if (TaskNames.RUN_TESTS.equals(step) && status == TaskStatus.SKIPPED && replayed) {
            served.incrementAndGet();
        }
    }
}
