// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What every applied jk.toml edit must tell the agent next. */
final class ManifestEdits {

    private ManifestEdits() {}

    /** Applied jk.toml edits stale {@code manifests-sha256}; the result must say how to re-lock. */
    static @Nullable String relockHint(Map<String, Object> data) {
        return Boolean.TRUE.equals(data.get("applied")) ? "run kind=lock to refresh the stale jk-lock.toml" : null;
    }
}
