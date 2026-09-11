// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** A task's interpolated tick update. */
public record TickUpdateLine(long ts, String task, int delta, long denominator) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.TICK_UPDATE)
                .string("task", task)
                .number("delta", delta)
                .number("denominator", denominator)
                .finish();
    }

    public static TickUpdateLine decode(String json) {
        return new TickUpdateLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "task"),
                Jsonl.intValue(json, "delta", 0),
                Jsonl.longValue(json, "denominator", 0));
    }
}
