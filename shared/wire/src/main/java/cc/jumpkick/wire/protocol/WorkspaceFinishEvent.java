// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Workspace terminal; {@code upToDate} is {@link WorkspaceResult#upToDate}: the run succeeded and
 * did no work. {@code cancelled} is additive so a client settles as cancelled rather than
 * crashed. The rows in {@code errors} are plain strings here: the mint is the {@link
 * ProtoEvents#workspaceFinish} factory, whose {@link cc.jumpkick.config.Redacted} parameter is what
 * makes forgetting to mask a compile error — nothing constructs this record from raw worker output
 * (see {@link EngineProtocol#WORKSPACE_FINISH}). {@code conflict} is {@link
 * WorkspaceResult#conflict}, the text of one JSON object, {@code ""} (left out) otherwise. {@code
 * release} is {@link WorkspaceResult#release}, left out when the run has none.
 */
public record WorkspaceFinishEvent(
        boolean success,
        int exitCode,
        List<String> errors,
        boolean cancelled,
        WorkspaceResult.@Nullable SyncCounts sync,
        long millis,
        int modules,
        boolean upToDate,
        String conflict,
        @Nullable ReleaseOutcome release) {

    /** No sync counts: every goal but {@code jk sync}. */
    public WorkspaceFinishEvent(boolean success, int exitCode, List<String> errors, boolean cancelled) {
        this(success, exitCode, errors, cancelled, null, false);
    }

    /** The engine's terminal: the run's wall time and module count are the client's ({@code -1}, left out). */
    public WorkspaceFinishEvent(
            boolean success,
            int exitCode,
            List<String> errors,
            boolean cancelled,
            WorkspaceResult.@Nullable SyncCounts sync,
            boolean upToDate) {
        this(success, exitCode, errors, cancelled, sync, -1, -1, upToDate, "", null);
    }

    /** The client's terminal, with no conflict behind it. */
    public WorkspaceFinishEvent(
            boolean success,
            int exitCode,
            List<String> errors,
            boolean cancelled,
            WorkspaceResult.@Nullable SyncCounts sync,
            long millis,
            int modules,
            boolean upToDate) {
        this(success, exitCode, errors, cancelled, sync, millis, modules, upToDate, "", null);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.WORKSPACE_FINISH)
                .bool("success", success)
                .number("exitCode", exitCode)
                .array("errors", errors)
                .bool("cancelled", cancelled)
                .bool("upToDate", upToDate)
                .optionalToken(
                        "sync",
                        sync == null
                                ? null
                                : JsonFields.object()
                                        .number("fetched", sync.fetched())
                                        .number("upToDate", sync.upToDate())
                                        .finish())
                .optionalNumber("millis", millis, -1)
                .optionalNumber("modules", modules, -1)
                .optionalNonEmptyString("conflict", conflict)
                .optionalToken("release", release == null ? null : encodeRelease(release))
                .finish();
    }

    public static WorkspaceFinishEvent decode(String json) {
        return new WorkspaceFinishEvent(
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "exitCode", 0),
                Jsonl.strArray(json, "errors"),
                Jsonl.bool(json, "cancelled", false),
                syncOf(Jsonl.nested(json, "sync")),
                Jsonl.longValue(json, "millis", -1),
                Jsonl.intValue(json, "modules", -1),
                Jsonl.bool(json, "upToDate", false),
                Objects.requireNonNullElse(Jsonl.str(json, "conflict"), ""),
                releaseOf(Jsonl.nested(json, "release")));
    }

    private static String encodeRelease(ReleaseOutcome r) {
        StringBuilder files = new StringBuilder("[");
        for (ReleaseOutcome.File f : r.files()) {
            if (files.length() > 1) files.append(',');
            files.append(JsonFields.object()
                    .string("name", f.name())
                    .optionalNonEmptyString("module", f.module())
                    .optionalString("platform", f.platform())
                    .number("size", f.size())
                    .optionalNonEmptyString("sha256", f.sha256())
                    .string("role", f.role())
                    .string("status", f.status())
                    .finish());
        }
        return JsonFields.object()
                .string("destination", r.destination())
                .optionalTrue("stage", r.stage())
                .optionalTrue("dryRun", r.dryRun())
                .token("files", files.append(']').toString())
                .optionalString("latest", r.latest())
                .optionalArray("released", r.released().isEmpty() ? null : r.released())
                .optionalArray("notes", r.notes().isEmpty() ? null : r.notes())
                .finish();
    }

    private static @Nullable ReleaseOutcome releaseOf(@Nullable String release) {
        if (release == null) return null;
        List<ReleaseOutcome.File> files = new ArrayList<>();
        for (String f : Jsonl.objectArray(release, "files")) {
            files.add(new ReleaseOutcome.File(
                    Jsonl.requiredStr(f, "name"),
                    Objects.requireNonNullElse(Jsonl.str(f, "module"), ""),
                    Jsonl.str(f, "platform"),
                    Jsonl.longValue(f, "size", -1),
                    Objects.requireNonNullElse(Jsonl.str(f, "sha256"), ""),
                    Jsonl.requiredStr(f, "role"),
                    Jsonl.requiredStr(f, "status")));
        }
        return new ReleaseOutcome(
                Jsonl.requiredStr(release, "destination"),
                Jsonl.bool(release, "stage", false),
                Jsonl.bool(release, "dryRun", false),
                files,
                Jsonl.str(release, "latest"),
                Jsonl.strArray(release, "released"),
                Jsonl.strArray(release, "notes"));
    }

    private static WorkspaceResult.@Nullable SyncCounts syncOf(@Nullable String sync) {
        if (sync == null) return null;
        return new WorkspaceResult.SyncCounts(Jsonl.intValue(sync, "fetched", 0), Jsonl.intValue(sync, "upToDate", 0));
    }
}
