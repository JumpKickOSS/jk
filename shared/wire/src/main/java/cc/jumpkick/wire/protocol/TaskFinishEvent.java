// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A task's terminal: status, wall millis (queue wait included) and the part spent waiting (see {@link EngineProtocol#TASK_FINISH}). */
public record TaskFinishEvent(String dir, String task, String stage, String status, long millis, long waitMillis) {
    public String encode() {
        return RequestJson.request(EngineProtocol.TASK_FINISH)
                .string("dir", dir)
                .string("task", task)
                .string("stage", stage)
                .string("status", status)
                .number("millis", millis)
                .number("waitMillis", waitMillis)
                .finish();
    }

    public static TaskFinishEvent decode(String json) {
        return new TaskFinishEvent(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "task"),
                Jsonl.str(json, "stage"),
                Jsonl.str(json, "status"),
                Jsonl.longValue(json, "millis", 0),
                Jsonl.longValue(json, "waitMillis", 0));
    }
}
