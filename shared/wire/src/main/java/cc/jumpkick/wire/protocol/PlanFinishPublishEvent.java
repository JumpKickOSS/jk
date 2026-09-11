// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A publish run's terminal with its uploaded-file count (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishPublishEvent(String dir, boolean success, int files) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "publish")
                .string("dir", dir)
                .bool("success", success)
                .number("publishFiles", files)
                .finish();
    }

    public static PlanFinishPublishEvent decode(String json) {
        return new PlanFinishPublishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "publishFiles", 0));
    }
}
