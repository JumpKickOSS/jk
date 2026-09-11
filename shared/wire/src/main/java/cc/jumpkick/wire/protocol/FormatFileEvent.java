// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** One file's format result; {@code index}/{@code total} drive the client's per-file bar (see {@link EngineProtocol#FORMAT_FILE}). */
public record FormatFileEvent(
        String dir, String path, String status, @Nullable String message, int index, int total) {
    public String encode() {
        return RequestJson.request(EngineProtocol.FORMAT_FILE)
                .string("dir", dir)
                .string("path", path)
                .string("status", status)
                .string("message", message)
                .number("index", index)
                .number("total", total)
                .finish();
    }

    public static FormatFileEvent decode(String json) {
        return new FormatFileEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "path"),
                Jsonl.requiredStr(json, "status"),
                Jsonl.str(json, "message"),
                Jsonl.intValue(json, "index", 0),
                Jsonl.intValue(json, "total", 0));
    }
}
