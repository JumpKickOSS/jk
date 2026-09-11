// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** A git-fetch request's terminal: checkout path and sha, {@code null} on failure (see {@link EngineProtocol#BUILDPLAN_FINISH}). */
public record PlanFinishGitFetchEvent(
        String dir,
        boolean success,
        @Nullable String checkout,
        @Nullable String sha) {
    public String encode() {
        return RequestJson.request(EngineProtocol.BUILDPLAN_FINISH)
                .string("kind", "git-fetch")
                .string("dir", dir)
                .bool("success", success)
                .string("gitCheckout", checkout)
                .string("gitSha", sha)
                .finish();
    }

    public static PlanFinishGitFetchEvent decode(String json) {
        return new PlanFinishGitFetchEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.bool(json, "success", false),
                Jsonl.str(json, "gitCheckout"),
                Jsonl.str(json, "gitSha"));
    }
}
