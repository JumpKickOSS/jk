// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Whether the catalog feeds refreshed, and the error when they did not (see {@link EngineProtocol#FRESHEN_CATALOG_ACK}). */
public record FreshenCatalogAck(boolean ok, @Nullable String error) {
    public String encode() {
        return RequestJson.request(EngineProtocol.FRESHEN_CATALOG_ACK)
                .bool("ok", ok)
                .string("error", error)
                .finish();
    }

    public static FreshenCatalogAck decode(String json) {
        return new FreshenCatalogAck(Jsonl.bool(json, "ok", false), Jsonl.str(json, "error"));
    }
}
