// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Result of a {@link GuardFreezeRequest}: how many entries were accepted (or dropped), the
 * baseline's new entry count, and how many lanes had a smaller population accepted as their floor;
 * non-null {@code error} is printable and means nothing was written.
 */
public record GuardFreezeAck(@Nullable String error, int accepted, int total, int rebased) {

    public static GuardFreezeAck error(String message) {
        return new GuardFreezeAck(message, 0, 0, 0);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_FREEZE_ACK)
                .string("error", error)
                .number("accepted", accepted)
                .number("total", total)
                .number("rebased", rebased)
                .finish();
    }

    public static GuardFreezeAck decode(String line) {
        return new GuardFreezeAck(
                Jsonl.str(line, "error"),
                Jsonl.intValue(line, "accepted", 0),
                Jsonl.intValue(line, "total", 0),
                Jsonl.intValue(line, "rebased", 0));
    }
}
