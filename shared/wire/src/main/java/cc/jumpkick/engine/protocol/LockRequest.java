// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;

/** A dependency lock request. */
public record LockRequest(
        String dir,
        String cache,
        List<String> features,
        boolean noDefaultFeatures,
        boolean sources,
        String repoUrl,
        boolean offline,
        boolean force,
        boolean verbose,
        boolean conservative) {

    public LockRequest {
        features = features == null ? List.of() : List.copyOf(features);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.LOCK_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .array("features", features)
                .bool("noDefaultFeatures", noDefaultFeatures)
                .bool("sources", sources)
                .string("repoUrl", repoUrl)
                .bool("offline", offline)
                .bool("force", force)
                .bool("verbose", verbose)
                .bool("conservative", conservative)
                .finish();
    }

    public static LockRequest decode(String json) {
        return new LockRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.strArray(json, "features"),
                Jsonl.bool(json, "noDefaultFeatures", false),
                Jsonl.bool(json, "sources", false),
                Jsonl.str(json, "repoUrl"),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false),
                Jsonl.bool(json, "conservative", false));
    }
}
