// SPDX-License-Identifier: Apache-2.0

/**
 * What the HTTP front, the MCP router and its tools all read: the job and admission seams, the
 * progress-token registry and the status and cache snapshots. It imports no other {@code
 * engine.http} package, so the front can depend on the router without the router reaching back.
 */
@NullMarked
package cc.jumpkick.engine.http.api;

import org.jspecify.annotations.NullMarked;
