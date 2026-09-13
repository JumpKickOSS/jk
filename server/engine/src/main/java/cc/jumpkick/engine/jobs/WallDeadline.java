// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.JobLimits;

/**
 * The wall deadline one job runs under and the knob that set it, so the cancel reason can name
 * what to change. {@code ms == 0} is unbounded. A socket job takes the engine-wide deadline (EOF
 * is its real bound); a detached job takes the deadline its submission carried, else the engine's
 * detached default.
 */
record WallDeadline(long ms, String knob) {

    WallDeadline {
        if (ms < 0) throw new IllegalArgumentException("a wall deadline is a duration in ms and cannot be negative");
    }

    static WallDeadline of(JobLimits limits, JobTransport transport) {
        if (transport instanceof JobTransport.FireAndForget detached) {
            Long requested = detached.deadlineMs();
            if (requested != null) return new WallDeadline(requested, "the request's deadline");
            return new WallDeadline(
                    limits.detachedDeadlineMs(), "[engine] detached-deadline-ms / JK_ENGINE_DETACHED_DEADLINE_MS");
        }
        return new WallDeadline(limits.deadlineMs(), "JK_ENGINE_JOB_DEADLINE_MS");
    }

    boolean bounded() {
        return ms > 0;
    }

    /** The reason a deadline cancel leaves on the record and the wire. */
    String reason() {
        return "exceeded the " + ms + "ms wall deadline (" + knob + "); cancelled";
    }
}
