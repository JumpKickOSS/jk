// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import cc.jumpkick.jsonl.Jsonl;
import java.util.Objects;

/**
 * A step's wait (see {@link EngineProtocol#TASK_WAIT}): {@code reason} is {@code slot} or {@code
 * memory} while none of its forks runs and the next one waits for a lease, {@code thread} while its
 * body waits for an engine thread, or a phrase naming a lock another run holds; {@code ""} once it
 * works again.
 */
public record TaskWaitEvent(String dir, String task, String reason) {
    public String encode() {
        return RequestJson.event(EngineProtocol.TASK_WAIT)
                .string("dir", dir)
                .string("task", task)
                .string("reason", reason)
                .finish();
    }

    public static TaskWaitEvent decode(String json) {
        return new TaskWaitEvent(
                Jsonl.requiredStr(json, "dir"),
                Jsonl.requiredStr(json, "task"),
                Objects.requireNonNullElse(Jsonl.str(json, "reason"), ""));
    }
}
