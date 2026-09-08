// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * Workspace terminal; {@code cancelled} is additive so a client settles as cancelled rather than
 * crashed. The rows in {@code errors} are plain strings here: the mint is the {@link
 * ProtoEvents#workspaceFinish} factory, whose {@link cc.jumpkick.config.Redacted} parameter is what
 * makes forgetting to mask a compile error — nothing constructs this record from raw worker output
 * (see {@link EngineProtocol#WORKSPACE_FINISH}).
 */
public record WorkspaceFinishEvent(boolean success, int exitCode, List<String> errors, boolean cancelled) {
    public String encode() {
        return RequestJson.request(EngineProtocol.WORKSPACE_FINISH)
                .bool("success", success)
                .number("exitCode", exitCode)
                .array("errors", errors)
                .bool("cancelled", cancelled)
                .finish();
    }

    public static WorkspaceFinishEvent decode(String json) {
        return new WorkspaceFinishEvent(
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.strArray(json, "errors"),
                Jsonl.bool(json, "cancelled", false));
    }
}
