// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Cache/store inventory. {@code query} is one of {@code usage}, {@code store-usage}, {@code
 * repo-search} ({@code terms}), {@code repo-refresh} ({@code coords}), or {@code wipe-store}
 * ({@code dryRun} counts without deleting).
 */
public record CacheInventoryRequest(
        @Nullable String query,
        @Nullable String cache,
        @Nullable String store,
        @Nullable List<String> terms,
        @Nullable List<String> coords,
        boolean dryRun) {

    public CacheInventoryRequest {
        terms = terms == null ? List.of() : List.copyOf(terms);
        coords = coords == null ? List.of() : List.copyOf(coords);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.CACHE_INVENTORY_REQUEST)
                .string("query", query)
                .string("cache", cache)
                .string("store", store)
                .array("terms", terms)
                .array("coords", coords)
                .bool("dryRun", dryRun)
                .finish();
    }

    public static CacheInventoryRequest decode(String json) {
        return new CacheInventoryRequest(
                Jsonl.str(json, "query"),
                Jsonl.str(json, "cache"),
                Jsonl.str(json, "store"),
                Jsonl.strArray(json, "terms"),
                Jsonl.strArray(json, "coords"),
                Jsonl.bool(json, "dryRun", false));
    }
}
