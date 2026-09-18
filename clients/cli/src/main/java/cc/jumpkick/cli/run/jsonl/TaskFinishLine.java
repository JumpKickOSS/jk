// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A task's terminal: status, wall duration and the part of it spent waiting. */
public record TaskFinishLine(long ts, String task, String stage, String status, long durationMs, long waitMs) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.TASK_FINISH)
                .string("task", task)
                .string("stage", stage)
                .string("status", status)
                .number("duration_ms", durationMs)
                .number("wait_ms", waitMs)
                .finish();
    }

    public static TaskFinishLine decode(String json) {
        return new TaskFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "stage"),
                Jsonl.requiredStr(json, "status"),
                Jsonl.longValue(json, "duration_ms", 0),
                Jsonl.longValue(json, "wait_ms", 0));
    }
}
