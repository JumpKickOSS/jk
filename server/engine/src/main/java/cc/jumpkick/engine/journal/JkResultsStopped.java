// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import java.util.Locale;
import org.jspecify.annotations.NullMarked;

/**
 * The collateral of a failure in {@code jk-results.md}: what fail-fast stopped, told apart from
 * what failed.
 *
 * <p>When one step fails, the plan cancels the steps still running beside it and a workspace stops
 * admitting modules; the siblings in flight end their steps {@code CANCELLED} and their test runs
 * report {@code cancelled}. Those rows are true, but a reader who sees {@code CANCELLED} beside a
 * run headed {@code FAIL} looks for the interrupt that never happened, and a module row reading
 * {@code FAIL} with nothing but a stopped step under it sends an agent hunting for a failure that
 * is somewhere else. So a run that failed renders its stopped steps as one count, its stopped
 * modules as {@code SKIPPED}, and leaves the {@code cancelled} diagnostics out of {@code ##
 * Failures}. A run that was cancelled keeps every {@code CANCELLED} row: there the cancel is the
 * story.
 */
@NullMarked
final class JkResultsStopped {

    /** Diagnostic code a cancelled step or test run reports under. */
    static final String CANCELLED_CODE = "cancelled";

    private JkResultsStopped() {}

    static boolean isCancelledStatus(String status) {
        if (status == null || status.isBlank()) return false;
        String u = status.trim().toUpperCase(Locale.ROOT);
        return "CANCELLED".equals(u) || "CANCELED".equals(u);
    }

    /** A step stopped by a failure elsewhere in a run that failed rather than was cancelled. */
    static boolean stoppedStep(BuildRecord r, BuildRecord.Task t) {
        return !r.cancelled() && isCancelledStatus(t.status());
    }

    /** How many steps the failure stopped, root then modules. */
    static int countStopped(BuildRecord r) {
        int n = 0;
        for (BuildRecord.Task t : r.steps()) if (stoppedStep(r, t)) n++;
        for (BuildRecord.Module m : r.modules()) {
            for (BuildRecord.Task t : m.steps()) if (stoppedStep(r, t)) n++;
        }
        return n;
    }

    /**
     * A module that did not succeed and failed nothing of its own: fail-fast ended it before it
     * could be judged. Its recorded steps are the evidence — one the failure cancelled, or steps
     * that all ran green before the workspace stopped admitting the next — and the failure it was
     * stopped for is somewhere else in the run. A module with no step, or the only module with
     * anything failed, keeps its own outcome.
     */
    static boolean stoppedModule(BuildRecord r, BuildRecord.Module m) {
        if (r.cancelled() || m.success() || m.steps().isEmpty()) return false;
        for (BuildRecord.Task t : m.steps()) {
            if (failedStep(t)) return false;
        }
        return failedElsewhere(r, m);
    }

    private static boolean failedStep(BuildRecord.Task t) {
        return JkResultsMarkdown.isFailedStatus(t.status()) && !isCancelledStatus(t.status());
    }

    /** A failed step in another module or at the root, or an error diagnostic another module owns. */
    private static boolean failedElsewhere(BuildRecord r, BuildRecord.Module m) {
        for (BuildRecord.Task t : r.steps()) if (failedStep(t)) return true;
        for (BuildRecord.Module other : r.modules()) {
            if (other == m) continue;
            for (BuildRecord.Task t : other.steps()) if (failedStep(t)) return true;
        }
        for (BuildRecord.Diag d : r.diagnostics()) {
            if (!"error".equalsIgnoreCase(d.severity()) || CANCELLED_CODE.equals(d.code())) continue;
            if (d.dir() == null || d.dir().isBlank() || !d.dir().equals(m.dir())) return true;
        }
        return false;
    }

    /** The diagnostic a stopped step or test run left behind, in a run that failed. */
    static boolean collateral(BuildRecord r, BuildRecord.Diag d) {
        return !r.cancelled() && CANCELLED_CODE.equals(d.code());
    }

    /** {@code _N steps stopped by the failure._} after the failed-steps table, when any were. */
    static void appendNote(StringBuilder sb, BuildRecord r) {
        int stopped = countStopped(r);
        if (stopped == 0) return;
        sb.append('_')
                .append(stopped)
                .append(stopped == 1 ? " step" : " steps")
                .append(" stopped by the failure._\n\n");
    }
}
