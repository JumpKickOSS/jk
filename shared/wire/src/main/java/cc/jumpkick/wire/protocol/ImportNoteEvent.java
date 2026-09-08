// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** One import progress note (see {@link EngineProtocol#IMPORT_NOTE}). */
public record ImportNoteEvent(String dir, String kind, String text) {
    public String encode() {
        return RequestJson.request(EngineProtocol.IMPORT_NOTE)
                .string("dir", dir)
                .string("kind", kind)
                .string("text", text)
                .finish();
    }

    public static ImportNoteEvent decode(String json) {
        return new ImportNoteEvent(Jsonl.str(json, "dir"), Jsonl.str(json, "kind"), Jsonl.str(json, "text"));
    }
}
