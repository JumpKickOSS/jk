// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.run.TestFailureInfo;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A cancelled test run is not a green one. The kill we asked for leaves classes undispatched and
 * the in-flight ones unreported; every class that did run may have passed, and a summary that
 * only counted those would let run-tests stamp the suite green — the next build then skips the
 * tests that never ran. One failure names the shortfall instead.
 *
 * <p>That row is a fact about the run, not a test: it fails the step and stamps nothing green,
 * and the journal's test counts leave it out ({@link #rows}), so a fail-fast build with one red
 * test reads {@code 1 failed} however many sibling runs the failure stopped.
 */
public final class CancelledShortfall {

    /** The {@code method} of a shortfall row; there is no test behind it. */
    static final String RUN = "(test run)";

    /** Every shortfall message starts with this. */
    static final String PREFIX = "test run cancelled";

    private CancelledShortfall() {}

    /**
     * The failure a cancelled run reports, or {@code null} when the run completed: a cancel that
     * landed after the last class had already run and every worker exited cleanly left nothing
     * unrun, and the summary stands on its own.
     */
    static @Nullable String of(boolean cancelled, int worstExit, int undispatched) {
        if (!cancelled) return null;
        if (worstExit == 0 && undispatched == 0) return null;
        StringBuilder why = new StringBuilder(PREFIX);
        if (undispatched > 0) {
            why.append(": ")
                    .append(undispatched)
                    .append(undispatched == 1 ? " class" : " classes")
                    .append(" never ran");
        }
        if (worstExit != 0) {
            why.append(undispatched > 0 ? "; " : ": ").append("a worker was stopped mid-class");
        }
        return why.toString();
    }

    /** The shortfall row for {@code moduleLabel}, or {@code null} when {@link #of} says the run completed. */
    static @Nullable TestFailureInfo row(String moduleLabel, boolean cancelled, int worstExit, int undispatched) {
        String why = of(cancelled, worstExit, undispatched);
        return why == null ? null : new TestFailureInfo(moduleLabel, "", "", RUN, "", why, "", 0);
    }

    /** Whether {@code f} is a shortfall row rather than a test that ran and failed. */
    public static boolean isRow(TestFailureInfo f) {
        return RUN.equals(f.method()) && f.message().startsWith(PREFIX);
    }

    /** How many of {@code failures} are shortfall rows. */
    public static long rows(List<TestFailureInfo> failures) {
        long n = 0;
        for (TestFailureInfo f : failures) if (isRow(f)) n++;
        return n;
    }
}
