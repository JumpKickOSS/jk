// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.WorkerExit;
import cc.jumpkick.orchestrator.ResultAggregator;
import cc.jumpkick.orchestrator.TestProgressListener;
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
 * summary. A worker whose lease gave up before its process started says so, with the lease's
 * refusal. Any other early end — a non-zero exit, or an exit 0 before the parent said {@code
 * DONE} — names the worker, its exit code and the class it held as the row's class, so the class
 * is the one that failed and a re-run of the failed classes runs it. A memory kill says so after
 * the exit code.
 */
final class WorkerFailureRow {

    /** The single fork's worker id: it is the only worker, and the aggregator numbers it 0. */
    static final int SINGLE_WORKER = 0;

    private WorkerFailureRow() {}

    static TestFailureInfo of(
            String moduleLabel,
            int workerId,
            WorkerExit exit,
            String held,
            String unstarted,
            String output,
            @Nullable RuntimeException handler) {
        String dispatched = held.isBlank() ? "" : " (last class: " + held + ")";
        if (handler != null) return handlerRow(moduleLabel, "test protocol handler", dispatched, workerId, handler);
        String killed = exit.failure("");
        String why = !unstarted.isEmpty()
                ? "test worker never started: " + unstarted
                : exit.code() == 0
                        ? "test worker exited 0 mid-run" + dispatched + "; something called System.exit or Runtime.halt"
                        : "test worker exited " + exit.code() + " mid-run" + dispatched
                                + (killed.isEmpty() ? "" : ", " + killed);
        return new TestFailureInfo(moduleLabel, "", held, "(worker " + workerId + ")", "", why, output, workerId);
    }

    /**
     * Tell {@code listener} of each of {@code deaths} that {@code summary}, the run's final one,
     * still holds, as the failure of the class it names. No test event carried these rows, so
     * without this the run's transcript names no failed class and a gate cannot run the class
     * again; a row a heap retry replaced is not reported, since the retry ran its class.
     */
    static void report(TestProgressListener listener, List<TestFailureInfo> deaths, TestSummary summary) {
        for (TestFailureInfo row : summary.failures()) {
            if (!deaths.contains(row)) continue;
            listener.onFailure(
                    row.className(),
                    row.method(),
                    row.exceptionClass(),
                    row.message(),
                    row.stack(),
                    row.engine(),
                    row.className(),
                    row.method(),
                    row.worker());
        }
    }

    /**
     * The single fork's summary when the parent's own protocol handler ended it: every test the fork
     * reported before the throw, plus the handler row for worker {@link #SINGLE_WORKER}. The row is
     * what fails the run; the exit code of the kill the parent asked for is not consulted.
     */
    static TestSummary singleFork(ResultAggregator aggregator, String moduleLabel, RuntimeException handler) {
        TestFailureInfo row = of(moduleLabel, SINGLE_WORKER, WorkerExit.noWorker(-1), "", "", "", handler);
        return aggregator.snapshot().plus(new TestSummary(1, 0, 1, 0, List.of(row)));
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
