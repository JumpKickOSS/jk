// SPDX-License-Identifier: Apache-2.0
/**
 * The MCP tools, one class each, with their registry and the prompts that name them. Adding a tool
 * is a class here and one line in {@link cc.jumpkick.engine.http.mcp.tools.McpTools#standard()} —
 * the same shape hosted verbs use, and for the same reason: an explicit list is greppable and a
 * {@code ServiceLoader} is not.
 *
 * <p>A tool decodes arguments through {@link cc.jumpkick.engine.http.mcp.McpCall} and delegates
 * the work to an owner in the parent package. A tool body that computes a fact itself is a fact
 * with two owners.
 */
@NullMarked
package cc.jumpkick.engine.http.mcp.tools;

import org.jspecify.annotations.NullMarked;
