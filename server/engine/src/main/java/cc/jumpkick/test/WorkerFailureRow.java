// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.jspecify.annotations.Nullable;

/**
 * The failure row one pull-mode test worker leaves when it did not end cleanly. A conversation the
 * parent's own protocol handler ended is a parent-side bug: the worker was stopped on the parent's
 * account, so the row is a {@code (test run)} failure carrying the handler's exception class,
 * message and stack, not the exit code of a kill that says nothing. Any other abnormal exit names
 * the worker and the last class it was dispatched, so a shortfall has an owner.
 */
final class WorkerFailureRow {

    private WorkerFailureRow() {}

    static TestFailureInfo of(
            String moduleLabel,
            int workerId,
            int exit,
            String lastClass,
            String output,
            @Nullable RuntimeException handler) {
        String dispatched = lastClass.isBlank() ? "" : " (last class dispatched: " + lastClass + ")";
        if (handler != null) {
            String detail = handler.getMessage() == null ? "" : ": " + handler.getMessage();
            String why = "test protocol handler threw " + handler.getClass().getSimpleName() + detail + dispatched;
            return new TestFailureInfo(
                    moduleLabel, "", "", "(test run)", handler.getClass().getName(), why, stackOf(handler), workerId);
        }
        String why = "test worker exited " + exit + " mid-run" + dispatched;
        return new TestFailureInfo(moduleLabel, "", lastClass, "(worker " + workerId + ")", "", why, output, workerId);
    }

    private static String stackOf(Throwable t) {
        StringWriter rendered = new StringWriter();
        t.printStackTrace(new PrintWriter(rendered, true));
        return rendered.toString();
    }
}
