// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/**
 * A publish run's terminal with its uploaded-file count and the files the run wrote under the
 * module's target/ — the SBOM documents — when there are any (see {@link
 * EngineProtocol#BUILDPLAN_FINISH}).
 */
public record PlanFinishPublishEvent(String dir, boolean success, int files, List<String> written) {

    public PlanFinishPublishEvent {
        written = List.copyOf(written);
    }

    public String encode() {
        RequestJson json = RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "publish")
                .string("dir", dir)
                .bool("success", success)
                .number("publishFiles", files);
        if (!written.isEmpty()) json.array("publishWritten", written);
        return json.finish();
    }

    public static PlanFinishPublishEvent decode(String json) {
        return new PlanFinishPublishEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "publishFiles", 0),
                Jsonl.strArray(json, "publishWritten"));
    }
}
