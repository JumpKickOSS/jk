// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** A git checkout materialization request. */
public record GitFetchRequest(
        @Nullable String url,
        @Nullable String canonicalUrl,
        @Nullable String ref,
        @Nullable String cache,
        boolean refresh,
        boolean requireJkToml) {

    public String encode() {
        return RequestJson.request(EngineProtocol.GIT_FETCH_REQUEST)
                .string("url", url)
                .string("canonicalUrl", canonicalUrl)
                .string("ref", ref)
                .string("cache", cache)
                .bool("refresh", refresh)
                .bool("requireJkToml", requireJkToml)
                .finish();
    }

    public static GitFetchRequest decode(String json) {
        return new GitFetchRequest(
                Jsonl.str(json, "url"),
                Jsonl.str(json, "canonicalUrl"),
                Jsonl.str(json, "ref"),
                Jsonl.str(json, "cache"),
                Jsonl.bool(json, "refresh", false),
                Jsonl.bool(json, "requireJkToml", true));
    }
}
