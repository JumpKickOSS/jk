// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** The savings rows for the runs and actions since {@code since} (epoch millis; 0 for all kept). */
public record StatsRequest(long since) {

    public String encode() {
        return RequestJson.request(EngineProtocol.STATS_REQUEST)
                .number("since", since)
                .finish();
    }

    public static StatsRequest decode(String json) {
        return new StatsRequest(Math.max(0, Jsonl.longValue(json, "since", 0)));
    }
}
