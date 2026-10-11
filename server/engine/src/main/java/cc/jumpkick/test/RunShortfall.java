// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A pull-queue run that left classes undispatched is not a green one, whether the kill was the
 * cancel we asked for or a worker that quit on its own. Every class that did run may have passed,
 * and a summary that only counted those would let run-tests stamp the suite green — the next build
 * then skips the tests that never ran. One failure names the shortfall instead.
 *
 * <p>That row is a fact about the run, not a test: it fails the step and stamps nothing green,
 * and the journal's test counts leave it out ({@link #rows}), so a fail-fast build with one red
 * test reads {@code 1 failed} however many sibling runs the failure stopped.
 */
public final class RunShortfall {

    /** The {@code method} of a shortfall row; there is no test behind it. */
    static final String RUN = "(test run)";

    /** How a cancelled run's row starts. */
    static final String CANCELLED = "test run cancelled";

    /** How an uncancelled run's row starts: a worker quit with classes still queued. */
    static final String INCOMPLETE = "test run incomplete";

    private RunShortfall() {}

    /**
     * The failure the run reports, or {@code null} when it completed. Uncancelled, only the
     * undispatched classes are the shortfall: a worker stopped mid-class has a row of its own. A
     * cancel that landed after the last class had run and every worker finished its queue left
     * nothing unrun, and the summary stands on its own.
     */
    static @Nullable String of(boolean cancelled, boolean stoppedMidClass, int undispatched) {
        if (!cancelled) return undispatched == 0 ? null : INCOMPLETE + ": " + neverRan(undispatched);
        if (!stoppedMidClass && undispatched == 0) return null;
        StringBuilder why = new StringBuilder(CANCELLED);
        if (undispatched > 0) why.append(": ").append(neverRan(undispatched));
        if (stoppedMidClass) why.append(undispatched > 0 ? "; " : ": ").append("a worker was stopped mid-class");
        return why.toString();
    }

    private static String neverRan(int undispatched) {
        return undispatched + (undispatched == 1 ? " class" : " classes") + " never ran";
    }

    /** The shortfall row for {@code moduleLabel}, or {@code null} when {@link #of} says the run completed. */
    static @Nullable TestFailureInfo row(
            String moduleLabel, boolean cancelled, boolean stoppedMidClass, int undispatched) {
        String why = of(cancelled, stoppedMidClass, undispatched);
        return why == null ? null : new TestFailureInfo(moduleLabel, "", "", RUN, "", why, "", 0);
    }

    /** Whether {@code f} is a shortfall row rather than a test that ran and failed. */
    public static boolean isRow(TestFailureInfo f) {
        return RUN.equals(f.method())
                && (f.message().startsWith(CANCELLED) || f.message().startsWith(INCOMPLETE));
    }

    /** How many of {@code failures} are shortfall rows. */
    public static long rows(List<TestFailureInfo> failures) {
        long n = 0;
        for (TestFailureInfo f : failures) if (isRow(f)) n++;
        return n;
    }
}
