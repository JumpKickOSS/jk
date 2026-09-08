// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** A task's progress tick with the plan's bar units. */
public record ProgressLine(long ts, String task, int delta, long numerator, long denominator) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.PROGRESS)
                .string("task", task)
                .number("delta", delta)
                .number("numerator", numerator)
                .number("denominator", denominator)
                .finish();
    }

    public static ProgressLine decode(String json) {
        return new ProgressLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.str(json, "task"),
                Jsonl.intValue(json, "delta", 0),
                Jsonl.longValue(json, "numerator", 0),
                Jsonl.longValue(json, "denominator", 0));
    }
}
