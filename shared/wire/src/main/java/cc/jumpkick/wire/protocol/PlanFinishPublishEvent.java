// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A publish run's terminal with its uploaded-file count, the files the run wrote under the
 * module's target/ — the SBOM documents, a Central bundle — when there are any, and, for a Central
 * Portal publish, the deployment the Portal assigned: its id, the state the poll ended in and the
 * validation errors it reported. {@code bundle} lists the entries of a Central bundle (see {@link
 * EngineProtocol#BUILDPLAN_FINISH}).
 */
public record PlanFinishPublishEvent(
        String dir,
        boolean success,
        int files,
        List<String> written,
        @Nullable String deploymentId,
        @Nullable String deploymentState,
        List<String> deploymentErrors,
        List<String> bundle) {

    public PlanFinishPublishEvent {
        written = List.copyOf(written);
        deploymentErrors = List.copyOf(deploymentErrors);
        bundle = List.copyOf(bundle);
    }

    /** A plain repository publish: no deployment, no bundle. */
    public PlanFinishPublishEvent(String dir, boolean success, int files, List<String> written) {
        this(dir, success, files, written, null, null, List.of(), List.of());
    }

    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "publish")
                .string("dir", dir)
                .bool("success", success)
                .number("publishFiles", files);
        if (!written.isEmpty()) json.array("publishWritten", written);
        if (deploymentId != null) json.string("deploymentId", deploymentId);
        if (deploymentState != null) json.string("deploymentState", deploymentState);
        if (!deploymentErrors.isEmpty()) json.array("deploymentErrors", deploymentErrors);
        if (!bundle.isEmpty()) json.array("publishBundle", bundle);
        return json.finish();
    }

    public static PlanFinishPublishEvent decode(String json) {
        return new PlanFinishPublishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "publishFiles", 0),
                Jsonl.strArray(json, "publishWritten"),
                Jsonl.str(json, "deploymentId"),
                Jsonl.str(json, "deploymentState"),
                Jsonl.strArray(json, "deploymentErrors"),
                Jsonl.strArray(json, "publishBundle"));
    }
}
