// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A task's live label (see {@link EngineProtocol#LABEL}). */
public record LabelEvent(String dir, String task, String label) {
    public String encode() {
        return RequestJson.event(EngineProtocol.LABEL)
                .string("dir", dir)
                .string("task", task)
                .string("label", label)
                .finish();
    }

    public static LabelEvent decode(String json) {
        return new LabelEvent(Jsonl.str(json, "dir"), Jsonl.str(json, "task"), Jsonl.str(json, "label"));
    }
}
