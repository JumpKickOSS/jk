// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/**
 * The build went on hold for memory, or resumed ({@link EngineProtocol#HOLD_START}, {@link
 * EngineProtocol#HOLD_END}). A hold starts when a fork has waited for a worker lease past a moment
 * and ends when the build's last waiting fork leaves the queue. {@code needBytes} and {@code
 * freeBytes} describe the wait a hold started on; {@code heldMs} is how long a resumed hold lasted.
 */
public record MemoryHoldEvent(boolean held, String dir, long needBytes, long freeBytes, long heldMs) {

    /** The hold that starts: the memory a fork needs and what is free. */
    public static MemoryHoldEvent start(String dir, long needBytes, long freeBytes) {
        return new MemoryHoldEvent(true, dir, needBytes, freeBytes, 0);
    }

    /** The hold that ended after {@code heldMs}. */
    public static MemoryHoldEvent end(String dir, long heldMs) {
        return new MemoryHoldEvent(false, dir, 0, 0, heldMs);
    }

    public String type() {
        return held ? EngineProtocol.HOLD_START : EngineProtocol.HOLD_END;
    }

    public String encode() {
        return RequestJson.event(type())
                .string("dir", dir, "")
                .optionalNumber("needBytes", needBytes, 0)
                .optionalNumber("freeBytes", freeBytes, 0)
                .optionalNumber("heldMs", heldMs, 0)
                .finish();
    }

    public static MemoryHoldEvent decode(String json) {
        boolean held = EngineProtocol.HOLD_START.equals(Jsonl.str(json, EngineProtocol.TYPE_FIELD));
        return new MemoryHoldEvent(
                held,
                Objects.requireNonNullElse(Jsonl.str(json, "dir"), ""),
                Jsonl.longValue(json, "needBytes", 0),
                Jsonl.longValue(json, "freeBytes", 0),
                Jsonl.longValue(json, "heldMs", 0));
    }
}
