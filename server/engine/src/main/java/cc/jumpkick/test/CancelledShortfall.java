// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import org.jspecify.annotations.Nullable;

/**
 * A cancelled test run is not a green one. The kill we asked for leaves classes undispatched and
 * the in-flight ones unreported; every class that did run may have passed, and a summary that
 * only counted those would let run-tests stamp the suite green — the next build then skips the
 * tests that never ran. One failure names the shortfall instead.
 */
final class CancelledShortfall {

    private CancelledShortfall() {}

    /**
     * The failure a cancelled run reports, or {@code null} when the run completed: a cancel that
     * landed after the last class had already run and every worker exited cleanly left nothing
     * unrun, and the summary stands on its own.
     */
    static @Nullable String of(boolean cancelled, int worstExit, int undispatched) {
        if (!cancelled) return null;
        if (worstExit == 0 && undispatched == 0) return null;
        StringBuilder why = new StringBuilder("test run cancelled");
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
}
