// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/**
 * A build plan's terminal (see {@link EngineProtocol#BUILDPLAN_FINISH}): its success and cancel
 * flags, and when the plan ran in the client, its name, wall time and diagnostic counts ({@code -1}
 * and empty are left out).
 */
public record PlanFinishEvent(
        String dir, boolean success, boolean cancelled, String planName, long millis, int warnings, int errors) {

    /** The engine's terminal: the flags and nothing else. */
    public PlanFinishEvent(String dir, boolean success, boolean cancelled) {
        this(dir, success, cancelled, "", -1, -1, -1);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "build")
                .string("dir", dir)
                .bool("success", success)
                .bool("cancelled", cancelled)
                .optionalNonEmptyString("planName", planName)
                .optionalNumber("millis", millis, -1)
                .optionalNumber("warnings", warnings, -1)
                .optionalNumber("errors", errors, -1)
                .finish();
    }

    public static PlanFinishEvent decode(String json) {
        return new PlanFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.bool(json, "cancelled", false),
                Objects.requireNonNullElse(Jsonl.str(json, "planName"), ""),
                Jsonl.longValue(json, "millis", -1),
                Jsonl.intValue(json, "warnings", -1),
                Jsonl.intValue(json, "errors", -1));
    }
}
