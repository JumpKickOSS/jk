// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** Workspace preflight verdict: the stage, its unit counts and the engine's label. */
public record PreflightLine(long ts, String stage, int done, int totalUnits, String label) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.PREFLIGHT)
                .string("stage", stage, "")
                .number("done", Math.max(0, done))
                .number("totalUnits", Math.max(0, totalUnits))
                .string("label", label, "")
                .finish();
    }

    public static PreflightLine decode(String json) {
        return new PreflightLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.str(json, "stage"),
                Jsonl.intValue(json, "done", 0),
                Jsonl.intValue(json, "totalUnits", 0),
                Jsonl.str(json, "label"));
    }
}
