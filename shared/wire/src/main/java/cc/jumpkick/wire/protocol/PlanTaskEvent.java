// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One task of the plan burst: dir, name, label and stage (see {@link EngineProtocol#PLAN_TASK}). */
public record PlanTaskEvent(String dir, String name, String label, String stage) {
    public String encode() {
        return RequestJson.request(EngineProtocol.PLAN_TASK)
                .string("dir", dir)
                .string("name", name)
                .string("label", label)
                .string("stage", stage)
                .finish();
    }

    public static PlanTaskEvent decode(String json) {
        return new PlanTaskEvent(
                Jsonl.str(json, "dir"), Jsonl.str(json, "name"), Jsonl.str(json, "label"), Jsonl.str(json, "stage"));
    }
}
