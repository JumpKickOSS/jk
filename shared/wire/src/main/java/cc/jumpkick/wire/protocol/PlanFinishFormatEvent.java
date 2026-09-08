// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A format run's terminal: the source total and the formatter worker's exit (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishFormatEvent(String dir, boolean success, int total, int workerExit) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "format")
                .string("dir", dir)
                .bool("success", success)
                .number("formatTotal", total)
                .number("formatWorkerExit", workerExit)
                .finish();
    }

    public static PlanFinishFormatEvent decode(String json) {
        return new PlanFinishFormatEvent(
                Jsonl.str(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.intValue(json, "formatTotal", 0),
                Jsonl.intValue(json, "formatWorkerExit", 0));
    }
}
