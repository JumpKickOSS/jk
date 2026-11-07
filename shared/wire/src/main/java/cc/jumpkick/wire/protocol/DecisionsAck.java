// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for {@link EngineProtocol#DECISIONS_REQUEST}. {@code decisions} is a JSON array of the
 * decisions as {@code GET /api/decisions} lists them; {@code decision} is the one an answer settled
 * (or found already settled), as a JSON object. A non-null {@code error} is printable.
 */
public record DecisionsAck(
        @Nullable String error, String decisions, @Nullable String decision) {

    public static DecisionsAck error(String message) {
        return new DecisionsAck(message, "[]", null);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.DECISIONS_ACK)
                .string("error", error)
                .string("decisions", decisions)
                .string("decision", decision)
                .finish();
    }

    public static DecisionsAck decode(String line) {
        String decisions = Jsonl.str(line, "decisions");
        return new DecisionsAck(
                Jsonl.str(line, "error"), decisions == null ? "[]" : decisions, Jsonl.str(line, "decision"));
    }
}
