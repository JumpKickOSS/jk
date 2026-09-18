// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A task starts: task, stage and tick budget. */
public record TaskStartLine(long ts, String task, String stage, int ticks) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.TASK_START)
                .string("task", task)
                .string("stage", stage)
                .number("ticks", ticks)
                .finish();
    }

    public static TaskStartLine decode(String json) {
        return new TaskStartLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "task"),
                Jsonl.requiredStr(json, "stage"),
                Jsonl.intValue(json, "ticks", 0));
    }
}
