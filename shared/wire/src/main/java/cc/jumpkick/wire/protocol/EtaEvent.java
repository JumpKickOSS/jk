// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;

/**
 * Remaining wall-work in ms; {@code millis} duplicates {@code remainingMs} for older readers, {@code
 * fullMillis} rides only when non-negative (see {@link EngineProtocol#ETA}). {@code lowMs} and
 * {@code highMs} bound {@code remainingMs}, its likely range (JK-4TEZ7); -1 when there is none.
 */
public record EtaEvent(long remainingMs, long fullMillis, long lowMs, long highMs) {

    public EtaEvent(long remainingMs, long fullMillis) {
        this(remainingMs, fullMillis, -1, -1);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.ETA)
                .number("millis", remainingMs)
                .number("remainingMs", remainingMs)
                .optionalNumber("fullMillis", fullMillis, -1)
                .optionalNumber("lowMs", lowMs, -1)
                .optionalNumber("highMs", highMs, -1)
                .finish();
    }

    public static EtaEvent decode(String json) {
        return new EtaEvent(
                Jsonl.longValue(json, "remainingMs", 0),
                Jsonl.longValue(json, "fullMillis", -1),
                Jsonl.longValue(json, "lowMs", -1),
                Jsonl.longValue(json, "highMs", -1));
    }
}
