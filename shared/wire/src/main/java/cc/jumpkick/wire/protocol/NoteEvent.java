// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A workspace-level line the user keeps (see {@link EngineProtocol#NOTE}). */
public record NoteEvent(String text) {
    public String encode() {
        return RequestJson.request(EngineProtocol.NOTE).string("text", text, "").finish();
    }

    public static NoteEvent decode(String json) {
        return new NoteEvent(Jsonl.requiredStr(json, "text"));
    }
}
