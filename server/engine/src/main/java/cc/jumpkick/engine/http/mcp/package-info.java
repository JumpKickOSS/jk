// SPDX-License-Identifier: Apache-2.0
/**
 * The MCP surface behind {@code POST /mcp}: {@link cc.jumpkick.engine.http.mcp.McpRpc} frames
 * JSON-RPC, and the rest of the package are the read/write owners a tool body delegates to. The
 * tools and their registry live in {@code mcp.tools}, which reads this package and is never read
 * by it; nothing here scans the classpath.
 */
@NullMarked
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.NullMarked;
