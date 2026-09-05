// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.Nullable;

/**
 * A failure anywhere on the MCP surface, carrying the JSON-RPC code the transport reports
 * verbatim: {@code -32601} unknown method, {@code -32602} bad argument, {@code -32000} a hosted
 * operation that could not run, {@code -32603} an engine fault.
 */
public final class McpError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public McpError(int code, @Nullable String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
