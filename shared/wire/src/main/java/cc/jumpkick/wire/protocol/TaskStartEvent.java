// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A task starts: dir, task, stage and its tick budget (see {@link EngineProtocol#TASK_START}). */
public record TaskStartEvent(String dir, String task, String stage, int ticks) {
    public String encode() {
        return RequestJson.event(EngineProtocol.TASK_START)
                .string("dir", dir)
                .string("task", task)
                .string("stage", stage)
                .number("ticks", ticks)
                .finish();
    }

    public static TaskStartEvent decode(String json) {
        return new TaskStartEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "stage"),
                Jsonl.intValue(json, "ticks", 0));
    }
}
