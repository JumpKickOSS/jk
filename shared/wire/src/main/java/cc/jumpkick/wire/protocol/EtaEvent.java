// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/** Remaining wall-work in ms; {@code millis} duplicates {@code remainingMs} for older readers, {@code fullMillis} rides only when non-negative (see {@link EngineProtocol#ETA}). */
public record EtaEvent(long remainingMs, long fullMillis) {
    public String encode() {
        return RequestJson.request(EngineProtocol.ETA)
                .number("millis", remainingMs)
                .number("remainingMs", remainingMs)
                .optionalNumber("fullMillis", fullMillis, -1)
                .finish();
    }

    public static EtaEvent decode(String json) {
        return new EtaEvent(Jsonl.longValue(json, "remainingMs", 0), Jsonl.longValue(json, "fullMillis", -1));
    }
}
