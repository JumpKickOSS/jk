// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** The workspace graph finished: success, wall duration and module count. */
public record WorkspaceFinishLine(long ts, boolean success, long durationMs, int modules) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.WORKSPACE_FINISH)
                .bool("success", success)
                .number("duration_ms", durationMs)
                .number("modules", modules)
                .finish();
    }

    public static WorkspaceFinishLine decode(String json) {
        return new WorkspaceFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "duration_ms", 0),
                Jsonl.intValue(json, "modules", 0));
    }
}
