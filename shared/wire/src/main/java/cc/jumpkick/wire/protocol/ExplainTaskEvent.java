// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** One task of a {@code jk explain} module: forecast status and text, and the action key when known (see {@link EngineProtocol#EXPLAIN_TASK}). */
public record ExplainTaskEvent(
        String dir,
        String name,
        String status,
        String text,
        @Nullable String key) {
    public String encode() {
        return RequestJson.request(EngineProtocol.EXPLAIN_TASK)
                .string("dir", dir)
                .string("name", name)
                .string("status", status)
                .string("text", text)
                .string("key", key)
                .finish();
    }

    public static ExplainTaskEvent decode(String json) {
        return new ExplainTaskEvent(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "name"),
                Jsonl.str(json, "status"),
                Jsonl.str(json, "text"),
                Jsonl.str(json, "key"));
    }
}
