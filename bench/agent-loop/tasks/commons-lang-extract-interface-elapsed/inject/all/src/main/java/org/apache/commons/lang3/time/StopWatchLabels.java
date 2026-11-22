package org.apache.commons.lang3.time;

/** A stopwatch's state as a short label. */
final class StopWatchLabels {

    private StopWatchLabels() {
    }

    static String label(final StopWatch watch) {
        return watch.isStarted() ? "ran " + watch.getDuration().toMillis() + " ms" : "not started";
    }

}
