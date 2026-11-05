// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.io.IOException;
import java.util.Map;

/**
 * The way back to the client while one of its requests is outstanding. Over Streamable HTTP it is
 * the request's own response, turned into an event stream on the first message; the request's
 * JSON-RPC response is the stream's last event. A server request sent here is answered by the
 * client in a later {@code POST /mcp}.
 */
@FunctionalInterface
public interface McpOutbound {

    /** Send one JSON-RPC request or notification. Throws when the client is no longer listening. */
    void send(Map<String, Object> message) throws IOException;
}
