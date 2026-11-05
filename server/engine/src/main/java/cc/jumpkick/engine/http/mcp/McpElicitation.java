// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One question for the human behind a client: the {@code params} of an {@code elicitation/create}
 * a tool sends with {@link McpCall#elicit}. The {@code mode} field rides only on a session that
 * negotiated 2025-11-25; earlier revisions know form mode alone and omit it.
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/client/elicitation">Elicitation</a>
 */
public sealed interface McpElicitation {

    /** What the host shows the human. */
    String message();

    /** The request's {@code params} under {@code revision}. */
    Map<String, Object> params(McpRevision revision);

    /**
     * Form mode: the host renders {@code requestedSchema}, a flat JSON Schema object whose
     * properties are strings, numbers, booleans or enums, and answers its fields as {@code
     * content}. Never for a secret: those go through {@link Url}.
     */
    record Form(String message, Map<String, Object> requestedSchema) implements McpElicitation {
        @Override
        public Map<String, Object> params(McpRevision revision) {
            Map<String, Object> p = new LinkedHashMap<>();
            if (revision.urlElicitation()) p.put("mode", "form");
            p.put("message", message);
            p.put("requestedSchema", requestedSchema);
            return p;
        }
    }

    /**
     * URL mode: the host asks the human before opening {@code url}, and the interaction happens
     * there, out of band. An accept means only that the human agreed to go; {@code elicitationId}
     * names the interaction for a later {@code notifications/elicitation/complete}.
     */
    record Url(String message, String url, String elicitationId) implements McpElicitation {
        @Override
        public Map<String, Object> params(McpRevision revision) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("mode", "url");
            p.put("elicitationId", elicitationId);
            p.put("url", url);
            p.put("message", message);
            return p;
        }
    }
}
