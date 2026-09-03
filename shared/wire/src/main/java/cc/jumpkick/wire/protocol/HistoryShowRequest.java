// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/** One build-history entry's detail by id or locator. */
public record HistoryShowRequest(@Nullable String id) {

    public String encode() {
        return RequestJson.request(EngineProtocol.HISTORY_SHOW_REQUEST)
                .string("id", id)
                .finish();
    }

    public static HistoryShowRequest decode(String json) {
        return new HistoryShowRequest(Jsonl.str(json, "id"));
    }
}
