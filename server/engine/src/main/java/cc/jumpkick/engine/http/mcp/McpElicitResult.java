// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How an {@link McpCall#elicit} ended. {@link Accepted}, {@link Declined} and {@link Cancelled}
 * are the human's three answers; the rest say the question never got one.
 */
public sealed interface McpElicitResult {

    /** The human submitted. {@code content} holds the form's fields; it is empty for URL mode. */
    record Accepted(Map<String, Object> content) implements McpElicitResult {}

    /** The human said no. */
    record Declined() implements McpElicitResult {}

    /** The human dismissed the question without choosing. */
    record Cancelled() implements McpElicitResult {}

    /** Nothing was sent: the session cannot be asked in this mode, and {@code reason} says why. */
    record Unsupported(String reason) implements McpElicitResult {}

    /** The caller's timeout passed with no answer; the client was told to withdraw the question. */
    record TimedOut() implements McpElicitResult {}

    /** The client answered with an error, an unknown action, or the stream to it broke. */
    record Failed(String message) implements McpElicitResult {}

    /** The client's JSON-RPC response to an {@code elicitation/create}. */
    static McpElicitResult of(Map<String, Object> response) {
        if (response.get("error") instanceof Map<?, ?> error) {
            return new Failed("client answered " + error.get("code") + ": " + error.get("message"));
        }
        if (!(response.get("result") instanceof Map<?, ?> result)) return new Failed("client answered no result");
        Object action = result.get("action");
        return switch (action == null ? "" : String.valueOf(action)) {
            case "accept" -> new Accepted(content(result.get("content")));
            case "decline" -> new Declined();
            case "cancel" -> new Cancelled();
            default -> new Failed("client answered action " + action);
        };
    }

    private static Map<String, Object> content(@Nullable Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getValue() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return Map.copyOf(out);
    }
}
