// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run.jsonl;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.transcript.JsonlEnvelope;
import org.jspecify.annotations.Nullable;

/**
 * The workspace graph finished: success, wall duration and module count, and when the run synced
 * (a build's sync stage, or {@code jk sync}) what it fetched and found up to date, shaped as the
 * wire's {@code workspace-finish} carries it.
 */
public record WorkspaceFinishLine(
        long ts,
        boolean success,
        long durationMs,
        int modules,
        @Nullable Sync sync) {

    /** What the run's sync fetched and found already on disk. */
    public record Sync(int fetched, int upToDate) {}

    public WorkspaceFinishLine(long ts, boolean success, long durationMs, int modules) {
        this(ts, success, durationMs, modules, null);
    }

    public String encode() {
        JsonFields line = JsonlEnvelope.open(ts, EngineProtocol.WORKSPACE_FINISH)
                .bool("success", success)
                .number("duration_ms", durationMs)
                .number("modules", modules);
        if (sync != null) {
            line.token(
                    "sync",
                    JsonFields.object()
                            .number("fetched", sync.fetched())
                            .number("upToDate", sync.upToDate())
                            .finish());
        }
        return line.finish();
    }

    public static WorkspaceFinishLine decode(String json) {
        String sync = Jsonl.nested(json, "sync");
        return new WorkspaceFinishLine(
                Jsonl.longValue(json, "ts", 0),
                Jsonl.bool(json, "success", false),
                Jsonl.longValue(json, "duration_ms", 0),
                Jsonl.intValue(json, "modules", 0),
                sync == null
                        ? null
                        : new Sync(Jsonl.intValue(sync, "fetched", 0), Jsonl.intValue(sync, "upToDate", 0)));
    }
}
