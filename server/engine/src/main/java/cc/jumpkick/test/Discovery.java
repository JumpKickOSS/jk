// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.engine.plugin.WorkerExit;
import cc.jumpkick.orchestrator.DiscoveryListing;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What one list-only test-discovery fork produced: the classes it named, the tests its plan holds
 * (those under no class included), whether {@code discovery_total} closed the list, its exit code, what it printed outside the protocol (the crash
 * text when it died), the command it was started with, and the parent-side exception when the
 * parent's own decoder ended the conversation. Which classes the list holds and whether a suite may
 * run off it are the orchestrator's to decide ({@link DiscoveryListing}); what is left here only
 * spells the failure of a fork whose list is not runnable, which no green marker records.
 */
record Discovery(
        List<String> classes,
        int tests,
        boolean complete,
        WorkerExit exit,
        String output,
        List<String> command,
        @Nullable RuntimeException handler) {

    /** The fork's verdict once it exited {@code exit} with {@code output} printed outside the protocol. */
    static Discovery ended(DiscoveryListing listing, WorkerExit exit, String output, List<String> command) {
        return new Discovery(listing.classes(), listing.tests(), listing.complete(), exit, output, command, null);
    }

    /**
     * A fork whose conversation the parent's own protocol handler ended. The exit code is the kill
     * the parent asked for and says nothing; the classes named before the throw are a list the
     * decoder could not finish reading, so none of it is trusted.
     */
    static Discovery handlerFailed(DiscoveryListing listing, String output, RuntimeException handler) {
        return new Discovery(
                listing.classes(), listing.tests(), false, WorkerExit.noWorker(-1), output, List.of(), handler);
    }

    /** The fork said nothing usable about the suite: its list is not one a suite may run off ({@link DiscoveryListing#runnable}). */
    boolean crashed() {
        return !DiscoveryListing.runnable(complete, handler != null);
    }

    /**
     * Whether a runnable list holds nothing a suite JVM would run: no class and no test outside one.
     * A plan of features or scripts names no class but still counts its tests.
     */
    boolean holdsNoTest() {
        return !crashed() && classes.isEmpty() && tests == 0;
    }

    /**
     * The verdict for a fork that {@link #crashed()}. A conversation the parent's own decoder ended
     * is the parent's bug and stays a {@code (test run)} row; any other fork that ended before its
     * total is a {@link TestLauncherFailure} — the exit code and what the JVM printed fail the
     * step, so zero tests are never counted as passed.
     */
    TestSummary verdict(String moduleLabel) {
        if (handler != null) {
            return new TestSummary(1, 0, 1, 0, List.of(WorkerFailureRow.discovery(moduleLabel, handler)));
        }
        if (classes.isEmpty()) throw TestLauncherFailure.discovery(moduleLabel, exit, output, command);
        throw TestLauncherFailure.discoveryCutShort(moduleLabel, exit, output, command, classes.size());
    }
}
