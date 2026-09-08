// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** A plan starts: its name, denominator and task count. */
public record PlanStartLine(long ts, String plan, long denominator, int tasks) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.BUILDPLAN_START)
                .string("plan", plan)
                .number("denominator", denominator)
                .number("tasks", tasks)
                .finish();
    }

    public static PlanStartLine decode(String json) {
        return new PlanStartLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.str(json, "plan"),
                Jsonl.longValue(json, "denominator", 0),
                Jsonl.intValue(json, "tasks", 0));
    }
}
