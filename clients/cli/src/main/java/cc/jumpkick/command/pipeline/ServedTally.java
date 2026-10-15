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
 *
 * <p>The same watch counts the modules that had a suite at all — one that ran, or one whose green
 * marker was replayed. A run where that count is zero ran no test, and the workspace line says so
 * rather than reporting a pass: {@code --profile <tier>} over modules that carry none is green,
 * but it is not a passing suite. A third count is the prerequisites an {@code --exclusive} run
 * left untested, labelled {@link TaskNames#TESTS_NOT_SELECTED}.
 */
final class ServedTally implements BuildPlanListener {

    private final AtomicInteger served;
    private final AtomicInteger withSuite;
    private final AtomicInteger notSelected;
    private volatile boolean replayed;
    private volatile boolean excluded;

    ServedTally(AtomicInteger served, AtomicInteger withSuite, AtomicInteger notSelected) {
        this.served = served;
        this.withSuite = withSuite;
        this.notSelected = notSelected;
    }

    ServedTally() {
        this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
    }

    /** Suites served from the action cache on the counter this tally writes. */
    int served() {
        return served.get();
    }

    /** Modules whose run-tests step ran a suite or replayed one, on the counter this tally writes. */
    int withSuite() {
        return withSuite.get();
    }

    /** Prerequisite modules whose suite {@code --exclusive} kept from running, on the counter this tally writes. */
    int notSelected() {
        return notSelected.get();
    }

    @Override
    public void label(String step, String label) {
        if (!TaskNames.RUN_TESTS.equals(step)) return;
        replayed = TaskNames.TESTS_UP_TO_DATE.equals(label);
        excluded = TaskNames.TESTS_NOT_SELECTED.equals(label);
    }

    @Override
    public void stepFinish(String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
        if (!TaskNames.RUN_TESTS.equals(step)) return;
        if (status == TaskStatus.SKIPPED && excluded) {
            notSelected.incrementAndGet();
        } else if (status == TaskStatus.SKIPPED && replayed) {
            served.incrementAndGet();
            withSuite.incrementAndGet();
        } else if (status == TaskStatus.SUCCESS || status == TaskStatus.FAIL) {
            withSuite.incrementAndGet();
        }
    }
}
