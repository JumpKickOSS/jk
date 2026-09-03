// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** Delete one build-history entry by id or locator. */
public record HistoryDeleteRequest(@Nullable String id) {

    public String encode() {
        return RequestJson.request(EngineProtocol.HISTORY_DELETE_REQUEST)
                .string("id", id)
                .finish();
    }

    public static HistoryDeleteRequest decode(String json) {
        return new HistoryDeleteRequest(Jsonl.str(json, "id"));
    }
}
