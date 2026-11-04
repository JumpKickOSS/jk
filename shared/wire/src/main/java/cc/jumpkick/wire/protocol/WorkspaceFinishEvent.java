// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Workspace terminal; {@code cancelled} is additive so a client settles as cancelled rather than
 * crashed. The rows in {@code errors} are plain strings here: the mint is the {@link
 * ProtoEvents#workspaceFinish} factory, whose {@link cc.jumpkick.config.Redacted} parameter is what
 * makes forgetting to mask a compile error — nothing constructs this record from raw worker output
 * (see {@link EngineProtocol#WORKSPACE_FINISH}).
 */
public record WorkspaceFinishEvent(
        boolean success,
        int exitCode,
        List<String> errors,
        boolean cancelled,
        WorkspaceResult.@Nullable SyncCounts sync) {

    /** No sync counts: every goal but {@code jk sync}. */
    public WorkspaceFinishEvent(boolean success, int exitCode, List<String> errors, boolean cancelled) {
        this(success, exitCode, errors, cancelled, null);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.WORKSPACE_FINISH)
                .bool("success", success)
                .number("exitCode", exitCode)
                .array("errors", errors)
                .bool("cancelled", cancelled)
                .optionalToken(
                        "sync",
                        sync == null
                                ? null
                                : JsonFields.object()
                                        .number("fetched", sync.fetched())
                                        .number("upToDate", sync.upToDate())
                                        .finish())
                .finish();
    }

    public static WorkspaceFinishEvent decode(String json) {
        return new WorkspaceFinishEvent(
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.strArray(json, "errors"),
                Jsonl.bool(json, "cancelled", false),
                syncOf(Jsonl.nested(json, "sync")));
    }

    private static WorkspaceResult.@Nullable SyncCounts syncOf(@Nullable String sync) {
        if (sync == null) return null;
        return new WorkspaceResult.SyncCounts(Jsonl.intValue(sync, "fetched", 0), Jsonl.intValue(sync, "upToDate", 0));
    }
}
