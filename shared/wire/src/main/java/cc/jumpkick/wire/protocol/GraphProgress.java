// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A graph refresh's progress: {@code done} of {@code total} files settled, and what is running now. */
public record GraphProgress(int done, int total, String label) {

    public String encode() {
        return RequestJson.event(EngineProtocol.GRAPH_PROGRESS)
                .number("done", done)
                .number("total", total)
                .string("label", label)
                .finish();
    }

    public static GraphProgress decode(String json) {
        String label = Jsonl.str(json, "label");
        return new GraphProgress(
                Jsonl.intValue(json, "done", 0), Jsonl.intValue(json, "total", 0), label == null ? "" : label);
    }
}
