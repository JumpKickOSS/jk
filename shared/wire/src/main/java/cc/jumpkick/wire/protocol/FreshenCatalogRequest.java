// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Refresh one bundled catalog ({@code templates}, {@code libraries}, {@code jdks}); {@code url} and
 * {@code cacheFile} override the defaults, {@code force} refreshes even when present.
 */
public record FreshenCatalogRequest(
        @Nullable String catalog,
        boolean offline,
        @Nullable String url,
        @Nullable String cacheFile,
        boolean force) {

    public String encode() {
        return RequestJson.request(EngineProtocol.FRESHEN_CATALOG_REQUEST)
                .string("catalog", catalog)
                .bool("offline", offline)
                .string("url", url)
                .string("cacheFile", cacheFile)
                .bool("force", force)
                .finish();
    }

    public static FreshenCatalogRequest decode(String json) {
        return new FreshenCatalogRequest(
                Jsonl.str(json, "catalog"),
                Jsonl.bool(json, "offline", false),
                Jsonl.str(json, "url"),
                Jsonl.str(json, "cacheFile"),
                Jsonl.bool(json, "force", false));
    }
}
