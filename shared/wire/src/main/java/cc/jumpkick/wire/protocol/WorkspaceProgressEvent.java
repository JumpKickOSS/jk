// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.WorkspaceProgressTracker;

/**
 * Workspace aggregate progress: bar units, the phase, module counts and the remaining-work oracle;
 * {@code progressPercent} is NaN when no percent applies (see {@link EngineProtocol#WORKSPACE_PROGRESS}).
 * {@code heldMs} is the time the build has been on hold for memory ({@link EngineProtocol#HOLD_START}),
 * -1 when it is not: a held event carries {@code "state": "on-hold"} and the held time, and its
 * remaining time is the countdown frozen where the hold found it.
 */
public record WorkspaceProgressEvent(
        String dir,
        long numerator,
        long denominator,
        double progressPercent,
        String phase,
        int modulesComplete,
        int modulesTotal,
        long remainingMs,
        long r0Ms,
        long heldMs) {

    /** The state a held event carries. */
    public static final String ON_HOLD = "on-hold";

    public WorkspaceProgressEvent(
            String dir,
            long numerator,
            long denominator,
            double progressPercent,
            String phase,
            int modulesComplete,
            int modulesTotal,
            long remainingMs,
            long r0Ms) {
        this(dir, numerator, denominator, progressPercent, phase, modulesComplete, modulesTotal, remainingMs, r0Ms, -1);
    }

    /** Whether the build is on hold for memory. */
    public boolean onHold() {
        return heldMs >= 0;
    }

    public String encode() {
        return RequestJson.event(EngineProtocol.WORKSPACE_PROGRESS)
                .string("dir", dir, "")
                .number("numerator", numerator)
                .number("denominator", denominator)
                .token("progress", WorkspaceProgressTracker.progressToken(progressPercent))
                .string("phase", phase, "")
                .number("modulesComplete", modulesComplete)
                .number("modulesTotal", modulesTotal)
                .number("remainingMs", remainingMs)
                .number("R0", Math.max(0, r0Ms))
                .optionalString("state", heldMs >= 0 ? ON_HOLD : null)
                .optionalNumber("heldMs", heldMs, -1)
                .finish();
    }

    public static WorkspaceProgressEvent decode(String json) {
        return new WorkspaceProgressEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.longValue(json, "numerator", 0),
                Jsonl.longValue(json, "denominator", 0),
                Jsonl.doubleValue(json, "progress", Double.NaN),
                Jsonl.requiredStr(json, "phase"),
                Jsonl.intValue(json, "modulesComplete", 0),
                Jsonl.intValue(json, "modulesTotal", 0),
                Jsonl.longValue(json, "remainingMs", -1),
                Jsonl.longValue(json, "R0", 0),
                ON_HOLD.equals(Jsonl.str(json, "state")) ? Math.max(0, Jsonl.longValue(json, "heldMs", 0)) : -1);
    }
}
