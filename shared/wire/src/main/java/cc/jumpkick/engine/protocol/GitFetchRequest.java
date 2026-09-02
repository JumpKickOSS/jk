// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** A git checkout materialization request. */
public record GitFetchRequest(
        String url, String canonicalUrl, String ref, String cache, boolean refresh, boolean requireJkToml) {

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
