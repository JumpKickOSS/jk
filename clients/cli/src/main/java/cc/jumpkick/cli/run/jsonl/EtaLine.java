// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** ETA estimate in wall ms. */
public record EtaLine(long ts, long etaMs) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.ETA)
                .number("etaMs", Math.max(0, etaMs))
                .finish();
    }

    public static EtaLine decode(String json) {
        return new EtaLine(Jsonl.longValue(json, "ts", 0), Jsonl.longValue(json, "etaMs", 0));
    }
}
