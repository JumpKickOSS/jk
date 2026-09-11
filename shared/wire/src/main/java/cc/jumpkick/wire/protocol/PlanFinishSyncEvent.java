// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A sync run's terminal with its fetched/up-to-date counts (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishSyncEvent(String dir, boolean success, long fetched, long upToDate) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "sync")
                .string("dir", dir)
                .bool("success", success)
                .number("syncFetched", fetched)
                .number("syncUpToDate", upToDate)
                .finish();
    }

    public static PlanFinishSyncEvent decode(String json) {
        return new PlanFinishSyncEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "syncFetched", 0),
                Jsonl.longValue(json, "syncUpToDate", 0));
    }
}
