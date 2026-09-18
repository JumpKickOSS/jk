// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A workspace-level line the user keeps: a fact about the run as a whole, owned by no module. */
public record NoteLine(long ts, String text) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.NOTE)
                .string("text", text, "")
                .finish();
    }

    public static NoteLine decode(String json) {
        return new NoteLine(Jsonl.longValue(json, "ts", 0), Jsonl.requiredStr(json, "text"));
    }
}
