// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk decide} and the commands that ask a person: {@code list} the engine's decisions, {@code
 * accept} or {@code decline} the one {@code id} names, {@code spend} an accepted one (true once, so
 * it proceeds one operation), or {@code raise} the question {@code ask} carries. {@code answers} is
 * the accepted form's values as a JSON object; {@code ask} is {@code {kind, question, detail[],
 * items[{id,label,help}], yes, project, alert}}, where {@code alert} asks the engine to tell a person
 * the way {@code [ui] ask} says.
 */
public record DecisionsRequest(
        String action,
        @Nullable String id,
        @Nullable String answers,
        @Nullable String ask) {

    public static final String LIST = "list";
    public static final String ACCEPT = "accept";
    public static final String DECLINE = "decline";
    public static final String SPEND = "spend";
    public static final String RAISE = "raise";

    public DecisionsRequest {
        action = action == null || action.isBlank() ? LIST : action;
    }

    public static DecisionsRequest list() {
        return new DecisionsRequest(LIST, null, null, null);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.DECISIONS_REQUEST)
                .string("action", action)
                .string("id", id)
                .string("answers", answers)
                .string("ask", ask)
                .finish();
    }

    public static DecisionsRequest decode(String json) {
        String action = Jsonl.str(json, "action");
        return new DecisionsRequest(
                action == null ? LIST : action,
                Jsonl.str(json, "id"),
                Jsonl.str(json, "answers"),
                Jsonl.str(json, "ask"));
    }
}
