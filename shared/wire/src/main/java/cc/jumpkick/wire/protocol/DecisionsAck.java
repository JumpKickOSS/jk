// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Terminal for {@link EngineProtocol#DECISIONS_REQUEST}. {@code decisions} is a JSON array of the
 * decisions as {@code GET /api/decisions} lists them; {@code decision} is the one an answer settled
 * (or found already settled, or raised), as a JSON object; {@code link} is the dashboard link to it
 * while the dashboard serves. {@code spent} answers a {@code spend}. A non-null {@code error} is
 * printable.
 */
public record DecisionsAck(
        @Nullable String error,
        String decisions,
        @Nullable String decision,
        @Nullable String link,
        boolean spent) {

    public DecisionsAck(@Nullable String error, String decisions, @Nullable String decision) {
        this(error, decisions, decision, null, false);
    }

    public static DecisionsAck error(String message) {
        return new DecisionsAck(message, "[]", null);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.DECISIONS_ACK)
                .string("error", error)
                .string("decisions", decisions)
                .string("decision", decision)
                .string("link", link)
                .bool("spent", spent)
                .finish();
    }

    public static DecisionsAck decode(String line) {
        String decisions = Jsonl.str(line, "decisions");
        return new DecisionsAck(
                Jsonl.str(line, "error"),
                decisions == null ? "[]" : decisions,
                Jsonl.str(line, "decision"),
                Jsonl.str(line, "link"),
                Jsonl.bool(line, "spent", false));
    }
}
