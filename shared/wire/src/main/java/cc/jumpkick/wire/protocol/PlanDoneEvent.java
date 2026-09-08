// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** The plan burst is complete: how many tasks it named (see {@link EngineProtocol#PLAN_DONE}). */
public record PlanDoneEvent(int count) {
    public String encode() {
        return RequestJson.request(EngineProtocol.PLAN_DONE)
                .number("count", count)
                .finish();
    }

    public static PlanDoneEvent decode(String json) {
        return new PlanDoneEvent(Jsonl.intValue(json, "count", 0));
    }
}
