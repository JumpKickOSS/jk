// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A plan's terminal: success, wall duration, and warning and error counts. */
public record PlanFinishLine(long ts, String plan, boolean success, long durationMs, int warnings, int errors) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.BUILDPLAN_FINISH)
                .string("plan", plan)
                .bool("success", success)
                .number("duration_ms", durationMs)
                .number("warnings", warnings)
                .number("errors", errors)
                .finish();
    }

    public static PlanFinishLine decode(String json) {
        return new PlanFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "plan"),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "duration_ms", 0),
                Jsonl.intValue(json, "warnings", 0),
                Jsonl.intValue(json, "errors", 0));
    }
}
