// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A build plan's terminal with the cancel flag and nothing else (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishEvent(String dir, boolean success, boolean cancelled) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "build")
                .string("dir", dir)
                .bool("success", success)
                .bool("cancelled", cancelled)
                .finish();
    }

    public static PlanFinishEvent decode(String json) {
        return new PlanFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.bool(json, "cancelled", false));
    }
}
