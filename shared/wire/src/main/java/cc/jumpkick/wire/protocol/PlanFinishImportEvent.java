// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** An import run's terminal: worker exit, warning count and error text (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishImportEvent(
        String dir,
        boolean success,
        int exitCode,
        int warnings,
        @Nullable String error) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "import")
                .string("dir", dir)
                .bool("success", success)
                .number("importExit", exitCode)
                .number("importWarnings", warnings)
                .string("importError", error)
                .finish();
    }

    public static PlanFinishImportEvent decode(String json) {
        return new PlanFinishImportEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "importExit", 0),
                Jsonl.intValue(json, "importWarnings", 0),
                Jsonl.str(json, "importError"));
    }
}
