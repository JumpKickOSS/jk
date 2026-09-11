// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One line of a task's output (see {@link EngineProtocol#OUTPUT}). */
public record OutputEvent(String dir, String task, String line) {
    public String encode() {
        return RequestJson.event(EngineProtocol.OUTPUT)
                .string("dir", dir)
                .string("task", task)
                .string("line", line)
                .finish();
    }

    public static OutputEvent decode(String json) {
        return new OutputEvent(
                Jsonl.requiredStr(json, "dir"), Jsonl.requiredStr(json, "task"), Jsonl.requiredStr(json, "line"));
    }
}
