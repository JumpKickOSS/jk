// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.transcript.JsonlEnvelope;

/** The workspace graph starts: how many modules it holds. */
public record WorkspaceStartLine(long ts, int modules) {
    public String encode() {
        return JsonlEnvelope.open(ts, "workspace-start")
                .number("modules", modules)
                .finish();
    }

    public static WorkspaceStartLine decode(String json) {
        return new WorkspaceStartLine(Jsonl.longValue(json, "ts", 0), Jsonl.intValue(json, "modules", 0));
    }
}
