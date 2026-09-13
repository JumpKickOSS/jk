// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The failure row one test fork leaves when it did not end cleanly. A conversation the parent's
 * own protocol handler ended is a parent-side bug: the worker was stopped on the parent's account,
 * so the row is a {@code (test run)} failure carrying the handler's exception class, message and
 * stack, not the exit code of a kill that says nothing. Every fork the launcher drives — the pull
 * pool's workers, the single fork a one-worker module takes, the list-only discovery fork — ends a
 * handler throw with this one row shape, so the same decoder bug reads the same way in every
 * summary. Any other abnormal exit names the worker and the last class it was dispatched, so a
 * shortfall has an owner.
 */
final class WorkerFailureRow {

    /** The single fork's worker id: it is the only worker, and the aggregator numbers it 0. */
    static final int SINGLE_WORKER = 0;

    private WorkerFailureRow() {}

    static TestFailureInfo of(
            String moduleLabel,
            int workerId,
            int exit,
            String lastClass,
            String output,
            @Nullable RuntimeException handler) {
        String dispatched = lastClass.isBlank() ? "" : " (last class dispatched: " + lastClass + ")";
        if (handler != null) return handlerRow(moduleLabel, "test protocol handler", dispatched, workerId, handler);
        String why = "test worker exited " + exit + " mid-run" + dispatched;
        return new TestFailureInfo(moduleLabel, "", lastClass, "(worker " + workerId + ")", "", why, output, workerId);
    }

    /**
     * The single fork's summary when the parent's own protocol handler ended it: every test the fork
     * reported before the throw, plus the handler row for worker {@link #SINGLE_WORKER}. The row is
     * what fails the run; the exit code of the kill the parent asked for is not consulted.
     */
    static TestSummary singleFork(ResultAggregator aggregator, String moduleLabel, RuntimeException handler) {
        TestFailureInfo row = of(moduleLabel, SINGLE_WORKER, -1, "", "", handler);
        return JUnitLauncher.merge(aggregator.snapshot(), new TestSummary(1, 0, 1, 0, List.of(row)));
    }

    /**
     * The row the list-only discovery fork leaves when the parent's own decoder threw. The row says
     * discovery: no class was run, so there is no worker and no last class to name — only the
     * parent-side exception that ended the listing.
     */
    static TestFailureInfo discovery(String moduleLabel, RuntimeException handler) {
        return handlerRow(moduleLabel, "test discovery protocol handler", "", SINGLE_WORKER, handler);
    }

    private static TestFailureInfo handlerRow(
            String moduleLabel, String who, String dispatched, int workerId, RuntimeException handler) {
        String detail = handler.getMessage() == null ? "" : ": " + handler.getMessage();
        String why = who + " threw " + handler.getClass().getSimpleName() + detail + dispatched;
        return new TestFailureInfo(
                moduleLabel, "", "", "(test run)", handler.getClass().getName(), why, stackOf(handler), workerId);
    }

    private static String stackOf(Throwable t) {
        StringWriter rendered = new StringWriter();
        t.printStackTrace(new PrintWriter(rendered, true));
        return rendered.toString();
    }
}
