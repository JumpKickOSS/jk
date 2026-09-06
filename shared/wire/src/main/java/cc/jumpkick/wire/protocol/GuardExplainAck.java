// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Result of a {@link GuardExplainRequest}: the rendered text and the same payload as JSON, or a
 * printable {@code error} (unknown rule, unknown kind, no guards here).
 */
public record GuardExplainAck(@Nullable String error, String text, String json) {
    public static GuardExplainAck error(String message) {
        return new GuardExplainAck(message, "", "");
    }

    public String encode() {
        return "{\"type\":\"" + EngineProtocol.GUARD_EXPLAIN_ACK + "\""
                + ",\"error\":" + (error == null ? "null" : Jsonl.quote(error))
                + ",\"text\":" + Jsonl.quote(text)
                + ",\"json\":" + Jsonl.quote(json)
                + "}";
    }

    public static GuardExplainAck decode(String line) {
        String text = Jsonl.str(line, "text");
        String json = Jsonl.str(line, "json");
        return new GuardExplainAck(Jsonl.str(line, "error"), text == null ? "" : text, json == null ? "" : json);
    }
}
