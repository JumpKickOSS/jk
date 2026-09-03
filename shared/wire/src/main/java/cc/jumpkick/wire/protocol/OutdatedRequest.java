// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Report newer versions of the project's dependencies ({@code jk outdated}). */
public record OutdatedRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String repoUrl,
        boolean offline,
        boolean force) {

    public String encode() {
        return RequestJson.request(EngineProtocol.OUTDATED_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("repoUrl", repoUrl)
                .bool("offline", offline)
                .bool("force", force)
                .finish();
    }

    public static OutdatedRequest decode(String json) {
        return new OutdatedRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "repoUrl"),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false));
    }
}
