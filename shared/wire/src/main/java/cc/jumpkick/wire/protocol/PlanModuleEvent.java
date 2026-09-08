// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One module of the plan burst: dir, coord, plan name, weight and whether every step is cached (see {@link EngineProtocol#PLAN_MODULE}). */
public record PlanModuleEvent(String dir, String coord, String planName, int weight, boolean fullyCached) {
    public String encode() {
        return RequestJson.request(EngineProtocol.PLAN_MODULE)
                .string("dir", dir)
                .string("coord", coord)
                .string("planName", planName)
                .number("weight", weight)
                .bool("fullyCached", fullyCached)
                .finish();
    }

    public static PlanModuleEvent decode(String json) {
        return new PlanModuleEvent(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "coord"),
                Jsonl.str(json, "planName"),
                Jsonl.intValue(json, "weight", 0),
                Jsonl.bool(json, "fullyCached", false));
    }
}
