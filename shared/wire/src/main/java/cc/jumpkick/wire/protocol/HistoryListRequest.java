// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** List the newest {@code limit} build-history entries. */
public record HistoryListRequest(int limit) {

    public String encode() {
        return RequestJson.request(EngineProtocol.HISTORY_LIST_REQUEST)
                .number("limit", limit)
                .finish();
    }

    public static HistoryListRequest decode(String json) {
        return new HistoryListRequest(Jsonl.intValue(json, "limit", 200));
    }
}
