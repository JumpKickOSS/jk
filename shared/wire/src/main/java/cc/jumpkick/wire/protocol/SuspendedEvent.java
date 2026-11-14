// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/**
 * The engine was not running for {@code suspendedMs} from {@code startedAt} (epoch ms): the machine
 * slept or the process was stopped ({@link EngineProtocol#SUSPENDED}). {@code monoMs} is how far the
 * monotonic clock moved across it, which a countdown on that clock takes off its elapsed time.
 */
public record SuspendedEvent(String dir, long startedAt, long suspendedMs, long monoMs) {

    public String encode() {
        return RequestJson.event(EngineProtocol.SUSPENDED)
                .string("dir", dir, "")
                .optionalNumber("startedAt", startedAt, 0)
                .optionalNumber("suspendedMs", suspendedMs, 0)
                .optionalNumber("monoMs", monoMs, 0)
                .finish();
    }

    public static SuspendedEvent decode(String json) {
        return new SuspendedEvent(
                Objects.requireNonNullElse(Jsonl.str(json, "dir"), ""),
                Jsonl.longValue(json, "startedAt", 0),
                Jsonl.longValue(json, "suspendedMs", 0),
                Jsonl.longValue(json, "monoMs", 0));
    }
}
