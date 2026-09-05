// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A dependency lock request. {@code freshen} marks an invisible freshen — one a command ran for the
 * user rather than {@code jk lock} itself: pins are always kept and an already-current lock is a
 * no-op. Otherwise {@code force} decides: bare {@code jk lock} keeps pins, {@code jk lock -F}
 * floats within the declared ranges.
 */
public record LockRequest(
        @Nullable String dir,
        @Nullable String cache,
        List<String> features,
        boolean noDefaultFeatures,
        boolean sources,
        @Nullable String repoUrl,
        boolean offline,
        boolean force,
        boolean verbose,
        boolean freshen) {

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
                .bool("freshen", freshen)
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
                Jsonl.bool(json, "freshen", false));
    }
}
