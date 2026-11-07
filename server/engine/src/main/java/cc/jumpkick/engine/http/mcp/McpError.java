// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.Nullable;

/**
 * A failure anywhere on the MCP surface, carrying the JSON-RPC code the transport reports
 * verbatim: {@code -32601} unknown method, {@code -32602} bad argument, {@code -32000} a hosted
 * operation that could not run, {@code -32042} a call that needs the user to open a link first,
 * {@code -32603} an engine fault.
 */
public final class McpError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final transient @Nullable Object data;

    public McpError(int code, @Nullable String message) {
        this(code, message, null);
    }

    /** An error whose {@code error.data} carries {@code data}, as {@code -32042} carries its elicitations. */
    public McpError(int code, @Nullable String message, @Nullable Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    /** The JSON-RPC {@code error.data}, or null for none. */
    public @Nullable Object data() {
        return data;
    }
}
