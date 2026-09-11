// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;

/** Engine workspace aggregate progress, mirrored from the engine's tracker snapshot. */
public record WorkspaceProgressLine(
        long ts, String dir, long numerator, long denominator, String phase, int modulesComplete, int modulesTotal) {
    public String encode() {
        return JsonlEnvelope.open(ts, EngineProtocol.WORKSPACE_PROGRESS)
                .string("dir", dir, "")
                .number("numerator", numerator)
                .number("denominator", denominator)
                .string("phase", phase, "")
                .number("modulesComplete", modulesComplete)
                .number("modulesTotal", modulesTotal)
                .finish();
    }

    public static WorkspaceProgressLine decode(String json) {
        return new WorkspaceProgressLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.requiredStr(json, "dir"),
                Jsonl.longValue(json, "numerator", 0),
                Jsonl.longValue(json, "denominator", 0),
                Jsonl.requiredStr(json, "phase"),
                Jsonl.intValue(json, "modulesComplete", 0),
                Jsonl.intValue(json, "modulesTotal", 0));
    }
}
