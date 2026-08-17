// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.Nullable;

/**
 * Process-wide bind for this engine's MCP surface. A resident engine is one developer / one
 * workspace; later tools inherit {@link #dir()} when {@code arguments.dir} is omitted.
 */
public final class McpSession {

    private volatile @Nullable String dir;

    public void bind(String dir) {
        this.dir = dir == null || dir.isBlank() ? null : dir;
    }

    public @Nullable String dir() {
        return dir;
    }
}
