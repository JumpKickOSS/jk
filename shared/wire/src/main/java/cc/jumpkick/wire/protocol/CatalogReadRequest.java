// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read the library catalog engine-side ({@code jk search} and friends). */
public record CatalogReadRequest(
        @Nullable String dir,
        @Nullable String cache,
        @Nullable String query,
        @Nullable List<String> terms,
        boolean offline,
        boolean includeCached,
        boolean bundledOnly) {

    public CatalogReadRequest {
        terms = terms == null ? List.of() : List.copyOf(terms);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.CATALOG_READ_REQUEST)
                .string("dir", dir)
                .string("cache", cache)
                .string("query", query)
                .array("terms", terms)
                .bool("offline", offline)
                .bool("includeCached", includeCached)
                .bool("bundledOnly", bundledOnly)
                .finish();
    }

    public static CatalogReadRequest decode(String json) {
        return new CatalogReadRequest(
                Jsonl.str(json, "dir"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "query"),
                Jsonl.strArray(json, "terms"),
                Jsonl.bool(json, "offline", false),
                Jsonl.bool(json, "includeCached", false),
                Jsonl.bool(json, "bundledOnly", false));
    }
}
