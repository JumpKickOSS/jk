// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** A workspace module finished. */
public record ModuleFinishLine(long ts, String dir, String coord, boolean success, long durationMs) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.MODULE_FINISH)
                .string("dir", dir)
                .string("coord", coord)
                .bool("success", success)
                .number("duration_ms", durationMs)
                .finish();
    }

    public static ModuleFinishLine decode(String json) {
        return new ModuleFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "coord"),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "duration_ms", 0));
    }
}
