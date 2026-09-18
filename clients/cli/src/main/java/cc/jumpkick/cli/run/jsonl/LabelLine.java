// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A task's live label. */
public record LabelLine(long ts, String task, String label) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.LABEL)
                .string("task", task)
                .string("label", label)
                .finish();
    }

    public static LabelLine decode(String json) {
        return new LabelLine(
                Jsonl.longValue(json, "ts", 0), Jsonl.requiredStr(json, "task"), Jsonl.requiredStr(json, "label"));
    }
}
