// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import org.jspecify.annotations.Nullable;

/**
 * Result of a {@link GuardFreezeRequest}: how many entries were accepted (or dropped), and the
 * baseline's new entry count; non-null {@code error} is printable and means nothing was written.
 */
public record GuardFreezeAck(@Nullable String error, int accepted, int total) {

    public static GuardFreezeAck error(String message) {
        return new GuardFreezeAck(message, 0, 0);
    }

    public String encode() {
        return RequestJson.request(EngineProtocol.GUARD_FREEZE_ACK)
                .string("error", error)
                .number("accepted", accepted)
                .number("total", total)
                .finish();
    }

    public static GuardFreezeAck decode(String line) {
        return new GuardFreezeAck(
                Jsonl.str(line, "error"), Jsonl.intValue(line, "accepted", 0), Jsonl.intValue(line, "total", 0));
    }
}
