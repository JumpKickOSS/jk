package org.apache.commons.lang3.time;

/** Whether a running stopwatch has gone past a time budget. */
final class StopWatchBudget {

    private StopWatchBudget() {
    }

    static boolean overBudget(final StopWatch watch, final long budgetNanos) {
        return watch.isStarted() && watch.getNanoTime() > budgetNanos;
    }

}
