// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A workspace module is about to run its plan. */
public record ModuleStartLine(long ts, String dir, String coord) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.MODULE_START)
                .string("dir", dir)
                .string("coord", coord)
                .finish();
    }

    public static ModuleStartLine decode(String json) {
        return new ModuleStartLine(
                Jsonl.longValue(json, "ts", 0), Jsonl.requiredStr(json, "dir"), Jsonl.requiredStr(json, "coord"));
    }
}
