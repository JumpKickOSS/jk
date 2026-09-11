// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Preflight progress: one stage's done/total with a label (see {@link EngineProtocol#PREFLIGHT}). */
public record PreflightEvent(String stage, int done, int total, String label) {
    public String encode() {
        return RequestJson.request(EngineProtocol.PREFLIGHT)
                .string("stage", stage, "")
                .number("done", done)
                .number("total", total)
                .string("label", label, "")
                .finish();
    }

    public static PreflightEvent decode(String json) {
        return new PreflightEvent(
                Jsonl.requiredStr(json, "stage"),
                Jsonl.intValue(json, "done", 0),
                Jsonl.intValue(json, "total", 0),
                Jsonl.requiredStr(json, "label"));
    }
}
