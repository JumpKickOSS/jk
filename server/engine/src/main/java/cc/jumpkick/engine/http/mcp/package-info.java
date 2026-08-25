// SPDX-License-Identifier: Apache-2.0
/**
 * The MCP surface behind {@code POST /mcp}: {@link cc.jumpkick.engine.http.mcp.McpRpc} frames
 * JSON-RPC, {@link cc.jumpkick.engine.http.mcp.McpTools} is the explicit tool registry, and the
 * rest of the package are the read/write owners a tool body delegates to. One class + one registry
 * line adds a tool; nothing here scans the classpath.
 */
@NullMarked
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.NullMarked;
