// SPDX-License-Identifier: Apache-2.0
/**
 * Decisions: questions an operation must put to a person before it proceeds, in one engine-side
 * queue that the agent's host, the dashboard and {@code jk decide} all answer. The first answer
 * wins. See {@code docs/user/mcp.md#decisions}.
 */
@NullMarked
package cc.jumpkick.engine.decisions;

import org.jspecify.annotations.NullMarked;
