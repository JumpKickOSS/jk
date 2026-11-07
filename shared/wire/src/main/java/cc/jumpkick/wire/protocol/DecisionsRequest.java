// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk decide}: {@code list} the engine's decisions, or {@code accept} or {@code decline} the
 * one {@code id} names. {@code answers} is the accepted form's values as a JSON object. {@code tty}
 * says the answer was typed at a terminal, which is what lets it settle a trust-changing decision.
 */
public record DecisionsRequest(
        String action, @Nullable String id, @Nullable String answers, boolean tty) {

    public static final String LIST = "list";
    public static final String ACCEPT = "accept";
    public static final String DECLINE = "decline";

    public DecisionsRequest {
        action = action == null || action.isBlank() ? LIST : action;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.DECISIONS_REQUEST)
                .string("action", action)
                .string("id", id)
                .string("answers", answers)
                .bool("tty", tty)
                .finish();
    }

    public static DecisionsRequest decode(String json) {
        String action = Jsonl.str(json, "action");
        return new DecisionsRequest(
                action == null ? LIST : action,
                Jsonl.str(json, "id"),
                Jsonl.str(json, "answers"),
                Jsonl.bool(json, "tty", false));
    }
}
