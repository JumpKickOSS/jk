// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.wire.runtime.EtaSpread;

/**
 * Remaining wall-work in ms; {@code millis} duplicates {@code remainingMs} for older readers, {@code
 * fullMillis} rides only when non-negative (see {@link EngineProtocol#ETA}). {@code lowMs} and
 * {@code highMs} bound {@code remainingMs}, its likely range (JK-4TEZ7); -1 when there is none.
 * {@code confidence} is the probability that the truth lands within ±10% of {@code remainingMs}
 * (JK-R0N5S); -1 when there is none.
 */
public record EtaEvent(long remainingMs, long fullMillis, long lowMs, long highMs, double confidence) {

    public EtaEvent(long remainingMs, long fullMillis) {
        this(remainingMs, fullMillis, -1, -1, -1);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.ETA)
                .number("millis", remainingMs)
                .number("remainingMs", remainingMs)
                .optionalNumber("fullMillis", fullMillis, -1)
                .optionalNumber("lowMs", lowMs, -1)
                .optionalNumber("highMs", highMs, -1)
                .optionalToken("confidence", EtaSpread.token(confidence))
                .finish();
    }

    public static EtaEvent decode(String json) {
        return new EtaEvent(
                Jsonl.longValue(json, "remainingMs", 0),
                Jsonl.longValue(json, "fullMillis", -1),
                Jsonl.longValue(json, "lowMs", -1),
                Jsonl.longValue(json, "highMs", -1),
                Jsonl.doubleValue(json, "confidence", -1));
    }
}
