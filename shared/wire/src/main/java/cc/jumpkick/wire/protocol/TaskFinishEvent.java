// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/**
 * A task's terminal: status, wall millis (queue wait included), the part spent waiting, and the
 * task's last word on what it did ({@code label}, when it said one; see {@link
 * EngineProtocol#TASK_FINISH}).
 */
public record TaskFinishEvent(
        String dir, String task, String stage, String status, long millis, long waitMillis, String label) {

    public TaskFinishEvent(String dir, String task, String stage, String status, long millis, long waitMillis) {
        this(dir, task, stage, status, millis, waitMillis, "");
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.TASK_FINISH)
                .string("dir", dir)
                .string("task", task)
                .string("stage", stage)
                .string("status", status)
                .number("millis", millis)
                .number("waitMillis", waitMillis)
                .optionalNonEmptyString("label", label)
                .finish();
    }

    public static TaskFinishEvent decode(String json) {
        return new TaskFinishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "stage"),
                Jsonl.requiredStr(json, "status"),
                Jsonl.longValue(json, "millis", 0),
                Jsonl.longValue(json, "waitMillis", 0),
                Objects.requireNonNullElse(Jsonl.str(json, "label"), ""));
    }
}
