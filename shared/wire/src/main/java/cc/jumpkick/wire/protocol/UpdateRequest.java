// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A dependency update request. */
public record UpdateRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable List<String> features,
        boolean noDefaultFeatures,
        @Nullable String repoUrl,
        boolean gitOnly,
        @Nullable String gitTarget,
        boolean offline,
        boolean force,
        boolean verbose,
        @Nullable String platform) {

    public UpdateRequest {
        features = features == null ? List.of() : List.copyOf(features);
        platform = platform == null ? "" : platform;
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.UPDATE_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .array("features", features)
                .bool("noDefaultFeatures", noDefaultFeatures)
                .string("repoUrl", repoUrl)
                .bool("gitOnly", gitOnly)
                .string("gitTarget", gitTarget)
                .bool("offline", offline)
                .bool("force", force)
                .bool("verbose", verbose)
                .string("platform", platform)
                .finish();
    }

    public static UpdateRequest decode(String json) {
        return new UpdateRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.strArray(json, "features"),
                Jsonl.bool(json, "noDefaultFeatures", false),
                Jsonl.str(json, "repoUrl"),
                Jsonl.bool(json, "gitOnly", false),
                Jsonl.str(json, "gitTarget"),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "force", false),
                Jsonl.bool(json, "verbose", false),
                Jsonl.str(json, "platform"));
    }
}
