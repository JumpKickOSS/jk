// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Cache/store inventory. {@code query} is one of {@code usage}, {@code store-usage}, {@code
 * repo-search} ({@code terms}), {@code repo-refresh} ({@code coords}), {@code wipe-store}
 * ({@code dryRun} counts without deleting), {@code workers} (every installed plugin worker with
 * the launch classpath the engine rebuilds for it), or {@code drop-workers} (delete the installed
 * workers and forget their memoised classpaths; {@code dryRun} counts).
 *
 * <p>{@code cache}, {@code store} and {@code m2} are resolved by the <em>client</em> from its own
 * environment and sent, because each is what that client's next command would act on; an engine
 * resolving them would answer for its own. Blank means "you decide".
 */
public record CacheInventoryRequest(
        @Nullable String query,
        @Nullable String cache,
        @Nullable String store,
        @Nullable String m2,
        List<String> terms,
        List<String> coords,
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
                .string("m2", m2)
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
                Jsonl.str(json, "m2"),
                Jsonl.strArray(json, "terms"),
                Jsonl.strArray(json, "coords"),
                Jsonl.bool(json, "dryRun", false));
    }
}
