package org.apache.commons.lang3.time;

/** A stopwatch's message and its time, as one log line. */
final class StopWatchMessages {

    private StopWatchMessages() {
    }

    static String line(final StopWatch watch) {
        return watch.getMessage() + ": " + watch.getDuration().toMillis() + " ms";
    }

}
