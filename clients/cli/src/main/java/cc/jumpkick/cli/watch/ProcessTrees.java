// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.watch;

import cc.jumpkick.cli.tui.GlobalCancel;
import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stops a set of process trees together: every tree is snapshotted (descendants first, so a
 * script's real server does not outlive it), signalled at once, given one shared grace, force-killed
 * where still alive, then waited for once more so "stopped" means gone. The whole pass fits strictly
 * inside {@link GlobalCancel#HOOKS_BOUND_MILLIS}, which is what lets the SIGINT hook run it.
 */
final class ProcessTrees {

    /** How long the trees get to exit on their own after the polite signal. */
    static final Duration GRACE = Duration.ofSeconds(3);

    /** How long a force-killed survivor gets to be gone; grace + settle leave the SIGINT hook a second to spare. */
    static final Duration SETTLE =
            Duration.ofMillis(GlobalCancel.HOOKS_BOUND_MILLIS).minus(GRACE).minusSeconds(1);

    private ProcessTrees() {}

    /** Stop {@code roots} and everything beneath them; a root that is already gone is nothing to do. */
    static void stop(Collection<ProcessHandle> roots, Clock clock) {
        List<ProcessHandle> family = new ArrayList<>();
        for (ProcessHandle root : roots) {
            root.descendants().forEach(family::add);
            family.add(root);
        }
        family.removeIf(h -> !h.isAlive());
        if (family.isEmpty()) return;
        family.forEach(ProcessHandle::destroy);
        awaitGone(family, GRACE, clock);
        List<ProcessHandle> survivors =
                family.stream().filter(ProcessHandle::isAlive).toList();
        if (survivors.isEmpty()) return;
        survivors.forEach(ProcessHandle::destroyForcibly);
        awaitGone(survivors, SETTLE, clock);
    }

    private static void awaitGone(List<ProcessHandle> handles, Duration budget, Clock clock) {
        long deadline = clock.nanos() + budget.toNanos();
        while (clock.nanos() < deadline && handles.stream().anyMatch(ProcessHandle::isAlive)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
